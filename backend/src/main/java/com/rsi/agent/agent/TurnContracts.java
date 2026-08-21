package com.rsi.agent.agent;

import java.util.List;
import java.util.Map;

import com.rsi.agent.observability.SpanRecord;
import com.rsi.agent.security.Identity;

/** 一次 turn 的输入与输出契约。 */
public final class TurnContracts {

    private TurnContracts() {}

    /** 审批放行后的恢复上下文：直接从执行节点续跑，不重新路由。 */
    public record Resume(RoutePlan plan, String signature, String decision, String comment) {
        public boolean approved() { return "approved".equals(decision); }
    }

    public record Request(
            String sessionId,
            String question,
            Identity identity,
            CapabilitySnapshot snapshot,
            List<ContextAssembler.Turn> history,
            String summary,
            String traceId,
            Resume resume
    ) {
        public boolean isResume() { return resume != null; }
    }

    /** 一次 turn 的执行结果，同时喂给 SSE done 事件与 Trace 落库。 */
    public record Result(
            String status,
            String answer,
            RoutePlan plan,
            List<String> evidence,
            int iterations,
            int toolCalls,
            int toolFailures,
            Map<String, Object> pendingApproval,
            String summary,
            int contextTokens,
            boolean compressed,
            int visibleToolCount,
            int totalToolCount,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            String modelUsed,
            boolean modelFallback,
            boolean degraded,
            Map<String, Object> guardSnapshot,
            /** 本轮采集到的 Span，随结果带出 —— 运行时结束后 ThreadLocal 已清理。 */
            List<SpanRecord> spans
    ) {
        /** 转成前端 done 事件的载荷（snake_case 由 Jackson 全局策略处理）。 */
        public Map<String, Object> toPayload() {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("status", status);
            payload.put("answer", answer == null ? "" : answer);
            payload.put("route", plan == null ? Map.of() : Map.of(
                    "route_type", plan.type(),
                    "target", plan.target(),
                    "confidence", plan.confidence(),
                    "reason", plan.reason()));
            payload.put("evidence", evidence);
            payload.put("iterations", iterations);
            payload.put("tool_calls", toolCalls);
            payload.put("tool_failures", toolFailures);
            payload.put("pending_approval", pendingApproval);
            payload.put("summary", summary == null ? "" : summary);
            payload.put("context_tokens", contextTokens);
            payload.put("compressed", compressed);
            payload.put("visible_tool_count", visibleToolCount);
            payload.put("total_tool_count", totalToolCount);
            payload.put("latency_ms", latencyMs);
            payload.put("prompt_tokens", promptTokens);
            payload.put("completion_tokens", completionTokens);
            payload.put("model_used", modelUsed);
            payload.put("model_fallback", modelFallback);
            payload.put("degraded", degraded);
            payload.put("guard", guardSnapshot);
            return payload;
        }
    }
}
