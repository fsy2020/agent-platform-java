package com.rsi.agent.domain;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 高风险工具调用的人工审批单（Human-in-the-Loop）。 */
@Entity
@Table(name = "approvals")
public class ApprovalEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String sessionId;

    private String traceId = "";
    private String requestedBy = "";

    /** tool | skill | agent */
    private String executorType = "tool";
    private String executorName = "";
    private String toolName = "";

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> toolArgs = Map.of();

    private String riskLevel = "high";

    @Column(length = 2000)
    private String riskReason = "";

    @Column(length = 2000)
    private String impactScope = "";

    /** pending | approved | rejected | expired */
    private String status = "pending";

    private String decidedBy = "";

    @Column(length = 2000)
    private String decisionComment = "";

    private Instant decidedAt;

    /** resume 所需的上下文快照：路由决策 + 调用签名 + 原始问题。 */
    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> resumePayload = Map.of();

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public String getExecutorType() { return executorType; }
    public void setExecutorType(String v) { this.executorType = v; }
    public String getExecutorName() { return executorName; }
    public void setExecutorName(String v) { this.executorName = v; }
    public String getToolName() { return toolName; }
    public void setToolName(String v) { this.toolName = v; }
    public Map<String, Object> getToolArgs() { return toolArgs; }
    public void setToolArgs(Map<String, Object> v) { this.toolArgs = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public String getRiskReason() { return riskReason; }
    public void setRiskReason(String v) { this.riskReason = v; }
    public String getImpactScope() { return impactScope; }
    public void setImpactScope(String v) { this.impactScope = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String v) { this.decisionComment = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public Map<String, Object> getResumePayload() { return resumePayload; }
    public void setResumePayload(Map<String, Object> v) { this.resumePayload = v; }
}
