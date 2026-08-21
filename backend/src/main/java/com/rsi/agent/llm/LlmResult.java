package com.rsi.agent.llm;

import com.rsi.agent.agent.RoutePlan;

/** 一次模型调用的结果与元数据（含降级/切备用标记，供观测与成本归因使用）。 */
public record LlmResult(
        String text,
        RoutePlan plan,
        String model,
        int promptTokens,
        int completionTokens,
        boolean fallbackUsed,
        boolean degraded,
        String error
) {
    public static LlmResult ofText(String text, String model, int promptTokens,
                                   int completionTokens, boolean fallbackUsed) {
        return new LlmResult(text, null, model, promptTokens, completionTokens,
                fallbackUsed, false, "");
    }

    public static LlmResult ofPlan(RoutePlan plan, String model, int promptTokens,
                                   boolean fallbackUsed) {
        return new LlmResult("", plan, model, promptTokens, 0, fallbackUsed, false, "");
    }

    /** 规则引擎兜底产出的结果。 */
    public static LlmResult degraded(RoutePlan plan, String error) {
        return new LlmResult("", plan, "rule-engine", 0, 0, false, true, error);
    }

    public static LlmResult unavailable() {
        return new LlmResult("", null, "rule-engine", 0, 0, false, true, "llm_disabled");
    }
}
