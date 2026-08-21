package com.rsi.agent.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次 turn 的观测收集器。
 *
 * <p>可变状态集中在这里：Span 序列、Token 计量、模型与降级标记、工具计数。
 * 切面往里写，运行时也往里写，最后由 TraceService 一次性落库。
 */
public class TraceCollector {

    private final String traceId;
    private final long startedNanos = System.nanoTime();
    private final List<SpanRecord> spans = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger(0);

    private String modelUsed = "";
    private boolean modelFallback;
    private boolean degradedToRules;
    private int promptTokens;
    private int completionTokens;

    public TraceCollector(String traceId) {
        this.traceId = traceId;
    }

    public synchronized SpanRecord addSpan(String name, String type, Map<String, Object> input,
                                           Map<String, Object> output, String status,
                                           String error, long latencyMs) {
        SpanRecord span = new SpanRecord(sequence.incrementAndGet(), name, type,
                input == null ? Map.of() : input,
                output == null ? Map.of() : output,
                status, error == null ? "" : error, latencyMs);
        spans.add(span);
        return span;
    }

    public synchronized List<SpanRecord> spans() { return List.copyOf(spans); }

    public String traceId() { return traceId; }

    public long elapsedMs() { return (System.nanoTime() - startedNanos) / 1_000_000; }

    public String modelUsed() { return modelUsed; }
    public void modelUsed(String v) { if (v != null && !v.isBlank()) this.modelUsed = v; }

    public boolean modelFallback() { return modelFallback; }
    public void markFallback() { this.modelFallback = true; }

    public boolean degradedToRules() { return degradedToRules; }
    public void markDegraded() { this.degradedToRules = true; }

    public int promptTokens() { return promptTokens; }
    public int completionTokens() { return completionTokens; }

    public void addTokens(int prompt, int completion) {
        this.promptTokens += prompt;
        this.completionTokens += completion;
    }
}
