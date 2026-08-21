package com.rsi.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 一次 turn 的全链路记录：路由、成本、工具链路、恢复动作。 */
@Entity
@Table(name = "traces")
public class TraceEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String sessionId;

    private int turnIndex = 0;
    private String userId = "";
    private String tenantId = "default";

    @Column(length = 4000)
    private String question = "";

    @Column(length = 16000)
    private String answer = "";

    /** tool | skill | agent | answer | clarify */
    private String routeType = "";
    private String routeTarget = "";
    private double routeConfidence = 0;

    @Column(length = 2000)
    private String routeReason = "";

    /** 分层路由后的可见能力数 vs 平台工具总数。 */
    private int visibleToolCount = 0;
    private int totalToolCount = 0;

    private int promptChars = 0;
    private int promptTokens = 0;
    private int completionTokens = 0;
    private int totalTokens = 0;
    private int cacheHitTokens = 0;

    private long latencyMs = 0;
    private int iterations = 0;
    private int toolCalls = 0;
    private int toolFailures = 0;

    private boolean contextCompressed = false;
    private String modelUsed = "";
    private boolean modelFallback = false;
    private boolean degradedToRules = false;

    /** success | failed | interrupted | blocked */
    private String status = "success";
    private String errorType = "";

    @Column(length = 2000)
    private String errorMessage = "";

    private String recoveryAction = "";

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public int getTurnIndex() { return turnIndex; }
    public void setTurnIndex(int v) { this.turnIndex = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getQuestion() { return question; }
    public void setQuestion(String v) { this.question = v; }
    public String getAnswer() { return answer; }
    public void setAnswer(String v) { this.answer = v; }
    public String getRouteType() { return routeType; }
    public void setRouteType(String v) { this.routeType = v; }
    public String getRouteTarget() { return routeTarget; }
    public void setRouteTarget(String v) { this.routeTarget = v; }
    public double getRouteConfidence() { return routeConfidence; }
    public void setRouteConfidence(double v) { this.routeConfidence = v; }
    public String getRouteReason() { return routeReason; }
    public void setRouteReason(String v) { this.routeReason = v; }
    public int getVisibleToolCount() { return visibleToolCount; }
    public void setVisibleToolCount(int v) { this.visibleToolCount = v; }
    public int getTotalToolCount() { return totalToolCount; }
    public void setTotalToolCount(int v) { this.totalToolCount = v; }
    public int getPromptChars() { return promptChars; }
    public void setPromptChars(int v) { this.promptChars = v; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int v) { this.promptTokens = v; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int v) { this.completionTokens = v; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int v) { this.totalTokens = v; }
    public int getCacheHitTokens() { return cacheHitTokens; }
    public void setCacheHitTokens(int v) { this.cacheHitTokens = v; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long v) { this.latencyMs = v; }
    public int getIterations() { return iterations; }
    public void setIterations(int v) { this.iterations = v; }
    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int v) { this.toolCalls = v; }
    public int getToolFailures() { return toolFailures; }
    public void setToolFailures(int v) { this.toolFailures = v; }
    public boolean isContextCompressed() { return contextCompressed; }
    public void setContextCompressed(boolean v) { this.contextCompressed = v; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String v) { this.modelUsed = v; }
    public boolean isModelFallback() { return modelFallback; }
    public void setModelFallback(boolean v) { this.modelFallback = v; }
    public boolean isDegradedToRules() { return degradedToRules; }
    public void setDegradedToRules(boolean v) { this.degradedToRules = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { this.errorType = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public String getRecoveryAction() { return recoveryAction; }
    public void setRecoveryAction(String v) { this.recoveryAction = v; }
}
