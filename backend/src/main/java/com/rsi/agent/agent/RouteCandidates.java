package com.rsi.agent.agent;

import java.util.ArrayList;
import java.util.List;

/** 一次路由决策的候选集合，按能力粒度从粗到细分组。 */
public record RouteCandidates(List<Candidate> agents, List<Candidate> skills, List<Candidate> tools) {

    public static RouteCandidates empty() {
        return new RouteCandidates(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public List<Candidate> all() {
        List<Candidate> merged = new ArrayList<>(agents.size() + skills.size() + tools.size());
        merged.addAll(agents);
        merged.addAll(skills);
        merged.addAll(tools);
        return merged;
    }

    public int visibleCount() {
        return agents.size() + skills.size() + tools.size();
    }

    /**
     * 渲染成 Prompt 片段。
     *
     * <p>顺序本身就是优先级暗示：子 Agent → Skill → 单工具，
     * 并对工具做数量截断，避免 Prompt 膨胀。
     */
    public String describe(int maxTools) {
        StringBuilder sb = new StringBuilder();

        if (!agents.isEmpty()) {
            sb.append("## 可用业务子 Agent（复杂跨系统流程优先分发）\n");
            for (Candidate candidate : agents) {
                sb.append("- `").append(candidate.name()).append("`：")
                        .append(candidate.description()).append('\n');
                if (!candidate.positiveExamples().isEmpty()) {
                    sb.append("  - 适用示例：")
                            .append(String.join(" / ", limit(candidate.positiveExamples(), 3)))
                            .append('\n');
                }
                if (!candidate.negativeExamples().isEmpty()) {
                    sb.append("  - 不适用：")
                            .append(String.join(" / ", limit(candidate.negativeExamples(), 2)))
                            .append('\n');
                }
            }
        }

        if (!skills.isEmpty()) {
            sb.append("\n## 可用 Skill（标准 SOP，多工具编排）\n");
            for (Candidate candidate : skills) {
                sb.append("- `").append(candidate.name()).append("`：")
                        .append(candidate.description()).append('\n');
                if (!candidate.positiveExamples().isEmpty()) {
                    sb.append("  - 适用示例：")
                            .append(String.join(" / ", limit(candidate.positiveExamples(), 2)))
                            .append('\n');
                }
            }
        }

        if (!tools.isEmpty()) {
            sb.append("\n## 可用 MCP 工具（简单查询直接调用）\n");
            int shown = 0;
            for (Candidate candidate : tools) {
                if (shown++ >= maxTools) break;
                sb.append("- `").append(candidate.name()).append("`：")
                        .append(candidate.description());
                if (!candidate.requiredArgs().isEmpty()) {
                    sb.append("，必填参数：").append(String.join(", ", candidate.requiredArgs()));
                }
                sb.append('\n');
            }
            if (tools.size() > maxTools) {
                sb.append("- …（另有 ").append(tools.size() - maxTools)
                        .append(" 个工具未展示，如需使用请先分发给对应子 Agent）\n");
            }
        }
        return sb.toString();
    }

    private List<String> limit(List<String> source, int max) {
        return source.size() <= max ? source : source.subList(0, max);
    }
}
