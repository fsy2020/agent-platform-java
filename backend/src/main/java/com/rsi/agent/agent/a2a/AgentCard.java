package com.rsi.agent.agent.a2a;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A2A Agent Card —— 远程 Agent 的自描述文档。
 *
 * <p>约定位置 {@code /.well-known/agent-card.json}（早期规范用 {@code agent.json}，
 * 拉取时两个都试）。这份文档就是注册中心的数据来源：部门只交一个 URL，
 * 平台把它映射成 {@code SubAgentEntity}。
 *
 * <p>字段刻意只取平台用得上的子集。A2A 卡片里的多模态声明（inputModes /
 * outputModes）当前只做记录不做校验 —— 本平台的编排链路是纯文本的。
 */
public record AgentCard(
        String protocolVersion,
        String name,
        String description,
        String url,
        String preferredTransport,
        String version,
        boolean streaming,
        boolean pushNotifications,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<Skill> skills,
        Map<String, Object> securitySchemes
) {

    /** 卡片声明的一项能力。{@code examples} 正好可用作 Supervisor 的路由 few-shot。 */
    public record Skill(String id, String name, String description,
                        List<String> tags, List<String> examples) {}

    /**
     * 解析 Agent Card。
     *
     * <p>对缺失字段一律给默认值而不是抛异常 —— 卡片是外部产物，
     * 版本差异与字段缺失是常态，注册流程不该因为少一个可选字段就失败。
     */
    public static AgentCard from(JsonNode node) {
        JsonNode capabilities = node.path("capabilities");

        List<Skill> skills = new ArrayList<>();
        for (JsonNode item : node.path("skills")) {
            skills.add(new Skill(
                    item.path("id").asText(""),
                    item.path("name").asText(""),
                    item.path("description").asText(""),
                    texts(item.path("tags")),
                    texts(item.path("examples"))));
        }

        return new AgentCard(
                node.path("protocolVersion").asText(""),
                node.path("name").asText(""),
                node.path("description").asText(""),
                node.path("url").asText(""),
                node.path("preferredTransport").asText("JSONRPC"),
                node.path("version").asText(""),
                capabilities.path("streaming").asBoolean(false),
                capabilities.path("pushNotifications").asBoolean(false),
                texts(node.path("defaultInputModes")),
                texts(node.path("defaultOutputModes")),
                List.copyOf(skills),
                node.has("securitySchemes")
                        ? A2aProtocol.toMap(node.get("securitySchemes")) : Map.of());
    }

    /** 卡片里所有 skill 的 examples 合并 —— 直接喂给路由候选集当正样例。 */
    public List<String> allExamples() {
        List<String> merged = new ArrayList<>();
        skills.forEach(skill -> merged.addAll(skill.examples()));
        return merged;
    }

    /** 卡片里所有 skill 的 tags 合并去重。 */
    public List<String> allTags() {
        return skills.stream().flatMap(skill -> skill.tags().stream()).distinct().sorted().toList();
    }

    /**
     * 供路由使用的描述文本。
     *
     * <p>卡片顶层 description 往往只有一句话，不足以支撑路由决策，
     * 因此把各 skill 的名称与描述一并展开。
     */
    public String routingDescription() {
        StringBuilder sb = new StringBuilder(description == null ? "" : description.strip());
        for (Skill skill : skills) {
            if (skill.description() == null || skill.description().isBlank()) continue;
            sb.append(sb.isEmpty() ? "" : "；").append(skill.name()).append('：')
                    .append(skill.description().strip());
        }
        return sb.toString();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isTextual()) values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
