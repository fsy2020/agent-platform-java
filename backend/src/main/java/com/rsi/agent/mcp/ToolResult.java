package com.rsi.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具调用结果。错误在这里归一化 —— 下游千奇百怪的失败形态统一成
 * 有限的 errorType，模型和护栏都只需要认这几种。
 */
public record ToolResult(
        boolean success,
        Object data,
        String errorType,
        String errorMessage,
        long latencyMs,
        int attempts,
        boolean retryable,
        String server,
        String tool
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult ok(Object data, String server, String tool) {
        return new ToolResult(true, data, "", "", 0, 1, false, server, tool);
    }

    public static ToolResult fail(String errorType, String message, boolean retryable,
                                  String server, String tool) {
        return new ToolResult(false, null, errorType, message, 0, 1, retryable, server, tool);
    }

    public ToolResult withTiming(long latencyMs, int attempts) {
        return new ToolResult(success, data, errorType, errorMessage, latencyMs, attempts,
                retryable, server, tool);
    }

    /** 注入上下文时用的摘要，避免长工具输出撑爆窗口。 */
    public String summary(int maxChars) {
        if (!success) {
            return "[失败:" + errorType + "] " + errorMessage;
        }
        String text;
        try {
            text = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            text = String.valueOf(data);
        }
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "…（已截断，原始长度 " + text.length() + " 字符）";
    }

    public String summary() { return summary(600); }
}
