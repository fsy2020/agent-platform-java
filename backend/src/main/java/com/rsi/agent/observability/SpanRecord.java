package com.rsi.agent.observability;

import java.util.Map;

/** 一条执行片段。路由决策、工具调用、护栏拦截、审批中断各占一条。 */
public record SpanRecord(
        int sequence,
        String name,
        String type,
        Map<String, Object> input,
        Map<String, Object> output,
        String status,
        String error,
        long latencyMs
) {}
