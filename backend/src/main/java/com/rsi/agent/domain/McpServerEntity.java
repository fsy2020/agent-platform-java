package com.rsi.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** MCP Server 注册项：内部 REST API 经 SDK 标准化封装后在此登记。 */
@Entity
@Table(name = "mcp_servers")
public class McpServerEntity extends BaseEntity {

    @Column(unique = true, nullable = false, length = 64)
    private String name;

    private String displayName = "";

    @Column(length = 2000)
    private String description = "";

    private String businessDomain = "general";

    /** builtin | stdio | streamable_http | sse */
    private String transport = "builtin";

    private String endpoint = "";
    private String command = "";

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> args = List.of();

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> env = Map.of();

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> headers = Map.of();

    /** none | bearer | api_key | oauth2 —— 鉴权由网关统一注入，业务侧不感知。 */
    private String authType = "none";

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> authConfig = Map.of();

    private String owner = "";

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 1000)
    private List<String> tags = List.of();

    private boolean enabled = true;

    /** healthy | degraded | down | unknown */
    private String healthStatus = "unknown";
    private Instant lastHealthAt;

    @Column(length = 2000)
    private String lastError = "";

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getBusinessDomain() { return businessDomain; }
    public void setBusinessDomain(String v) { this.businessDomain = v; }
    public String getTransport() { return transport; }
    public void setTransport(String v) { this.transport = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getCommand() { return command; }
    public void setCommand(String v) { this.command = v; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> v) { this.args = v; }
    public Map<String, Object> getEnv() { return env; }
    public void setEnv(Map<String, Object> v) { this.env = v; }
    public Map<String, Object> getHeaders() { return headers; }
    public void setHeaders(Map<String, Object> v) { this.headers = v; }
    public String getAuthType() { return authType; }
    public void setAuthType(String v) { this.authType = v; }
    public Map<String, Object> getAuthConfig() { return authConfig; }
    public void setAuthConfig(Map<String, Object> v) { this.authConfig = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> v) { this.tags = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String v) { this.healthStatus = v; }
    public Instant getLastHealthAt() { return lastHealthAt; }
    public void setLastHealthAt(Instant v) { this.lastHealthAt = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
}
