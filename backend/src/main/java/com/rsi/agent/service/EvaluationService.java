package com.rsi.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.agent.AgentRuntime;
import com.rsi.agent.agent.CapabilityRegistry;
import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.agent.TurnContracts;
import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.ChatSessionEntity;
import com.rsi.agent.domain.EvalEntities.EvalCaseEntity;
import com.rsi.agent.domain.EvalEntities.EvalDatasetEntity;
import com.rsi.agent.domain.EvalEntities.EvalResultEntity;
import com.rsi.agent.domain.EvalEntities.EvalRunEntity;
import com.rsi.agent.dto.PlatformDtos.*;
import com.rsi.agent.llm.LlmResult;
import com.rsi.agent.llm.LlmRouter;
import com.rsi.agent.repository.*;
import com.rsi.agent.security.Identity;
import com.rsi.agent.security.IdentityContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 评测闭环：黄金测试集跑批 + LLM 预判 + 人工抽检。
 *
 * <p>统计维度对齐线上真正关心的东西：Agent 选择准确率、工具成功率、
 * 端到端质量、时延与 Token 成本。
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private static final String JUDGE_SYSTEM = """
            你是 Agent 输出质量评审员。根据「用户问题」「参考答案（可能为空）」和「实际答复」打分。
            评分标准：
            - 1.0 完全正确且信息完整
            - 0.7 基本正确但有遗漏
            - 0.4 部分正确或含推测成分
            - 0.0 错误、答非所问，或在无证据情况下声称已完成写操作
            只输出 JSON：{"score": 数字, "comment": "一句话理由"}
            """;

    private final EvalDatasetRepository datasetRepository;
    private final EvalCaseRepository caseRepository;
    private final EvalRunRepository runRepository;
    private final EvalResultRepository resultRepository;
    private final ChatSessionRepository sessionRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final AgentRuntime runtime;
    private final LlmRouter llmRouter;

    public EvaluationService(EvalDatasetRepository datasetRepository, EvalCaseRepository caseRepository,
                             EvalRunRepository runRepository, EvalResultRepository resultRepository,
                             ChatSessionRepository sessionRepository,
                             CapabilityRegistry capabilityRegistry, AgentRuntime runtime,
                             LlmRouter llmRouter) {
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.sessionRepository = sessionRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.runtime = runtime;
        this.llmRouter = llmRouter;
    }

    // ---------------------------------------------------------------- 数据集

    @RequiresScope("eval:read")
    public List<EvalDatasetDto> listDatasets() {
        return datasetRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(dataset -> EvalDatasetDto.from(dataset,
                        caseRepository.findByDatasetIdOrderByCreatedAtAsc(dataset.getId())))
                .toList();
    }

    @RequiresScope("eval:write")
    @Transactional
    public EvalDatasetDto createDataset(EvalDatasetCreateRequest request, Identity identity) {
        if (datasetRepository.existsByName(request.name())) {
            throw BizException.conflict("数据集已存在：" + request.name());
        }
        EvalDatasetEntity dataset = new EvalDatasetEntity();
        dataset.setName(request.name());
        dataset.setDescription(request.description() == null ? "" : request.description());
        dataset.setOwner(identity.username());
        datasetRepository.save(dataset);

        if (request.cases() != null) {
            request.cases().forEach(item -> saveCase(dataset.getId(), item));
        }
        return EvalDatasetDto.from(dataset,
                caseRepository.findByDatasetIdOrderByCreatedAtAsc(dataset.getId()));
    }

    @RequiresScope("eval:write")
    @Transactional
    public EvalCaseDto addCase(String datasetId, EvalCaseCreateRequest request) {
        requireDataset(datasetId);
        return EvalCaseDto.from(saveCase(datasetId, request));
    }

    @RequiresScope("eval:write")
    @Transactional
    public void deleteCase(String caseId) {
        EvalCaseEntity item = caseRepository.findById(caseId)
                .orElseThrow(() -> BizException.notFound("用例"));
        caseRepository.delete(item);
    }

    private EvalCaseEntity saveCase(String datasetId, EvalCaseCreateRequest request) {
        EvalCaseEntity item = new EvalCaseEntity();
        item.setDatasetId(datasetId);
        item.setQuestion(request.question());
        item.setExpectedRouteType(orEmpty(request.expectedRouteType()));
        item.setExpectedTarget(orEmpty(request.expectedTarget()));
        item.setExpectedAnswer(orEmpty(request.expectedAnswer()));
        item.setTags(request.tags() == null ? List.of() : request.tags());
        return caseRepository.save(item);
    }

    // ---------------------------------------------------------------- 跑批

    @RequiresScope("eval:write")
    @Transactional
    public EvalRunDto startRun(String datasetId, Identity identity) {
        EvalDatasetEntity dataset = requireDataset(datasetId);
        long count = caseRepository.countByDatasetId(datasetId);
        if (count == 0) throw BizException.badRequest("数据集为空，请先添加用例");

        EvalRunEntity run = new EvalRunEntity();
        run.setDatasetId(datasetId);
        run.setName(dataset.getName() + " 跑批");
        run.setStatus("running");
        run.setTotal((int) count);
        run.setTriggeredBy(identity.username());
        runRepository.save(run);

        // 异步执行，接口立即返回；前端轮询进度
        executeRun(run.getId(), datasetId, identity);
        return EvalRunDto.from(run);
    }

    /**
     * 后台跑批。
     *
     * <p>{@code @Async} 让它跑在独立线程池上，因此必须重建身份上下文 ——
     * 与 SSE 编排同一个坑。
     */
    @Async
    public void executeRun(String runId, String datasetId, Identity identity) {
        IdentityContext.set(identity);
        try {
            EvalRunEntity run = runRepository.findById(runId).orElse(null);
            if (run == null) return;

            List<EvalCaseEntity> cases = caseRepository.findByDatasetIdOrderByCreatedAtAsc(datasetId);
            CapabilitySnapshot snapshot = capabilityRegistry.load(identity);
            List<EvalResultEntity> results = new ArrayList<>();

            for (EvalCaseEntity item : cases) {
                ChatSessionEntity session = new ChatSessionEntity();
                session.setTitle("[eval] " + abbreviate(item.getQuestion(), 20));
                session.setUserId(identity.id());
                session.setTenantId(identity.tenantId());
                session.setMeta(Map.of("eval_run", runId));
                sessionRepository.save(session);

                String traceId = UUID.randomUUID().toString().replace("-", "");
                TurnContracts.Result outcome;
                try {
                    outcome = runtime.run(new TurnContracts.Request(session.getId(),
                            item.getQuestion(), identity, snapshot, List.of(), "", traceId, null),
                            event -> { /* 跑批不需要流式事件 */ });
                } catch (Exception e) {
                    log.warn("评测用例执行失败: {}", item.getQuestion(), e);
                    continue;
                }

                results.add(saveResult(runId, item, outcome, traceId));
                run.setFinished(results.size());
                runRepository.save(run);
            }

            run.setMetrics(aggregate(results));
            run.setStatus("finished");
            run.setFinishedAt(Instant.now());
            runRepository.save(run);

        } finally {
            IdentityContext.clear();
        }
    }

    private EvalResultEntity saveResult(String runId, EvalCaseEntity item,
                                        TurnContracts.Result outcome, String traceId) {
        String actualType = outcome.plan() == null ? "" : outcome.plan().type();
        String actualTarget = outcome.plan() == null ? "" : outcome.plan().target();

        EvalResultEntity result = new EvalResultEntity();
        result.setRunId(runId);
        result.setCaseId(item.getId());
        result.setQuestion(item.getQuestion());
        result.setAnswer(outcome.answer());
        result.setExpectedRouteType(item.getExpectedRouteType());
        result.setExpectedTarget(item.getExpectedTarget());
        result.setActualRouteType(actualType);
        result.setActualTarget(actualTarget);
        result.setRouteCorrect(routeMatches(item, actualType, actualTarget));
        result.setToolSuccess(outcome.toolCalls() > 0 && outcome.toolFailures() == 0);
        result.setLatencyMs(outcome.latencyMs());
        result.setTotalTokens(outcome.promptTokens() + outcome.completionTokens());
        result.setTraceId(traceId);

        double[] score = judge(item, outcome);
        result.setLlmScore(Double.isNaN(score[0]) ? null : score[0]);
        result.setLlmComment(judgeComment(item, outcome));
        return resultRepository.save(result);
    }

    private boolean routeMatches(EvalCaseEntity item, String actualType, String actualTarget) {
        if (item.getExpectedRouteType().isBlank()) return true;
        if (!item.getExpectedRouteType().equals(actualType)) return false;
        if (item.getExpectedTarget().isBlank()) return true;
        if (item.getExpectedTarget().equals(actualTarget)) return true;
        // 容忍工具短名与全名的差异
        return lastSegment(item.getExpectedTarget()).equals(lastSegment(actualTarget));
    }

    /**
     * 评分。
     *
     * <p>关键口径：<b>审批中断与护栏阻断不算失败</b> —— 它们是设计预期，
     * 标记为「待人工抽检」。第一版把它们按 0 分处理，导致准确率被系统性低估。
     */
    private double[] judge(EvalCaseEntity item, TurnContracts.Result outcome) {
        if ("waiting_approval".equals(outcome.status()) || "blocked".equals(outcome.status())) {
            return new double[]{Double.NaN};
        }
        String answer = outcome.answer();
        if (answer == null || answer.isBlank()) return new double[]{0.0};

        if (llmRouter.available()) {
            LlmResult result = llmRouter.complete(JUDGE_SYSTEM,
                    "用户问题：%s\n参考答案：%s\n实际答复：%s".formatted(
                            item.getQuestion(),
                            item.getExpectedAnswer().isBlank() ? "（无）" : item.getExpectedAnswer(),
                            answer));
            Double parsed = parseScore(result.text());
            if (parsed != null) return new double[]{parsed};
        }

        if (item.getExpectedAnswer().isBlank()) return new double[]{Double.NaN};
        String[] keywords = item.getExpectedAnswer().split("[\\s,，、;；]+");
        long hit = 0;
        long total = 0;
        for (String keyword : keywords) {
            if (keyword.length() < 2) continue;
            total++;
            if (answer.contains(keyword)) hit++;
        }
        if (total == 0) return new double[]{Double.NaN};
        return new double[]{Math.round((double) hit / total * 100) / 100.0};
    }

    private String judgeComment(EvalCaseEntity item, TurnContracts.Result outcome) {
        if ("waiting_approval".equals(outcome.status())) {
            return "已按预期中断等待人工审批，待抽检确认审批信息是否完整";
        }
        if ("blocked".equals(outcome.status())) {
            return "被运行时护栏阻断（循环/越权），待抽检确认拦截是否正确";
        }
        if (item.getExpectedAnswer().isBlank()) return "未配置参考答案，待人工抽检";
        return llmRouter.available() ? "LLM 评判" : "规则评分：参考答案关键词覆盖率";
    }

    private Double parseScore(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = JSON_BLOCK.matcher(text);
        if (!matcher.find()) return null;
        try {
            JsonNode node = MAPPER.readTree(matcher.group());
            return node.path("score").asDouble(0);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> aggregate(List<EvalResultEntity> results) {
        if (results.isEmpty()) return Map.of();

        List<Double> scored = results.stream()
                .map(EvalResultEntity::getLlmScore).filter(java.util.Objects::nonNull).toList();
        List<EvalResultEntity> toolUsed = results.stream()
                .filter(r -> List.of("tool", "skill", "agent").contains(r.getActualRouteType()))
                .toList();

        Map<String, Long> distribution = new LinkedHashMap<>();
        results.forEach(r -> distribution.merge(
                r.getActualRouteType().isBlank() ? "unknown" : r.getActualRouteType(), 1L, Long::sum));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total", results.size());
        metrics.put("route_accuracy", round(
                results.stream().filter(EvalResultEntity::isRouteCorrect).count()
                        / (double) results.size()));
        metrics.put("tool_success_rate", toolUsed.isEmpty() ? null : round(
                toolUsed.stream().filter(EvalResultEntity::isToolSuccess).count()
                        / (double) toolUsed.size()));
        metrics.put("avg_score", scored.isEmpty() ? null : round(
                scored.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        metrics.put("pass_rate", scored.isEmpty() ? null : round(
                scored.stream().filter(s -> s >= 0.7).count() / (double) scored.size()));
        metrics.put("avg_latency_ms", (int) results.stream()
                .mapToLong(EvalResultEntity::getLatencyMs).average().orElse(0));
        metrics.put("total_tokens", results.stream()
                .mapToInt(EvalResultEntity::getTotalTokens).sum());
        metrics.put("route_distribution", distribution.entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "count", e.getValue())).toList());
        return metrics;
    }

    // ---------------------------------------------------------------- 查询与打标

    @RequiresScope("eval:read")
    public List<EvalRunDto> listRuns(String datasetId) {
        PageRequest page = PageRequest.of(0, 50);
        List<EvalRunEntity> runs = datasetId == null || datasetId.isBlank()
                ? runRepository.findAllByOrderByCreatedAtDesc(page)
                : runRepository.findByDatasetIdOrderByCreatedAtDesc(datasetId, page);
        return runs.stream().map(EvalRunDto::from).toList();
    }

    @RequiresScope("eval:read")
    public EvalRunDto getRun(String runId) {
        return EvalRunDto.from(runRepository.findById(runId)
                .orElseThrow(() -> BizException.notFound("跑批")));
    }

    @RequiresScope("eval:read")
    public List<EvalResultDto> listResults(String runId, boolean onlyFailed) {
        return resultRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(r -> !onlyFailed || !r.isRouteCorrect()
                        || (r.getLlmScore() != null && r.getLlmScore() < 0.7))
                .map(EvalResultDto::from)
                .toList();
    }

    @RequiresScope("eval:write")
    @Transactional
    public EvalResultDto label(String resultId, HumanLabelRequest request) {
        EvalResultEntity result = resultRepository.findById(resultId)
                .orElseThrow(() -> BizException.notFound("结果"));
        result.setHumanLabel(request.humanLabel());
        result.setHumanComment(request.humanComment() == null ? "" : request.humanComment());
        resultRepository.save(result);
        return EvalResultDto.from(result);
    }

    private EvalDatasetEntity requireDataset(String datasetId) {
        return datasetRepository.findById(datasetId)
                .orElseThrow(() -> BizException.notFound("数据集"));
    }

    private double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private String lastSegment(String value) {
        if (value == null) return "";
        int index = value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
    }

    private String orEmpty(String value) { return value == null ? "" : value; }

    private String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
