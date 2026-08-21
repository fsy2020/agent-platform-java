package com.rsi.agent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.rsi.agent.agent.CapabilitySnapshot.ToolEntry;
import com.rsi.agent.agent.guard.AnswerVerifier;
import com.rsi.agent.agent.guard.LoopGuard;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.ApprovalEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.llm.LlmResult;
import com.rsi.agent.llm.LlmRouter;
import com.rsi.agent.llm.RuleEngine;
import com.rsi.agent.mcp.McpGateway;
import com.rsi.agent.mcp.ToolResult;
import com.rsi.agent.observability.TraceCollector;
import com.rsi.agent.observability.TraceContext;
import com.rsi.agent.repository.ApprovalRepository;
import com.rsi.agent.repository.McpToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent Runtime —— 融合式编排。
 *
 * <p>主循环是 ReAct 风格：简单任务直接走工具闭环；复杂任务才 delegate 给子 Agent。
 * 低成本任务不引入额外编排开销，高复杂任务保留拆解、隔离与预算控制。
 *
 * <pre>
 *   prepare ──> route ──> execute ──> route（带着新证据重新决策）
 *                 │          ├──> finalize（证据够了 / 达上限 / 被护栏阻断）
 *                 │          └──> 挂起（等待人工审批）
 *                 └──> finalize（answer / clarify 无需执行）
 * </pre>
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final CapabilityRegistry registry;
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final RuleEngine ruleEngine;
    private final McpGateway gateway;
    private final McpToolRepository toolRepository;
    private final ApprovalRepository approvalRepository;
    private final AgentProperties.Runtime config;

    public AgentRuntime(CapabilityRegistry registry, ContextAssembler contextAssembler,
                        LlmRouter llmRouter, RuleEngine ruleEngine, McpGateway gateway,
                        McpToolRepository toolRepository, ApprovalRepository approvalRepository,
                        AgentProperties properties) {
        this.registry = registry;
        this.contextAssembler = contextAssembler;
        this.llmRouter = llmRouter;
        this.ruleEngine = ruleEngine;
        this.gateway = gateway;
        this.toolRepository = toolRepository;
        this.approvalRepository = approvalRepository;
        this.config = properties.runtime();
    }

    /** 一次 turn 的可变状态。集中在一个对象里，避免十几个参数在方法间传递。 */
    private static final class State {
        final List<String> evidence = new ArrayList<>();
        final Map<String, String> approvals = new HashMap<>();
        final List<String> routeHistory = new ArrayList<>();
        LoopGuard guard;
        int iterations;
        int toolCalls;
        int toolFailures;
        boolean hadSuccessfulWrite;
        boolean degraded;
        String finalAnswer;
        String status = "running";
        Map<String, Object> pendingApproval;
    }

    // ------------------------------------------------------------------ 入口

    public TurnContracts.Result run(TurnContracts.Request request, Consumer<AgentEvent> emit) {
        TraceCollector collector = new TraceCollector(request.traceId());
        TraceContext.set(collector);

        State state = new State();
        state.guard = new LoopGuard(config.loopRepeatThreshold());
        state.degraded = !llmRouter.available();

        try {
            // ---------- prepare：能力裁剪 + 上下文装配 ----------
            RouteCandidates candidates = registry.supervisorCandidates(request.snapshot());
            ContextAssembler.Assembled context = contextAssembler.assemble(
                    request.identity(), candidates.describe(40), request.summary(),
                    request.history(), state.evidence);

            emit.accept(AgentEvent.of("context",
                    "compressed", context.compressed(),
                    "context_tokens", context.tokens(),
                    "visible_tools", candidates.tools().size(),
                    "visible_skills", candidates.skills().size(),
                    "visible_agents", candidates.agents().size(),
                    "total_tools", request.snapshot().totalToolCount()));

            collector.addSpan("上下文装配", "context",
                    Map.of("turns", request.history().size()),
                    Map.of("tokens", context.tokens(), "compressed", context.compressed(),
                            "visible_tools", candidates.visibleCount()),
                    "success", "", 0);

            RoutePlan plan = null;

            if (request.isResume()) {
                // ---------- resume：从执行节点直接续跑，不重新路由 ----------
                plan = request.resume().plan();
                state.approvals.put(request.resume().signature(), request.resume().decision());
                execute(plan, request, state, emit);
            } else {
                // ---------- route ⇄ execute 主循环 ----------
                while (state.iterations < config.maxIterations()) {
                    plan = route(request, context, candidates, state, emit);
                    state.iterations++;
                    state.routeHistory.add(plan.type() + ":" + plan.target());

                    if (!plan.needsExecution()) break;

                    execute(plan, request, state, emit);

                    if ("waiting_approval".equals(state.status)) {
                        return finish(state, plan, context, candidates, request, collector);
                    }
                    if (state.finalAnswer != null || "blocked".equals(state.status)) break;

                    // 规则引擎无法判断证据是否充分：取得证据后立即收敛，避免空转
                    if (state.degraded && !state.evidence.isEmpty()) break;

                    // 同一路径连续两次被选中且无新进展 → 收敛，交给汇总节点
                    int size = state.routeHistory.size();
                    if (size >= 2 && state.routeHistory.get(size - 1).equals(state.routeHistory.get(size - 2))) {
                        break;
                    }
                }
            }

            // ---------- finalize ----------
            String answer = finalize(plan, state, request, emit, collector);
            state.finalAnswer = answer;
            return finish(state, plan, context, candidates, request, collector);

        } catch (Exception e) {
            log.error("Agent 执行失败", e);
            emit.accept(AgentEvent.of("error", "message", e.getClass().getSimpleName() + ": " + e.getMessage()));
            state.status = "failed";
            state.finalAnswer = "执行过程中发生异常：" + e.getMessage();
            return finish(state, null, null, null, request, collector);
        } finally {
            TraceContext.clear();
        }
    }

    // ------------------------------------------------------------------ 路由

    private RoutePlan route(TurnContracts.Request request, ContextAssembler.Assembled context,
                            RouteCandidates candidates, State state, Consumer<AgentEvent> emit) {
        emit.accept(AgentEvent.of("status", "phase", "routing",
                "message", "Supervisor 正在识别意图并选择执行路径…"));

        String userPrompt = buildRoutePrompt(request.question(), state);
        LlmResult result = llmRouter.route(context.text(), userPrompt, request.question(),
                candidates, true);

        RoutePlan plan = result.plan() != null ? result.plan()
                : ruleEngine.route(request.question(), candidates, true);

        TraceCollector collector = TraceContext.peek();
        if (collector != null) {
            collector.modelUsed(result.model());
            if (result.fallbackUsed()) collector.markFallback();
            if (result.degraded()) collector.markDegraded();
            collector.addTokens(result.promptTokens(), result.completionTokens());
            collector.addSpan("路由决策 → " + plan.type(), "route",
                    Map.of("question", request.question(),
                            "candidates", Map.of("agents", candidates.agents().size(),
                                    "skills", candidates.skills().size(),
                                    "tools", candidates.tools().size())),
                    Map.of("route_type", plan.type(), "target", plan.target(),
                            "confidence", plan.confidence(), "reason", plan.reason()),
                    "success", "", 0);
        }
        state.degraded = state.degraded || result.degraded();

        boolean lowConfidence = plan.confidence() < config.routeConfidenceThreshold();
        if (lowConfidence && plan.needsExecution()) {
            emit.accept(AgentEvent.of("guard", "kind", "low_confidence",
                    "message", "路由置信度 %.2f 低于阈值 %.2f，已记录用于 description 与 few-shot 优化。"
                            .formatted(plan.confidence(), config.routeConfidenceThreshold())));
        }

        emit.accept(AgentEvent.of("route",
                "route_type", plan.type(), "target", plan.target(),
                "confidence", plan.confidence(), "reason", plan.reason(),
                "degraded", result.degraded(), "model", result.model(),
                "low_confidence", lowConfidence));

        return plan;
    }

    private String buildRoutePrompt(String question, State state) {
        StringBuilder sb = new StringBuilder("用户当前请求：").append(question).append('\n');
        if (!state.evidence.isEmpty()) {
            sb.append("\n本轮已获取的工具执行证据：\n");
            for (int i = 0; i < state.evidence.size(); i++) {
                sb.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
            }
            sb.append("\n如果证据已足够回答用户，请选择 answer；否则继续选择下一步执行路径。\n");
        }
        if (state.iterations >= config.maxIterations() - 2) {
            sb.append("\n注意：已执行 ").append(state.iterations)
                    .append(" 轮，接近上限，请尽快收敛为 answer 或 clarify。\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 执行分发

    /**
     * 执行分发。
     *
     * <p>switch 对密封接口做<b>模式匹配</b>，编译器保证穷尽 —— 将来新增一种
     * 执行路径而这里忘了处理，是编译错误而不是线上的静默 default 分支。
     */
    private void execute(RoutePlan plan, TurnContracts.Request request, State state,
                         Consumer<AgentEvent> emit) {
        switch (plan) {
            case RoutePlan.CallTool call -> executeTool(call, request, state, emit);
            case RoutePlan.RunSkill skill -> executeSkill(skill, request, state, emit);
            case RoutePlan.Delegate delegate -> executeAgent(delegate, request, state, emit);
            case RoutePlan.Reply ignored -> { /* 无需执行 */ }
            case RoutePlan.Clarify ignored -> { /* 无需执行 */ }
        }
    }

    // ------------------------------------------------------------------ 工具

    private void executeTool(RoutePlan.CallTool call, TurnContracts.Request request,
                             State state, Consumer<AgentEvent> emit) {
        Optional<ToolEntry> found = request.snapshot().tool(call.tool());
        if (found.isEmpty()) {
            String message = "工具 `%s` 不存在或当前身份无权访问。".formatted(call.tool());
            emit.accept(AgentEvent.of("guard", "kind", "tool_not_found", "message", message));
            state.evidence.add("[路由错误] " + message);
            return;
        }
        ToolEntry entry = found.get();
        Map<String, Object> args = new LinkedHashMap<>(call.arguments());

        // 参数校验：缺必填项转澄清，而不是带着空值硬调
        List<String> missing = AnswerVerifier.missingRequired(entry.tool().getInputSchema(), args);
        if (!missing.isEmpty()) {
            String message = "调用 `%s` 缺少必填参数：%s。请补充后我再执行。"
                    .formatted(entry.qualifiedName(), String.join(", ", missing));
            emit.accept(AgentEvent.of("guard", "kind", "missing_arguments", "message", message));
            state.finalAnswer = message;
            return;
        }

        if (guardBlocked(entry.qualifiedName(), args, state, emit)) return;

        if (requiresApproval(entry) && !passApproval(entry.qualifiedName(), args, state, emit,
                request, call, "tool", entry.qualifiedName(), entry)) {
            return;
        }

        invokeTool(entry, args, state, emit, Map.of());
    }

    /** 统一的工具调用：护栏记录、指标累加、事件推送都在这里，避免三处重复。 */
    private ToolResult invokeTool(ToolEntry entry, Map<String, Object> args, State state,
                                  Consumer<AgentEvent> emit, Map<String, Object> extraEventFields) {
        Map<String, Object> startEvent = new LinkedHashMap<>(extraEventFields);
        startEvent.put("tool", entry.qualifiedName());
        startEvent.put("args", args);
        startEvent.put("risk_level", entry.tool().getRiskLevel());
        emit.accept(AgentEvent.of("tool_start", startEvent));

        ToolResult result = gateway.call(entry.server(), entry.tool().getName(), args);
        recordToolMetrics(entry.tool(), result);
        state.guard.record(entry.qualifiedName(), args, result.success(), result.summary());
        state.toolCalls++;
        if (!result.success()) {
            state.toolFailures++;
        } else if (!entry.tool().isReadOnly()) {
            state.hadSuccessfulWrite = true;
        }

        Map<String, Object> endEvent = new LinkedHashMap<>(extraEventFields);
        endEvent.put("tool", entry.qualifiedName());
        endEvent.put("success", result.success());
        endEvent.put("latency_ms", result.latencyMs());
        endEvent.put("attempts", result.attempts());
        endEvent.put("error_type", result.errorType());
        endEvent.put("summary", result.summary(300));
        emit.accept(AgentEvent.of("tool_end", endEvent));

        TraceCollector collector = TraceContext.peek();
        if (collector != null) {
            collector.addSpan("工具 " + entry.qualifiedName(), "tool",
                    Map.of("args", args),
                    Map.of("success", result.success(), "summary", result.summary(300)),
                    result.success() ? "success" : "failed", result.errorMessage(), result.latencyMs());
        }
        return result;
    }

    // ------------------------------------------------------------------ Skill

    private void executeSkill(RoutePlan.RunSkill run, TurnContracts.Request request,
                              State state, Consumer<AgentEvent> emit) {
        Optional<SkillEntity> found = request.snapshot().skill(run.skill());
        if (found.isEmpty()) {
            String message = "Skill `%s` 不存在或未发布。".formatted(run.skill());
            emit.accept(AgentEvent.of("guard", "kind", "skill_not_found", "message", message));
            state.evidence.add("[路由错误] " + message);
            return;
        }
        SkillEntity skill = found.get();

        Map<String, Object> inputs = new LinkedHashMap<>(run.inputs());
        if (inputs.isEmpty()) {
            inputs.putAll(ruleEngine.extractArguments(request.question(),
                    CapabilityRegistry.requiredOf(skill.getInputSchema()),
                    CapabilityRegistry.propertiesOf(skill.getInputSchema())));
        }

        List<String> missing = AnswerVerifier.missingRequired(skill.getInputSchema(), inputs);
        if (!missing.isEmpty()) {
            String message = "执行 Skill `%s` 缺少参数：%s。请补充。".formatted(
                    skill.getDisplayName().isBlank() ? skill.getName() : skill.getDisplayName(),
                    String.join(", ", missing));
            emit.accept(AgentEvent.of("guard", "kind", "missing_arguments", "message", message));
            state.finalAnswer = message;
            return;
        }

        if ("high".equals(skill.getRiskLevel())
                && !passApproval("skill:" + skill.getName(), inputs, state, emit, request, run,
                        "skill", skill.getName(), null)) {
            return;
        }

        emit.accept(AgentEvent.of("skill_start",
                "skill", skill.getName(), "display_name", skill.getDisplayName(),
                "steps", skill.getSteps().size(), "inputs", inputs));

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", inputs);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        scope.put("steps", stepResults);

        int failures = 0;
        int index = 0;
        for (Map<String, Object> step : skill.getSteps()) {
            index++;
            String stepId = step.get("id") != null ? String.valueOf(step.get("id")) : "step" + index;
            String type = step.get("type") != null ? String.valueOf(step.get("type")) : "tool";
            boolean optional = Boolean.TRUE.equals(step.get("optional"));

            if ("llm".equals(type)) {
                String prompt = SkillTemplates.render(String.valueOf(step.getOrDefault("prompt", "")), scope);
                LlmResult result = llmRouter.complete(
                        "你是企业业务流程助手，基于给定数据完成该步骤。", prompt);
                String text = result.text() == null || result.text().isBlank()
                        ? "（模型不可用，该步骤已跳过）" : result.text();
                stepResults.put(stepId, Map.of("success", true, "data", text));
                state.evidence.add("[%s/%s] 分析结论：%s".formatted(skill.getName(), stepId,
                        text.length() > 300 ? text.substring(0, 300) : text));
                continue;
            }

            Optional<ToolEntry> entry = request.snapshot().tool(String.valueOf(step.get("tool")));
            if (entry.isEmpty()) {
                failures++;
                emit.accept(AgentEvent.of("skill_step", "skill", skill.getName(), "step", stepId,
                        "success", false, "message", "步骤工具不可用：" + step.get("tool")));
                if (optional) continue;
                break;
            }

            Map<String, Object> stepArgs = SkillTemplates.resolveStepArgs(step, scope);
            if (guardBlocked(entry.get().qualifiedName(), stepArgs, state, emit)) break;

            ToolResult result = invokeTool(entry.get(), stepArgs, state, emit,
                    Map.of("skill", skill.getName(), "step", stepId));

            stepResults.put(stepId, Map.of("success", result.success(),
                    "data", result.data() == null ? Map.of() : result.data()));
            state.evidence.add("[%s/%s] `%s` → %s".formatted(skill.getName(), stepId,
                    entry.get().qualifiedName(), result.summary(400)));

            if (!result.success()) {
                failures++;
                if (!optional) {
                    emit.accept(AgentEvent.of("skill_step", "skill", skill.getName(), "step", stepId,
                            "success", false,
                            "message", "步骤失败，终止后续步骤：" + result.errorMessage()));
                    break;
                }
            }
        }

        emit.accept(AgentEvent.of("skill_end", "skill", skill.getName(), "failures", failures));

        if (!skill.getOutputTemplate().isBlank()) {
            String rendered = SkillTemplates.render(skill.getOutputTemplate(), scope);
            if (!rendered.isBlank()) {
                state.evidence.add("[%s] 汇总：%s".formatted(skill.getName(), rendered));
            }
        }
    }

    // ------------------------------------------------------------------ 子 Agent

    private void executeAgent(RoutePlan.Delegate delegate, TurnContracts.Request request,
                              State state, Consumer<AgentEvent> emit) {
        Optional<SubAgentEntity> found = request.snapshot().agent(delegate.agent());
        if (found.isEmpty()) {
            String message = "子 Agent `%s` 不存在或未发布。".formatted(delegate.agent());
            emit.accept(AgentEvent.of("guard", "kind", "agent_not_found", "message", message));
            state.evidence.add("[路由错误] " + message);
            return;
        }
        SubAgentEntity agent = found.get();
        List<ToolEntry> tools = registry.agentTools(request.snapshot(), agent);

        emit.accept(AgentEvent.of("agent_start",
                "agent", agent.getName(), "display_name", agent.getDisplayName(),
                "tools", tools.stream().map(ToolEntry::qualifiedName).toList(),
                "budget", agent.getMaxIterations()));

        // 子 Agent 的候选集只含自己的工具：越界与二次分发在这里被结构性杜绝
        RouteCandidates candidates = registry.agentCandidates(tools);
        CapabilitySnapshot subSnapshot = CapabilitySnapshot.ofTools(
                tools, request.snapshot().totalToolCount());

        String system = Prompts.subagentSystem(agent.getSystemPrompt())
                + "\n\n" + candidates.describe(25);

        List<String> subEvidence = new ArrayList<>();
        int budget = agent.getMaxIterations() <= 0 ? 6 : agent.getMaxIterations();

        for (int step = 1; step <= budget; step++) {
            StringBuilder userPrompt = new StringBuilder("待办任务：").append(request.question()).append('\n');
            if (!subEvidence.isEmpty()) {
                userPrompt.append("\n已获取证据：\n");
                subEvidence.forEach(item -> userPrompt.append("- ").append(item).append('\n'));
            }
            userPrompt.append("\n请决定下一步：调用工具（route_type=tool）、")
                    .append("已可作答（route_type=answer，把结论写进 message）或需要澄清（clarify）。");

            // 子 Agent 不再受置信度阈值二次拦截 —— 领域边界已由上层确定
            LlmResult result = llmRouter.route(system, userPrompt.toString(),
                    request.question(), candidates, false);
            RoutePlan plan = result.plan() != null ? result.plan()
                    : ruleEngine.route(request.question(), candidates, false);

            TraceCollector collector = TraceContext.peek();
            if (collector != null) {
                collector.addTokens(result.promptTokens(), result.completionTokens());
                if (result.degraded()) collector.markDegraded();
            }
            state.degraded = state.degraded || result.degraded();

            if (!(plan instanceof RoutePlan.CallTool call)) {
                String message = switch (plan) {
                    case RoutePlan.Reply reply -> reply.message();
                    case RoutePlan.Clarify clarify -> clarify.message();
                    default -> "子 Agent 未产出结论。";
                };
                subEvidence.add("[%s] 结论：%s".formatted(agent.getName(), message));
                break;
            }

            Optional<ToolEntry> entry = subSnapshot.tool(call.tool());
            if (entry.isEmpty()) {
                emit.accept(AgentEvent.of("guard", "kind", "out_of_scope_tool",
                        "message", "子 Agent %s 请求了 allowlist 之外的工具 %s，已拦截。"
                                .formatted(agent.getName(), call.tool())));
                subEvidence.add("[%s] 尝试使用领域外工具 `%s`，已被工具边界拦截。"
                        .formatted(agent.getName(), call.tool()));
                break;
            }

            Map<String, Object> args = new LinkedHashMap<>(call.arguments());
            if (args.isEmpty()) {
                Map<String, Object> schema = entry.get().tool().getInputSchema();
                args.putAll(ruleEngine.extractArguments(request.question(),
                        CapabilityRegistry.requiredOf(schema), CapabilityRegistry.propertiesOf(schema)));
            }

            List<String> missing = AnswerVerifier.missingRequired(entry.get().tool().getInputSchema(), args);
            if (!missing.isEmpty()) {
                subEvidence.add("[%s] 缺少参数 %s，无法调用 `%s`。".formatted(
                        agent.getName(), String.join(", ", missing), entry.get().qualifiedName()));
                break;
            }

            if (guardBlocked(entry.get().qualifiedName(), args, state, emit)) break;

            if (requiresApproval(entry.get())) {
                boolean allowed = passApproval(entry.get().qualifiedName(), args, state, emit,
                        request, delegate, "agent", agent.getName(), entry.get());
                if (!allowed) {
                    state.evidence.addAll(subEvidence);
                    return;
                }
            }

            ToolResult toolResult = invokeTool(entry.get(), args, state, emit,
                    Map.of("agent", agent.getName()));
            subEvidence.add("[%s] `%s` → %s".formatted(agent.getName(),
                    entry.get().qualifiedName(), toolResult.summary(400)));

            // 规则引擎模式下模型无法判断证据是否充分，成功取得结果后即收敛，避免空转
            if (state.degraded && toolResult.success()) break;
        }

        emit.accept(AgentEvent.of("agent_end", "agent", agent.getName(), "steps", subEvidence.size()));
        state.evidence.addAll(subEvidence);
    }

    // ------------------------------------------------------------------ 护栏与审批

    private boolean guardBlocked(String tool, Map<String, Object> args, State state,
                                 Consumer<AgentEvent> emit) {
        LoopGuard.Verdict verdict = state.guard.check(tool, args);
        if (!verdict.blocked()) return false;

        emit.accept(AgentEvent.of("guard", "kind", verdict.kind(), "message", verdict.message()));
        state.finalAnswer = verdict.message() + "\n\n" + verdict.hint();
        state.status = "blocked";

        TraceCollector collector = TraceContext.peek();
        if (collector != null) {
            collector.addSpan("循环护栏拦截", "guard", Map.of("tool", tool, "args", args),
                    Map.of("kind", verdict.kind()), "blocked", verdict.message(), 0);
        }
        return true;
    }

    private boolean requiresApproval(ToolEntry entry) {
        return "high".equals(entry.tool().getRiskLevel()) || !entry.tool().isReadOnly();
    }

    /**
     * Human-in-the-Loop 关卡。
     *
     * @return true 表示可以继续执行；false 表示已挂起等待审批或被拒绝
     */
    private boolean passApproval(String signatureKey, Map<String, Object> args, State state,
                                 Consumer<AgentEvent> emit, TurnContracts.Request request,
                                 RoutePlan plan, String executorType, String executorName,
                                 ToolEntry entry) {
        String signature = LoopGuard.signature(signatureKey, args);
        String decision = state.approvals.get(signature);

        if (decision == null) {
            Map<String, Object> approval = createApproval(request, plan, executorType, executorName,
                    entry, args, signature);
            state.pendingApproval = approval;
            state.status = "waiting_approval";
            emit.accept(AgentEvent.of("approval_required", approval));

            TraceCollector collector = TraceContext.peek();
            if (collector != null) {
                collector.addSpan("人工审批中断", "approval",
                        Map.of("tool", approval.get("tool_name"), "args", args),
                        Map.of("approval_id", approval.get("id")), "pending", "", 0);
            }
            return false;
        }

        if ("rejected".equals(decision)) {
            String message = "操作 `%s` 已被拒绝。我不会执行该写操作。".formatted(signatureKey)
                    + "你可以修改参数后重新发起，或改用只读方式查询。";
            emit.accept(AgentEvent.of("guard", "kind", "approval_rejected", "message", message));
            state.finalAnswer = message;
            return false;
        }
        return true;
    }

    private Map<String, Object> createApproval(TurnContracts.Request request, RoutePlan plan,
                                               String executorType, String executorName,
                                               ToolEntry entry, Map<String, Object> args,
                                               String signature) {
        String toolName = entry != null ? entry.qualifiedName() : executorName;
        String impact = entry != null
                ? "影响系统：%s；作用对象：%s".formatted(
                        entry.server().getDisplayName().isBlank()
                                ? entry.server().getName() : entry.server().getDisplayName(),
                        args.isEmpty() ? "无参数" : describeArgs(args))
                : "涉及多个下游系统的写操作";
        String reason = entry != null
                ? "`%s` 为写操作/高风险工具，执行后将变更下游业务系统数据。".formatted(toolName)
                : "`%s` 被标记为高风险。".formatted(executorName);

        ApprovalEntity approval = new ApprovalEntity();
        approval.setSessionId(request.sessionId());
        approval.setTraceId(request.traceId());
        approval.setRequestedBy(request.identity().id());
        approval.setExecutorType(executorType);
        approval.setExecutorName(executorName);
        approval.setToolName(toolName);
        approval.setToolArgs(args);
        approval.setRiskLevel("high");
        approval.setRiskReason(reason);
        approval.setImpactScope(impact);
        approval.setResumePayload(Map.of(
                "route_type", plan.type(),
                "target", plan.target(),
                "arguments", args,
                "confidence", plan.confidence(),
                "reason", plan.reason(),
                "signature", signature,
                "question", request.question()));
        approvalRepository.save(approval);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", approval.getId());
        payload.put("executor_type", executorType);
        payload.put("executor_name", executorName);
        payload.put("tool_name", toolName);
        payload.put("tool_args", args);
        payload.put("risk_level", "high");
        payload.put("risk_reason", reason);
        payload.put("impact_scope", impact);
        payload.put("signature", signature);
        return payload;
    }

    private String describeArgs(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (count++ >= 4) break;
            if (count > 1) sb.append(", ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 收敛

    private String finalize(RoutePlan plan, State state, TurnContracts.Request request,
                            Consumer<AgentEvent> emit, TraceCollector collector) {
        if (state.finalAnswer != null) {
            emit.accept(AgentEvent.of("answer", "text", state.finalAnswer, "streamed", false));
            return state.finalAnswer;
        }

        if (plan instanceof RoutePlan.Reply reply && state.evidence.isEmpty()) {
            emit.accept(AgentEvent.of("answer", "text", reply.message(), "streamed", false));
            return reply.message();
        }
        if (plan instanceof RoutePlan.Clarify clarify) {
            emit.accept(AgentEvent.of("answer", "text", clarify.message(), "streamed", false));
            return clarify.message();
        }

        emit.accept(AgentEvent.of("status", "phase", "summarizing", "message", "正在汇总执行结果…"));

        StringBuilder prompt = new StringBuilder("用户请求：").append(request.question())
                .append("\n\n工具执行证据（只能使用这些事实）：\n");
        if (state.evidence.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (int i = 0; i < state.evidence.size(); i++) {
                prompt.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
            }
        }
        prompt.append("\n请用中文给出最终答复。");

        LlmResult result = llmRouter.complete(Prompts.ANSWER_IDENTITY, prompt.toString());
        collector.addTokens(result.promptTokens(), result.completionTokens());

        String answer = result.text() == null || result.text().isBlank()
                ? fallbackAnswer(state) : result.text().trim();

        // 防幻觉：未真正完成写操作却声称已完成 → 纠正
        AnswerVerifier.Verdict verdict = AnswerVerifier.verify(answer, state.hadSuccessfulWrite,
                state.evidence);
        if (!verdict.ok()) {
            emit.accept(AgentEvent.of("guard", "kind", verdict.reason(),
                    "message", "检测到未经证据支撑的完成声明，已纠正答复。"));
            answer = verdict.corrected();
            emit.accept(AgentEvent.of("answer", "text", answer, "streamed", false, "corrected", true));
        } else {
            emit.accept(AgentEvent.of("answer", "text", answer, "streamed", false));
        }

        collector.addSpan("结果汇总", "llm",
                Map.of("evidence_count", state.evidence.size()),
                Map.of("answer_chars", answer.length(), "guard_corrected", !verdict.ok()),
                "success", "", 0);
        return answer;
    }

    /** LLM 不可用时的确定性答复：直接结构化呈现工具证据。 */
    private String fallbackAnswer(State state) {
        if (state.evidence.isEmpty()) {
            return "当前没有可用的执行结果。请补充更具体的信息（例如工号 E1001、工单号 INC-xxx、"
                    + "客户编号 C-xxxx），我再为你查询。";
        }
        StringBuilder sb = new StringBuilder("本轮的工具调用结果如下：\n\n");
        for (int i = 0; i < state.evidence.size(); i++) {
            sb.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
        }
        sb.append("\n> 当前未配置模型 API Key，以上为工具原始返回的结构化摘要（规则引擎模式）。");
        return sb.toString();
    }

    private TurnContracts.Result finish(State state, RoutePlan plan,
                                        ContextAssembler.Assembled context,
                                        RouteCandidates candidates, TurnContracts.Request request,
                                        TraceCollector collector) {
        String status = switch (state.status) {
            case "waiting_approval", "blocked", "failed" -> state.status;
            default -> "success";
        };

        return new TurnContracts.Result(
                status,
                state.finalAnswer == null ? "" : state.finalAnswer,
                plan,
                List.copyOf(state.evidence),
                state.iterations,
                state.toolCalls,
                state.toolFailures,
                state.pendingApproval,
                context == null ? request.summary() : context.summary(),
                context == null ? 0 : context.tokens(),
                context != null && context.compressed(),
                candidates == null ? 0 : candidates.visibleCount(),
                request.snapshot().totalToolCount(),
                collector.elapsedMs(),
                collector.promptTokens(),
                collector.completionTokens(),
                collector.modelUsed().isBlank()
                        ? (state.degraded ? "rule-engine" : "") : collector.modelUsed(),
                collector.modelFallback(),
                collector.degradedToRules() || state.degraded,
                state.guard.snapshot(),
                collector.spans());
    }

    private void recordToolMetrics(McpToolEntity tool, ToolResult result) {
        tool.setCallCount(tool.getCallCount() + 1);
        if (result.success()) tool.setSuccessCount(tool.getSuccessCount() + 1);
        tool.setTotalLatencyMs(tool.getTotalLatencyMs() + result.latencyMs());
        toolRepository.save(tool);
    }
}
