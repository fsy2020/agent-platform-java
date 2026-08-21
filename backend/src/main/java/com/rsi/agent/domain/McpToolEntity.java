package com.rsi.agent.domain;

import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** 从 MCP Server 发现的工具，含参数 schema、风险等级与权限 scope。 */
@Entity
@Table(name = "mcp_tools")
public class McpToolEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String serverId;

    /** 冗余一份 Server 名，避免每次拼全名都要 join。 */
    @Column(nullable = false, length = 64)
    private String serverName;

    @Column(nullable = false, length = 128)
    private String name;

    private String displayName = "";

    @Column(length = 2000)
    private String description = "";

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> inputSchema = Map.of();

    /** low | medium | high */
    private String riskLevel = "low";

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 1000)
    private List<String> requiredScopes = List.of();

    private boolean readOnly = true;
    private boolean enabled = true;

    private long callCount = 0;
    private long successCount = 0;
    private long totalLatencyMs = 0;

    @Transient
    public String getQualifiedName() { return serverName + "." + name; }

    public String getServerId() { return serverId; }
    public void setServerId(String v) { this.serverId = v; }
    public String getServerName() { return serverName; }
    public void setServerName(String v) { this.serverName = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> v) { this.inputSchema = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public List<String> getRequiredScopes() { return requiredScopes; }
    public void setRequiredScopes(List<String> v) { this.requiredScopes = v; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean v) { this.readOnly = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public long getCallCount() { return callCount; }
    public void setCallCount(long v) { this.callCount = v; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long v) { this.successCount = v; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public void setTotalLatencyMs(long v) { this.totalLatencyMs = v; }
}
