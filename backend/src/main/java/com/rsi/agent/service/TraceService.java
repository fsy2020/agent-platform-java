package com.rsi.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.agent.RoutePlan;
import com.rsi.agent.agent.TurnContracts;
import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.domain.TraceEntity;
import com.rsi.agent.domain.TraceSpanEntity;
import com.rsi.agent.dto.PlatformDtos.TraceDto;
import com.rsi.agent.mcp.CircuitBreaker;
import com.rsi.agent.observability.SpanRecord;
import com.rsi.agent.repository.*;
import com.rsi.agent.security.Identity;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** 全链路观测：Trace / Span 落库与平台指标聚合。 */
@Service
public class TraceService {

    private final TraceRepository traceRepository;
    private final TraceSpanRepository spanRepository;
    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final SkillRepository skillRepository;
    private final SubAgentRepository agentRepository;
    private final CircuitBreaker circuitBreaker;

    public TraceService(TraceRepository traceRepository, TraceSpanRepository spanRepository,
                        McpServerRepository serverRepository, McpToolRepository toolRepository,
                        SkillRepository skillRepository, SubAgentRepository agentRepository,
                        CircuitBreaker circuitBreaker) {
        this.traceRepository = traceRepository;
        this.spanRepository = spanRepository;
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.skillRepository = skillRepository;
        this.agentRepository = agentRepository;
        this.circuitBreaker = circuitBreaker;
    }

    /** 一次 turn 结束后落库：一条 Trace + N 条 Span。 */
    @Transactional
    public void persist(String traceId, String sessionId, int turnIndex, Identity identity,
                        String question, TurnContracts.Result result) {
        TraceEntity trace = new TraceEntity();
        trace.setId(traceId);
        trace.setSessionId(sessionId);
        trace.setTurnIndex(turnIndex);
        trace.setUserId(identity.id());
        trace.setTenantId(identity.tenantId());
        trace.setQuestion(question);
        trace.setAnswer(truncate(result.answer(), 15000));

        RoutePlan plan = result.plan();
        if (plan != null) {
            trace.setRouteType(plan.type());
            trace.setRouteTarget(plan.target());
            trace.setRouteConfidence(plan.confidence());
            trace.setRouteReason(truncate(plan.reason(), 1900));
        }

        trace.setVisibleToolCount(result.visibleToolCount());
        trace.setTotalToolCount(result.totalToolCount());
        trace.setPromptTokens(result.promptTokens());
        trace.setCompletionTokens(result.completionTokens());
        trace.setTotalTokens(result.promptTokens() + result.completionTokens());
        trace.setPromptChars(result.contextTokens() * 2);
        trace.setLatencyMs(result.latencyMs());
        trace.setIterations(result.iterations());
        trace.setToolCalls(result.toolCalls());
        trace.setToolFailures(result.toolFailures());
        trace.setContextCompressed(result.compressed());
        trace.setModelUsed(result.modelUsed());
        trace.setModelFallback(result.modelFallback());
        trace.setDegradedToRules(result.degraded());
        trace.setStatus(normalizeStatus(result.status()));
        traceRepository.save(trace);

        for (SpanRecord span : result.spans()) {
            TraceSpanEntity entity = new TraceSpanEntity();
            entity.setTraceId(traceId);
            entity.setSequence(span.sequence());
            entity.setName(span.name());
            entity.setSpanType(span.type());
            entity.setInput(span.input());
            entity.setOutput(span.output());
            entity.setStatus(span.status());
            entity.setError(truncate(span.error(), 1900));
            entity.setLatencyMs(span.latencyMs());
            spanRepository.save(entity);
        }
    }

    /** 审批中断在 Trace 里记为 interrupted —— 它不是失败，而是设计预期。 */
    private String normalizeStatus(String status) {
        return switch (status) {
            case "waiting_approval" -> "interrupted";
            case "blocked" -> "blocked";
            case "failed" -> "failed";
            default -> "success";
        };
    }

    @RequiresScope("trace:read")
    public List<TraceDto> search(String sessionId, String status, String routeType, int limit) {
        return traceRepository.search(blankToNull(sessionId), blankToNull(status),
                        blankToNull(routeType), PageRequest.of(0, Math.min(limit, 200)))
                .stream()
                .map(trace -> TraceDto.from(trace, List.of()))
                .toList();
    }

    @RequiresScope("trace:read")
    public TraceDto detail(String traceId) {
        TraceEntity trace = traceRepository.findById(traceId)
                .orElseThrow(() -> BizException.notFound("Trace"));
        return TraceDto.from(trace, spanRepository.findByTraceIdOrderBySequenceAsc(traceId));
    }

    // ---------------------------------------------------------------- 指标

    @RequiresScope("trace:read")
    public Map<String, Object> overview(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<TraceEntity> traces = traceRepository.findAll().stream()
                .filter(t -> t.getCreatedAt().isAfter(since))
                .toList();

        int total = traces.size();
        long success = traces.stream().filter(t -> "success".equals(t.getStatus())).count();
        long toolCalls = traces.stream().mapToLong(TraceEntity::getToolCalls).sum();
        long toolFailures = traces.stream().mapToLong(TraceEntity::getToolFailures).sum();
        long tokens = traces.stream().mapToLong(TraceEntity::getTotalTokens).sum();
        double avgLatency = traces.stream().mapToLong(TraceEntity::getLatencyMs).average().orElse(0);
        double avgVisible = traces.stream().mapToInt(TraceEntity::getVisibleToolCount).average().orElse(0);
        long compressed = traces.stream().filter(TraceEntity::isContextCompressed).count();

        Map<String, Long> routeCounts = new LinkedHashMap<>();
        traces.forEach(t -> routeCounts.merge(
                t.getRouteType() == null || t.getRouteType().isBlank() ? "unknown" : t.getRouteType(),
                1L, Long::sum));

        Map<String, long[]> daily = new LinkedHashMap<>();
        traces.forEach(t -> {
            String day = t.getCreatedAt().toString().substring(0, 10);
            long[] bucket = daily.computeIfAbsent(day, k -> new long[3]);
            bucket[0]++;
            bucket[1] += t.getTotalTokens();
            bucket[2] += t.getLatencyMs();
        });

        Map<String, Long> errors = new LinkedHashMap<>();
        traces.stream().filter(t -> !"success".equals(t.getStatus())).forEach(t -> errors.merge(
                t.getErrorType() == null || t.getErrorType().isBlank() ? "unknown" : t.getErrorType(),
                1L, Long::sum));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window_days", days);
        result.put("total_turns", total);
        result.put("success_rate", total == 0 ? 0.0 : round(success / (double) total));
        result.put("tool_calls", toolCalls);
        result.put("tool_success_rate",
                toolCalls == 0 ? 0.0 : round((toolCalls - toolFailures) / (double) toolCalls));
        result.put("total_tokens", tokens);
        result.put("avg_latency_ms", (int) avgLatency);
        result.put("avg_visible_tools", Math.round(avgVisible * 10) / 10.0);
        result.put("compression_rate", total == 0 ? 0.0 : round(compressed / (double) total));
        result.put("route_distribution", routeCounts.entrySet().stream()
                .map(e -> Map.of("route_type", e.getKey(), "count", e.getValue())).toList());
        result.put("daily", daily.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Map.of("date", e.getKey(), "turns", e.getValue()[0],
                        "tokens", e.getValue()[1],
                        "avg_latency_ms", e.getValue()[0] == 0 ? 0 : e.getValue()[2] / e.getValue()[0]))
                .toList());
        result.put("errors", errors.entrySet().stream()
                .map(e -> Map.of("error_type", e.getKey(), "count", e.getValue())).toList());
        result.put("assets", Map.of(
                "servers", serverRepository.count(),
                "tools", toolRepository.count(),
                "skills", skillRepository.count(),
                "agents", agentRepository.count()));
        result.put("circuits", circuitBreaker.snapshot());
        return result;
    }

    @RequiresScope("trace:read")
    public List<Map<String, Object>> toolMetrics() {
        Map<String, McpServerEntity> servers = new LinkedHashMap<>();
        serverRepository.findAll().forEach(s -> servers.put(s.getId(), s));

        List<Map<String, Object>> metrics = new ArrayList<>();
        for (McpToolEntity tool : toolRepository.findAllByOrderByCallCountDesc()) {
            McpServerEntity server = servers.get(tool.getServerId());
            if (server == null) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", server.getName() + "." + tool.getName());
            item.put("server", server.getDisplayName().isBlank()
                    ? server.getName() : server.getDisplayName());
            item.put("risk_level", tool.getRiskLevel());
            item.put("read_only", tool.isReadOnly());
            item.put("call_count", tool.getCallCount());
            item.put("success_count", tool.getSuccessCount());
            item.put("success_rate", tool.getCallCount() == 0 ? null
                    : round(tool.getSuccessCount() / (double) tool.getCallCount()));
            item.put("avg_latency_ms", tool.getCallCount() == 0 ? 0
                    : (int) (tool.getTotalLatencyMs() / tool.getCallCount()));
            metrics.add(item);
        }
        return metrics;
    }

    @RequiresScope("trace:read")
    public Map<String, Object> capabilityMetrics() {
        List<TraceEntity> traces = traceRepository.findAll();

        Map<String, Long> agents = new LinkedHashMap<>();
        Map<String, Long> skills = new LinkedHashMap<>();
        List<Double> confidences = new ArrayList<>();
        long lowConfidence = 0;

        for (TraceEntity trace : traces) {
            String type = trace.getRouteType();
            String target = trace.getRouteTarget() == null || trace.getRouteTarget().isBlank()
                    ? "-" : trace.getRouteTarget();
            if ("agent".equals(type)) agents.merge(target, 1L, Long::sum);
            if ("skill".equals(type)) skills.merge(target, 1L, Long::sum);

            if (List.of("tool", "skill", "agent").contains(type)) {
                confidences.add(trace.getRouteConfidence());
                if (trace.getRouteConfidence() < 0.7) lowConfidence++;
            }
        }

        double avgConfidence = confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Map.of(
                "agents", agents.entrySet().stream()
                        .map(e -> Map.of("name", e.getKey(), "count", e.getValue())).toList(),
                "skills", skills.entrySet().stream()
                        .map(e -> Map.of("name", e.getKey(), "count", e.getValue())).toList(),
                "avg_route_confidence", Math.round(avgConfidence * 1000) / 1000.0,
                "low_confidence_routes", lowConfidence);
    }

    private double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
