package com.rsi.agent.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排过程中向外抛出的事件，经 SSE 实时推给前端。
 *
 * <p>事件名与既有前端契约一一对应：session / context / route / tool_start /
 * tool_end / skill_start / skill_end / agent_start / agent_end / guard /
 * approval_required / delta / answer / error / done。
 */
public record AgentEvent(String event, Map<String, Object> data) {

    public static AgentEvent of(String event, Map<String, Object> data) {
        return new AgentEvent(event, data == null ? Map.of() : data);
    }

    /** 便捷构造：可变参数键值对，允许 null 值（Map.of 不允许）。 */
    public static AgentEvent of(String event, Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return new AgentEvent(event, data);
    }
}
