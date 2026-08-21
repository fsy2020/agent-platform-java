package com.rsi.agent.domain;

import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 子 Agent 注册模型 —— 业务方动态创建，运行时按身份/权限/意图裁剪暴露。 */
@Entity
@Table(name = "sub_agents")
public class SubAgentEntity extends BaseEntity {

    @Column(unique = true, nullable = false, length = 64)
    private String name;

    private String displayName = "";

    /** 路由依据：能力范围 + 输入输出规范。注册时强制校验长度。 */
    @Column(length = 2000)
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
    @Column(length = 2000)
    private List<String> positiveExamples = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> negativeExamples = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> usageExamples = List.of();

    private String owner = "";
    private String version = "1.0.0";
    private String riskLevel = "medium";
    private String status = "published";
    private boolean enabled = true;
    private String tenantId = "default";
    private String model = "";

    /** 单次委派内允许的最大工具调用轮数。 */
    private int maxIterations = 6;

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 1000)
    private List<String> tags = List.of();

    private long callCount = 0;
    private long successCount = 0;

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
}
