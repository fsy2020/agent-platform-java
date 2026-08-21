package com.rsi.agent.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rsi.agent.agent.CapabilitySnapshot.ToolEntry;
import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.repository.McpServerRepository;
import com.rsi.agent.repository.McpToolRepository;
import com.rsi.agent.repository.SkillRepository;
import com.rsi.agent.repository.SubAgentRepository;
import com.rsi.agent.security.Identity;
import org.springframework.stereotype.Service;

/**
 * 能力注册中心：运行时计算 effective agents / effective tools。
 *
 * <p>核心目的是<b>把单次请求可见的能力数收敛到个位数</b>。工具规模扩到 150+ 时，
 * 主 Agent 看到的仍然只是「几个子 Agent + 几个 Skill」，因此 Prompt 不膨胀、
 * 工具不误选。
 */
@Service
public class CapabilityRegistry {

    private final McpToolRepository toolRepository;
    private final McpServerRepository serverRepository;
    private final SkillRepository skillRepository;
    private final SubAgentRepository agentRepository;

    public CapabilityRegistry(McpToolRepository toolRepository,
                              McpServerRepository serverRepository,
                              SkillRepository skillRepository,
                              SubAgentRepository agentRepository) {
        this.toolRepository = toolRepository;
        this.serverRepository = serverRepository;
        this.skillRepository = skillRepository;
        this.agentRepository = agentRepository;
    }

    /** 按身份加载可见能力。权限不足的工具进 denied 列表，便于在 UI 上解释「为什么看不到」。 */
    public CapabilitySnapshot load(Identity identity) {
        CapabilitySnapshot snapshot = new CapabilitySnapshot();

        Map<String, McpServerEntity> servers = new LinkedHashMap<>();
        serverRepository.findAll().forEach(server -> servers.put(server.getId(), server));

        List<McpToolEntity> tools = toolRepository.findByEnabledTrue();
        int total = 0;
        for (McpToolEntity tool : tools) {
            McpServerEntity server = servers.get(tool.getServerId());
            if (server == null || !server.isEnabled()) continue;
            total++;

            ToolEntry entry = new ToolEntry(tool, server);
            if (identity.hasScopes(tool.getRequiredScopes())) {
                snapshot.addTool(entry);
            } else {
                snapshot.denyTool(entry.qualifiedName());
            }
        }
        snapshot.totalToolCount(total);

        for (SkillEntity skill : skillRepository.findByEnabledTrueAndStatus("published")) {
            if (identity.hasScopes(skill.getRequiredScopes())) snapshot.addSkill(skill);
        }

        for (SubAgentEntity agent : agentRepository.findByEnabledTrueAndStatus("published")) {
            boolean tenantVisible = agent.getTenantId().equals(identity.tenantId())
                    || "default".equals(agent.getTenantId());
            if (tenantVisible && identity.hasScopes(agent.getRequiredScopes())) {
                snapshot.addAgent(agent);
            }
        }
        return snapshot;
    }

    /** 子 Agent 的最小必要工具集：allowlist ∩ 当前可见工具 ∪ 其 Skill 所需工具。 */
    public List<ToolEntry> agentTools(CapabilitySnapshot snapshot, SubAgentEntity agent) {
        Map<String, ToolEntry> entries = new LinkedHashMap<>();

        for (String name : agent.getToolAllowlist()) {
            snapshot.tool(name).ifPresent(entry -> entries.put(entry.qualifiedName(), entry));
        }
        for (String skillName : agent.getSkillNames()) {
            snapshot.skill(skillName).ifPresent(skill -> {
                for (Map<String, Object> step : skill.getSteps()) {
                    if ("tool".equals(step.get("type")) && step.get("tool") != null) {
                        snapshot.tool(String.valueOf(step.get("tool")))
                                .ifPresent(entry -> entries.put(entry.qualifiedName(), entry));
                    }
                }
            });
        }
        return List.copyOf(entries.values());
    }

    /**
     * Supervisor 直接可见的工具 = 未被任何子 Agent / Skill 认领的通用工具。
     *
     * <p>这是可见性收敛的关键一步：领域工具归子 Agent 持有，主 Agent 通过
     * delegate 使用，而不是一次性摊开在 Prompt 里。
     */
    public List<ToolEntry> supervisorVisibleTools(CapabilitySnapshot snapshot) {
        Set<String> claimed = new LinkedHashSet<>();

        for (SubAgentEntity agent : snapshot.agents().values()) {
            for (String name : agent.getToolAllowlist()) {
                snapshot.tool(name).ifPresent(entry -> claimed.add(entry.qualifiedName()));
            }
        }
        for (SkillEntity skill : snapshot.skills().values()) {
            for (Map<String, Object> step : skill.getSteps()) {
                if ("tool".equals(step.get("type")) && step.get("tool") != null) {
                    snapshot.tool(String.valueOf(step.get("tool")))
                            .ifPresent(entry -> claimed.add(entry.qualifiedName()));
                }
            }
        }

        List<ToolEntry> visible = new ArrayList<>();
        snapshot.tools().forEach((name, entry) -> {
            if (!claimed.contains(name)) visible.add(entry);
        });
        return visible;
    }

    /** 构建主 Agent 的路由候选集：全部子 Agent + Skill + 未认领的通用工具。 */
    public RouteCandidates supervisorCandidates(CapabilitySnapshot snapshot) {
        RouteCandidates candidates = RouteCandidates.empty();

        for (SubAgentEntity agent : snapshot.agents().values()) {
            List<String> keywords = new ArrayList<>(agent.getTags());
            keywords.add(agent.getBusinessDomain());
            candidates.agents().add(Candidate.ofAgent(
                    agent.getName(),
                    "%s（业务域：%s）—— %s".formatted(
                            agent.getDisplayName().isBlank() ? agent.getName() : agent.getDisplayName(),
                            agent.getBusinessDomain(), agent.getDescription()),
                    keywords, agent.getPositiveExamples(), agent.getNegativeExamples()));
        }

        for (SkillEntity skill : snapshot.skills().values()) {
            List<String> keywords = new ArrayList<>(skill.getTags());
            keywords.add(skill.getBusinessDomain());
            candidates.skills().add(Candidate.ofSkill(
                    skill.getName(),
                    "%s —— %s".formatted(
                            skill.getDisplayName().isBlank() ? skill.getName() : skill.getDisplayName(),
                            skill.getDescription()),
                    keywords, skill.getExamples(), skill.getNegativeExamples(),
                    requiredOf(skill.getInputSchema()), propertiesOf(skill.getInputSchema())));
        }

        for (ToolEntry entry : supervisorVisibleTools(snapshot)) {
            candidates.tools().add(toolCandidate(entry));
        }
        return candidates;
    }

    /** 子 Agent 的候选集只含它自己的工具 —— 不放 Skill 与其他 Agent，杜绝越界与二次分发。 */
    public RouteCandidates agentCandidates(List<ToolEntry> entries) {
        RouteCandidates candidates = RouteCandidates.empty();
        entries.forEach(entry -> candidates.tools().add(toolCandidate(entry)));
        return candidates;
    }

    private Candidate toolCandidate(ToolEntry entry) {
        Map<String, Object> schema = entry.tool().getInputSchema();
        List<String> keywords = new ArrayList<>();
        keywords.add(entry.server().getBusinessDomain());
        keywords.add(entry.server().getDescription());
        // 参数说明里常含业务词（如「差旅/餐饮」），纳入语料能显著提升匹配准确率
        keywords.add(parameterText(schema));

        return Candidate.ofTool(
                entry.qualifiedName(),
                "%s（来自 %s）".formatted(entry.tool().getDescription(),
                        entry.server().getDisplayName().isBlank()
                                ? entry.server().getName() : entry.server().getDisplayName()),
                keywords, requiredOf(schema), propertiesOf(schema));
    }

    @SuppressWarnings("unchecked")
    public static List<String> requiredOf(Map<String, Object> schema) {
        Object required = schema == null ? null : schema.get("required");
        return required instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> propertiesOf(Map<String, Object> schema) {
        Object properties = schema == null ? null : schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            return List.copyOf((Set<String>) map.keySet());
        }
        return List.of();
    }

    private String parameterText(Map<String, Object> schema) {
        Object properties = schema == null ? null : schema.get("properties");
        if (!(properties instanceof Map<?, ?> map)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> spec && spec.get("description") != null) {
                sb.append(spec.get("description")).append(" ");
            }
        }
        return sb.toString();
    }
}
