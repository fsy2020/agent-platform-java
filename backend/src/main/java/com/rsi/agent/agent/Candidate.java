package com.rsi.agent.agent;

import java.util.List;

/**
 * 路由候选项 —— 一个子 Agent / Skill / 工具在路由视角下的统一投影。
 *
 * <p>LLM 路由与规则引擎共用同一份候选集，保证两条路径的可见能力完全一致：
 * 降级时行为可预期，评测结果也可比。
 */
public record Candidate(
        Kind kind,
        String name,
        String description,
        List<String> keywords,
        List<String> positiveExamples,
        List<String> negativeExamples,
        List<String> requiredArgs,
        List<String> argNames
) {
    public enum Kind { AGENT, SKILL, TOOL;
        public String lower() { return name().toLowerCase(); }
    }

    public static Candidate ofAgent(String name, String description, List<String> keywords,
                                    List<String> positives, List<String> negatives) {
        return new Candidate(Kind.AGENT, name, description, keywords, positives, negatives,
                List.of(), List.of());
    }

    public static Candidate ofSkill(String name, String description, List<String> keywords,
                                    List<String> positives, List<String> negatives,
                                    List<String> requiredArgs, List<String> argNames) {
        return new Candidate(Kind.SKILL, name, description, keywords, positives, negatives,
                requiredArgs, argNames);
    }

    public static Candidate ofTool(String name, String description, List<String> keywords,
                                   List<String> requiredArgs, List<String> argNames) {
        return new Candidate(Kind.TOOL, name, description, keywords, List.of(), List.of(),
                requiredArgs, argNames);
    }
}
