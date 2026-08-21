package com.rsi.agent.domain;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Trace 下的单个执行片段：路由决策 / 工具调用 / 护栏拦截 / 审批中断各占一条。 */
@Entity
@Table(name = "trace_spans")
public class TraceSpanEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String traceId;

    private int sequence = 0;
    private String name = "";

    /** context | route | tool | skill | agent | llm | guard | approval */
    private String spanType = "";

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> input = Map.of();

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> output = Map.of();

    private String status = "success";

    @Column(length = 2000)
    private String error = "";

    private long latencyMs = 0;

    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public int getSequence() { return sequence; }
    public void setSequence(int v) { this.sequence = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getSpanType() { return spanType; }
    public void setSpanType(String v) { this.spanType = v; }
    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> v) { this.input = v; }
    public Map<String, Object> getOutput() { return output; }
    public void setOutput(Map<String, Object> v) { this.output = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long v) { this.latencyMs = v; }
}
