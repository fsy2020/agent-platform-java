package com.rsi.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.agent.CapabilityRegistry;
import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.agent.SkillTemplates;
import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.dto.RegistryDtos.SkillDto;
import com.rsi.agent.dto.RegistryDtos.SkillSaveRequest;
import com.rsi.agent.mcp.McpGateway;
import com.rsi.agent.mcp.ToolResult;
import com.rsi.agent.repository.SkillRepository;
import com.rsi.agent.security.Identity;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/** Skill 注册中心：把单个工具或多工具编排沉淀为可复用 SOP。 */
@Service
public class SkillService {

    private final SkillRepository skillRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final McpGateway gateway;

    public SkillService(SkillRepository skillRepository, CapabilityRegistry capabilityRegistry,
                        McpGateway gateway) {
        this.skillRepository = skillRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.gateway = gateway;
    }

    @RequiresScope("skill:read")
    public List<SkillDto> list() {
        return skillRepository.findAllByOrderByCreatedAtAsc().stream().map(SkillDto::from).toList();
    }

    @RequiresScope("skill:read")
    public SkillDto get(String skillId) {
        return SkillDto.from(require(skillId));
    }

    @RequiresScope("skill:write")
    @Transactional
    public SkillDto create(SkillSaveRequest request, Identity identity) {
        if (skillRepository.existsByName(request.name())) {
            throw BizException.conflict("Skill 名称已存在：" + request.name());
        }
        validateSteps(request.steps(), identity);

        SkillEntity skill = new SkillEntity();
        skill.setName(request.name());
        apply(skill, request);
        if (skill.getOwner().isBlank()) skill.setOwner(identity.username());
        skillRepository.save(skill);
        return SkillDto.from(skill);
    }

    @RequiresScope("skill:write")
    @Transactional
    public SkillDto update(String skillId, SkillSaveRequest request, Identity identity) {
        SkillEntity skill = require(skillId);
        if (request.steps() != null) validateSteps(request.steps(), identity);
        apply(skill, request);
        skillRepository.save(skill);
        return SkillDto.from(skill);
    }

    @RequiresScope("skill:write")
    @Transactional
    public void delete(String skillId) {
        skillRepository.delete(require(skillId));
    }

    /** 试运行：按步骤真实执行，返回每一步的入参与结果，用于验证编排是否跑得通。 */
    @RequiresScope("skill:write")
    public Map<String, Object> dryRun(String skillId, Map<String, Object> inputs, Identity identity) {
        SkillEntity skill = require(skillId);
        CapabilitySnapshot snapshot = capabilityRegistry.load(identity);

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", inputs == null ? Map.of() : inputs);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        scope.put("steps", stepResults);

        List<Map<String, Object>> trace = new ArrayList<>();
        int index = 0;

        for (Map<String, Object> step : skill.getSteps()) {
            index++;
            String stepId = step.get("id") != null ? String.valueOf(step.get("id")) : "step" + index;

            if (!"tool".equals(String.valueOf(step.getOrDefault("type", "tool")))) {
                trace.add(Map.of("step", stepId, "type", step.get("type"),
                        "skipped", true, "message", "dry-run 仅执行 tool 步骤"));
                continue;
            }

            var entry = snapshot.tool(String.valueOf(step.get("tool")));
            if (entry.isEmpty()) {
                trace.add(Map.of("step", stepId, "success", false,
                        "error", "工具不可用或无权限：" + step.get("tool")));
                break;
            }

            Map<String, Object> args = SkillTemplates.resolveStepArgs(step, scope);
            ToolResult result = gateway.call(entry.get().server(), entry.get().tool().getName(), args);
            stepResults.put(stepId, Map.of("success", result.success(),
                    "data", result.data() == null ? Map.of() : result.data()));

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("step", stepId);
            record.put("tool", entry.get().qualifiedName());
            record.put("args", args);
            record.put("success", result.success());
            record.put("latency_ms", result.latencyMs());
            record.put("data", result.data());
            record.put("error", result.errorMessage());
            trace.add(record);

            boolean optional = Boolean.TRUE.equals(step.get("optional"));
            if (!result.success() && !optional) break;
        }

        return Map.of("skill", skill.getName(), "steps", trace,
                "output", SkillTemplates.render(skill.getOutputTemplate(), scope));
    }

    private void apply(SkillEntity skill, SkillSaveRequest request) {
        if (request.displayName() != null) skill.setDisplayName(request.displayName());
        if (request.description() != null) skill.setDescription(request.description());
        if (request.businessDomain() != null) skill.setBusinessDomain(request.businessDomain());
        if (request.steps() != null) skill.setSteps(request.steps());
        if (request.inputSchema() != null) skill.setInputSchema(request.inputSchema());
        if (request.outputTemplate() != null) skill.setOutputTemplate(request.outputTemplate());
        if (request.riskLevel() != null) skill.setRiskLevel(request.riskLevel());
        if (request.requiredScopes() != null) skill.setRequiredScopes(request.requiredScopes());
        if (request.examples() != null) skill.setExamples(request.examples());
        if (request.negativeExamples() != null) skill.setNegativeExamples(request.negativeExamples());
        if (request.owner() != null) skill.setOwner(request.owner());
        if (request.version() != null) skill.setVersion(request.version());
        if (request.status() != null) skill.setStatus(request.status());
        if (request.enabled() != null) skill.setEnabled(request.enabled());
        if (request.tags() != null) skill.setTags(request.tags());
    }

    /** 校验步骤引用的工具已注册，避免注册出「悬空」的 Skill。 */
    private void validateSteps(List<Map<String, Object>> steps, Identity identity) {
        if (steps == null) return;
        CapabilitySnapshot snapshot = capabilityRegistry.load(identity);

        for (Map<String, Object> step : steps) {
            if (!"tool".equals(String.valueOf(step.getOrDefault("type", "tool")))) continue;
            Object tool = step.get("tool");
            if (tool == null || String.valueOf(tool).isBlank()) {
                throw BizException.badRequest("步骤 " + step.get("id") + " 缺少 tool 字段");
            }
            if (snapshot.tool(String.valueOf(tool)).isEmpty()) {
                throw BizException.badRequest(
                        "步骤 " + step.get("id") + " 引用了未注册或无权访问的工具：" + tool);
            }
        }
    }

    private SkillEntity require(String skillId) {
        return skillRepository.findById(skillId).orElseThrow(() -> BizException.notFound("Skill"));
    }
}
