package com.rsi.agent.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.domain.SubAgentEntity;

/**
 * 一次请求内的能力快照 —— 已按身份、租户、权限裁剪过。
 *
 * <p>「effective tools」这个概念就落在这里：注册中心里有什么是一回事，
 * 当前这个人在这一刻能看到什么是另一回事。
 */
public class CapabilitySnapshot {

    public record ToolEntry(McpToolEntity tool, McpServerEntity server) {
        public String qualifiedName() { return server.getName() + "." + tool.getName(); }
    }

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final Map<String, SkillEntity> skills = new LinkedHashMap<>();
    private final Map<String, SubAgentEntity> agents = new LinkedHashMap<>();
    private final List<String> deniedTools = new ArrayList<>();
    private int totalToolCount;

    public void addTool(ToolEntry entry) { tools.put(entry.qualifiedName(), entry); }
    public void denyTool(String qualifiedName) { deniedTools.add(qualifiedName); }
    public void addSkill(SkillEntity skill) { skills.put(skill.getName(), skill); }
    public void addAgent(SubAgentEntity agent) { agents.put(agent.getName(), agent); }

    public Map<String, ToolEntry> tools() { return tools; }
    public Map<String, SkillEntity> skills() { return skills; }
    public Map<String, SubAgentEntity> agents() { return agents; }
    public List<String> deniedTools() { return deniedTools; }

    public int totalToolCount() { return totalToolCount; }
    public void totalToolCount(int value) { this.totalToolCount = value; }

    /** 按全名查工具；模型只写短名时做一次唯一性回退匹配。 */
    public Optional<ToolEntry> tool(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        ToolEntry exact = tools.get(name);
        if (exact != null) return Optional.of(exact);

        List<ToolEntry> matches = tools.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().indexOf('.') + 1).equals(name))
                .map(Map.Entry::getValue)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public Optional<SkillEntity> skill(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        SkillEntity exact = skills.get(name);
        if (exact != null) return Optional.of(exact);
        return skills.values().stream()
                .filter(s -> s.getName().equalsIgnoreCase(name) || s.getDisplayName().equals(name))
                .findFirst();
    }

    public Optional<SubAgentEntity> agent(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        SubAgentEntity exact = agents.get(name);
        if (exact != null) return Optional.of(exact);
        return agents.values().stream()
                .filter(a -> a.getName().equalsIgnoreCase(name) || a.getDisplayName().equals(name))
                .findFirst();
    }

    /** 构造一个只含指定工具的子快照 —— 用于给子 Agent 划工具边界。 */
    public static CapabilitySnapshot ofTools(List<ToolEntry> entries, int totalToolCount) {
        CapabilitySnapshot snapshot = new CapabilitySnapshot();
        entries.forEach(snapshot::addTool);
        snapshot.totalToolCount(totalToolCount);
        return snapshot;
    }
}
