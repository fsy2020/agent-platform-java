package com.rsi.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.agent.CapabilityRegistry;
import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.dto.RegistryDtos.SubAgentDto;
import com.rsi.agent.dto.RegistryDtos.SubAgentSaveRequest;
import com.rsi.agent.repository.SkillRepository;
import com.rsi.agent.repository.SubAgentRepository;
import com.rsi.agent.security.Identity;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/** 子 Agent 注册中心：业务方动态创建并绑定 MCP 工具 / Skill。 */
@Service
public class SubAgentService {

    private final SubAgentRepository agentRepository;
    private final SkillRepository skillRepository;
    private final CapabilityRegistry capabilityRegistry;

    public SubAgentService(SubAgentRepository agentRepository, SkillRepository skillRepository,
                           CapabilityRegistry capabilityRegistry) {
        this.agentRepository = agentRepository;
        this.skillRepository = skillRepository;
        this.capabilityRegistry = capabilityRegistry;
    }

    @RequiresScope("agent:read")
    public List<SubAgentDto> list() {
        return agentRepository.findAllByOrderByCreatedAtAsc().stream().map(SubAgentDto::from).toList();
    }

    @RequiresScope("agent:read")
    public SubAgentDto get(String agentId) {
        return SubAgentDto.from(require(agentId));
    }

    @RequiresScope("agent:write")
    @Transactional
    public SubAgentDto create(SubAgentSaveRequest request, Identity identity) {
        if (agentRepository.existsByName(request.name())) {
            throw BizException.conflict("子 Agent 名称已存在：" + request.name());
        }
        validateBindings(request.toolAllowlist(), request.skillNames(), identity);
        validateRoutingQuality(request.description(), request.positiveExamples());

        SubAgentEntity agent = new SubAgentEntity();
        agent.setName(request.name());
        apply(agent, request);
        if (agent.getOwner().isBlank()) agent.setOwner(identity.username());
        agentRepository.save(agent);
        return SubAgentDto.from(agent);
    }

    @RequiresScope("agent:write")
    @Transactional
    public SubAgentDto update(String agentId, SubAgentSaveRequest request, Identity identity) {
        SubAgentEntity agent = require(agentId);
        validateBindings(request.toolAllowlist(), request.skillNames(), identity);
        apply(agent, request);
        agentRepository.save(agent);
        return SubAgentDto.from(agent);
    }

    @RequiresScope("agent:write")
    @Transactional
    public void delete(String agentId) {
        agentRepository.delete(require(agentId));
    }

    /**
     * 运行时生效的能力集。
     *
     * <p>展示 declared → effective 的裁剪过程：声明了什么、实际拿到什么、
     * 因权限被拒了什么。这是验证「最小权限」是否真的生效的窗口。
     */
    @RequiresScope("agent:read")
    public Map<String, Object> effectiveCapabilities(String agentId, Identity identity) {
        SubAgentEntity agent = require(agentId);
        CapabilitySnapshot snapshot = capabilityRegistry.load(identity);

        List<String> declared = new ArrayList<>(agent.getToolAllowlist());
        List<String> effective = capabilityRegistry.agentTools(snapshot, agent).stream()
                .map(CapabilitySnapshot.ToolEntry::qualifiedName).sorted().toList();

        List<String> denied = declared.stream()
                .filter(name -> snapshot.tool(name).isEmpty())
                .filter(name -> snapshot.deniedTools().stream().anyMatch(d -> d.equals(name)))
                .sorted().toList();
        List<String> missing = declared.stream()
                .filter(name -> snapshot.tool(name).isEmpty())
                .filter(name -> !denied.contains(name))
                .sorted().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent.getName());
        result.put("declared_tools", declared.stream().sorted().toList());
        result.put("effective_tools", effective);
        result.put("denied_by_permission", denied);
        result.put("missing_tools", missing);
        result.put("skills", agent.getSkillNames());
        result.put("total_platform_tools", snapshot.totalToolCount());
        result.put("supervisor_visible_tools",
                capabilityRegistry.supervisorVisibleTools(snapshot).size());
        return result;
    }

    private void apply(SubAgentEntity agent, SubAgentSaveRequest request) {
        if (request.displayName() != null) agent.setDisplayName(request.displayName());
        if (request.description() != null) agent.setDescription(request.description());
        if (request.businessDomain() != null) agent.setBusinessDomain(request.businessDomain());
        if (request.systemPrompt() != null) agent.setSystemPrompt(request.systemPrompt());
        if (request.toolAllowlist() != null) agent.setToolAllowlist(request.toolAllowlist());
        if (request.skillNames() != null) agent.setSkillNames(request.skillNames());
        if (request.requiredScopes() != null) agent.setRequiredScopes(request.requiredScopes());
        if (request.positiveExamples() != null) agent.setPositiveExamples(request.positiveExamples());
        if (request.negativeExamples() != null) agent.setNegativeExamples(request.negativeExamples());
        if (request.usageExamples() != null) agent.setUsageExamples(request.usageExamples());
        if (request.owner() != null) agent.setOwner(request.owner());
        if (request.version() != null) agent.setVersion(request.version());
        if (request.riskLevel() != null) agent.setRiskLevel(request.riskLevel());
        if (request.status() != null) agent.setStatus(request.status());
        if (request.enabled() != null) agent.setEnabled(request.enabled());
        if (request.tenantId() != null) agent.setTenantId(request.tenantId());
        if (request.model() != null) agent.setModel(request.model());
        if (request.maxIterations() != null) agent.setMaxIterations(request.maxIterations());
        if (request.tags() != null) agent.setTags(request.tags());
    }

    private void validateBindings(List<String> tools, List<String> skills, Identity identity) {
        if (tools != null && !tools.isEmpty()) {
            CapabilitySnapshot snapshot = capabilityRegistry.load(identity);
            List<String> missing = tools.stream()
                    .filter(name -> snapshot.tool(name).isEmpty()).toList();
            if (!missing.isEmpty()) {
                throw BizException.badRequest("以下工具未注册或无权访问：" + String.join(", ", missing));
            }
        }
        if (skills != null && !skills.isEmpty()) {
            List<String> known = skillRepository.findByNameIn(skills).stream()
                    .map(s -> s.getName()).toList();
            List<String> missing = skills.stream().filter(name -> !known.contains(name)).toList();
            if (!missing.isEmpty()) {
                throw BizException.badRequest("以下 Skill 未注册：" + String.join(", ", missing));
            }
        }
    }

    /**
     * 路由质量前置校验。
     *
     * <p>description 是主 Agent 唯一的路由依据，正样例是 few-shot 的基线。
     * 这两项缺失会让误路由率显著升高，因此在注册时就拦住，而不是等上线后再查。
     */
    private void validateRoutingQuality(String description, List<String> positiveExamples) {
        if (description == null || description.strip().length() < 20) {
            throw BizException.badRequest(
                    "description 至少 20 字，需说明能力范围与输入输出规范，否则主 Agent 无法正确路由");
        }
        if (positiveExamples == null || positiveExamples.isEmpty()) {
            throw BizException.badRequest(
                    "必须提供至少一条 positive_examples（正样例），用于路由匹配与 few-shot");
        }
    }

    private SubAgentEntity require(String agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> BizException.notFound("子 Agent"));
    }
}
