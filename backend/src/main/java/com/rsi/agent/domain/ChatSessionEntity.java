package com.rsi.agent.domain;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity extends BaseEntity {

    @Column(length = 255)
    private String title = "新会话";

    @Column(nullable = false, length = 32)
    private String userId;

    private String tenantId = "default";

    /** active | waiting_approval | closed */
    private String status = "active";

    /** 压缩后的历史摘要。 */
    @Column(length = 8000)
    private String summary = "";

    private int turnCount = 0;
    private long totalTokens = 0;

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> meta = Map.of();

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int v) { this.turnCount = v; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long v) { this.totalTokens = v; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> v) { this.meta = v; }
}
