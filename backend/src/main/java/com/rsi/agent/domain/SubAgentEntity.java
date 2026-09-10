package com.rsi.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 子 Agent 注册模型 —— 业务方动态创建，运行时按身份/权限/意图裁剪暴露。
 *
 * <p>两种形态由 {@link #transport} 区分：
 * <ul>
 *   <li>{@code local} —— 注册的只是配置（prompt + 工具白名单），ReAct 循环跑在本进程</li>
 *   <li>{@code a2a} —— 注册的是外部部门自建服务的地址，走 A2A 协议远程调用；
 *       本模型的大部分字段可由 Agent Card 自动填充</li>
 * </ul>
 */
@Entity
@Table(name = "sub_agents")
public class SubAgentEntity extends BaseEntity {

    /**
     * 列宽上限。写入前的裁剪逻辑（见 {@code AgentCardService#applyCard}）必须与建表宽度
     * 一致，拆成两处字面量迟早对不上，对不上的那一次就是一个 500。因此这里导出常量，
     * 下方的 {@code @Column(length = ...)} 与裁剪逻辑共用同一份。
     *
     * <p>集合字段以 JSON 文本落库，上限约束的是<b>序列化结果</b>的字符数，不是元素个数。
     */
    public static final int TEXT_LENGTH = 255;
    public static final int DESCRIPTION_LENGTH = 2000;
    public static final int POSITIVE_EXAMPLES_LENGTH = 2000;
    public static final int TAGS_LENGTH = 1000;
    public static final int REMOTE_SKILLS_LENGTH = 4000;

    @Column(unique = true, nullable = false, length = 64)
    private String name;

    @Column(length = TEXT_LENGTH)
    private String displayName = "";

    /** 路由依据：能力范围 + 输入输出规范。注册时强制校验长度。 */
    @Column(length = DESCRIPTION_LENGTH)
    private String description = "";

    private String businessDomain = "general";

    @Column(length = 4000)
    private String systemPrompt = "";

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> toolAllowlist = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 1000)
    private List<String> skillNames = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 1000)
    private List<String> requiredScopes = List.of();

    /** description 必须含正样例，否则路由误判率显著升高。 */
    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = POSITIVE_EXAMPLES_LENGTH)
    private List<String> positiveExamples = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> negativeExamples = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> usageExamples = List.of();

    private String owner = "";

    @Column(length = TEXT_LENGTH)
    private String version = "1.0.0";

    private String riskLevel = "medium";
    private String status = "published";
    private boolean enabled = true;
    private String tenantId = "default";
    private String model = "";

    /** 单次委派内允许的最大工具调用轮数。 */
    private int maxIterations = 6;

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = TAGS_LENGTH)
    private List<String> tags = List.of();

    private long callCount = 0;
    private long successCount = 0;

    // ---------------------------------------------------------- 远程接入（a2a）

    /** local：平台内配置驱动；a2a：外部部门自建服务，走 A2A 协议远程调用。 */
    private String transport = "local";

    /** A2A JSON-RPC 端点，取自 Agent Card 的 url 字段。 */
    @Column(length = TEXT_LENGTH)
    private String endpoint = "";

    /** 拉取 Agent Card 的来源地址，用于后续刷新。 */
    @Column(length = TEXT_LENGTH)
    private String cardUrl = "";

    /** 远端声明的 A2A 协议版本。仅记录并在不一致时告警，不做强校验。 */
    @Column(length = TEXT_LENGTH)
    private String protocolVersion = "";

    /** 远端是否支持 message/stream —— 决定走流式还是阻塞 + 轮询。 */
    private boolean streaming = false;

    private String authType = "none";

    /** 服务间静态凭证。<b>含密文，禁止出现在任何 DTO 中。</b> */
    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> authConfig = Map.of();

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 1000)
    private Map<String, Object> headers = Map.of();

    /** Agent Card 声明的 skills 原文，供审计比对与前端展示。 */
    @Convert(converter = JsonAttributeConverter.MapListConverter.class)
    @Column(length = REMOTE_SKILLS_LENGTH)
    private List<Map<String, Object>> remoteSkills = List.of();

    private Instant cardFetchedAt;

    private String healthStatus = "unknown";

    @Column(length = 1000)
    private String lastError = "";

    /** 是否为远程 Agent —— 决定工具白名单是强制边界还是仅申报信息。 */
    public boolean isRemote() {
        return transport != null && !transport.isBlank() && !"local".equals(transport);
    }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getBusinessDomain() { return businessDomain; }
    public void setBusinessDomain(String v) { this.businessDomain = v; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public List<String> getToolAllowlist() { return toolAllowlist; }
    public void setToolAllowlist(List<String> v) { this.toolAllowlist = v; }
    public List<String> getSkillNames() { return skillNames; }
    public void setSkillNames(List<String> v) { this.skillNames = v; }
    public List<String> getRequiredScopes() { return requiredScopes; }
    public void setRequiredScopes(List<String> v) { this.requiredScopes = v; }
    public List<String> getPositiveExamples() { return positiveExamples; }
    public void setPositiveExamples(List<String> v) { this.positiveExamples = v; }
    public List<String> getNegativeExamples() { return negativeExamples; }
    public void setNegativeExamples(List<String> v) { this.negativeExamples = v; }
    public List<String> getUsageExamples() { return usageExamples; }
    public void setUsageExamples(List<String> v) { this.usageExamples = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int v) { this.maxIterations = v; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> v) { this.tags = v; }
    public long getCallCount() { return callCount; }
    public void setCallCount(long v) { this.callCount = v; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long v) { this.successCount = v; }
    public String getTransport() { return transport; }
    public void setTransport(String v) { this.transport = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getCardUrl() { return cardUrl; }
    public void setCardUrl(String v) { this.cardUrl = v; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String v) { this.protocolVersion = v; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean v) { this.streaming = v; }
    public String getAuthType() { return authType; }
    public void setAuthType(String v) { this.authType = v; }
    public Map<String, Object> getAuthConfig() { return authConfig; }
    public void setAuthConfig(Map<String, Object> v) { this.authConfig = v; }
    public Map<String, Object> getHeaders() { return headers; }
    public void setHeaders(Map<String, Object> v) { this.headers = v; }
    public List<Map<String, Object>> getRemoteSkills() { return remoteSkills; }
    public void setRemoteSkills(List<Map<String, Object>> v) { this.remoteSkills = v; }
    public Instant getCardFetchedAt() { return cardFetchedAt; }
    public void setCardFetchedAt(Instant v) { this.cardFetchedAt = v; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String v) { this.healthStatus = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
}
