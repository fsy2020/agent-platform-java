package com.rsi.agent.domain;

import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 可复用能力目录：单个 MCP 工具或多工具编排形成的标准 SOP。 */
@Entity
@Table(name = "skills")
public class SkillEntity extends BaseEntity {

    @Column(unique = true, nullable = false, length = 64)
    private String name;

    private String displayName = "";

    @Column(length = 2000)
    private String description = "";

    private String businessDomain = "general";

    /** [{id, type: tool|llm, tool, args, input_map, prompt, optional, description}] */
    @Convert(converter = JsonAttributeConverter.MapListConverter.class)
    @Column(length = 8000)
    private List<Map<String, Object>> steps = List.of();

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> inputSchema = Map.of();

    @Column(length = 2000)
    private String outputTemplate = "";

    private String riskLevel = "low";

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 1000)
    private List<String> requiredScopes = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> examples = List.of();

    @Convert(converter = JsonAttributeConverter.StringListConverter.class)
    @Column(length = 2000)
    private List<String> negativeExamples = List.of();

    private String owner = "";
    private String version = "1.0.0";
    private String status = "published";
    private boolean enabled = true;

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
    public List<Map<String, Object>> getSteps() { return steps; }
    public void setSteps(List<Map<String, Object>> v) { this.steps = v; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> v) { this.inputSchema = v; }
    public String getOutputTemplate() { return outputTemplate; }
    public void setOutputTemplate(String v) { this.outputTemplate = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public List<String> getRequiredScopes() { return requiredScopes; }
    public void setRequiredScopes(List<String> v) { this.requiredScopes = v; }
    public List<String> getExamples() { return examples; }
    public void setExamples(List<String> v) { this.examples = v; }
    public List<String> getNegativeExamples() { return negativeExamples; }
    public void setNegativeExamples(List<String> v) { this.negativeExamples = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> v) { this.tags = v; }
    public long getCallCount() { return callCount; }
    public void setCallCount(long v) { this.callCount = v; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long v) { this.successCount = v; }
}
