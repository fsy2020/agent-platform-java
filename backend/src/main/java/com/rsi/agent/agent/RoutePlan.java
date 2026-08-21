package com.rsi.agent.agent;

import java.util.Map;

/**
 * Supervisor 的执行路径 —— 用<b>密封接口 + record</b> 建模。
 *
 * <p>相比一个带 {@code String routeType} 的贫血对象，密封类型有两个实打实的好处：
 * <ul>
 *   <li>每条路径携带的数据不同（调工具要 args，委派子 Agent 不要），
 *       类型系统直接表达这种差异，不用在运行时判空</li>
 *   <li>执行器用 switch 模式匹配分发时<b>编译期穷尽性检查</b>：
 *       将来新增一种执行路径，所有没处理它的 switch 会直接编译失败，
 *       而不是在线上悄悄走进 default 分支</li>
 * </ul>
 */
public sealed interface RoutePlan {

    double confidence();

    String reason();

    /** 路径类型标识，用于落库与前端展示（与既有 API 契约一致）。 */
    String type();

    /** 目标名称；answer / clarify 无目标。 */
    default String target() { return ""; }

    /** 简单查询：直接调用单个 MCP 工具。 */
    record CallTool(String tool, Map<String, Object> arguments, double confidence, String reason)
            implements RoutePlan {
        public String type() { return "tool"; }
        public String target() { return tool; }
    }

    /** 标准 SOP：执行已沉淀的 Skill。 */
    record RunSkill(String skill, Map<String, Object> inputs, double confidence, String reason)
            implements RoutePlan {
        public String type() { return "skill"; }
        public String target() { return skill; }
    }

    /** 跨系统复杂流程：分发给业务子 Agent。 */
    record Delegate(String agent, double confidence, String reason) implements RoutePlan {
        public String type() { return "agent"; }
        public String target() { return agent; }
    }

    /** 无需调用任何能力，直接回答。 */
    record Reply(String message, double confidence, String reason) implements RoutePlan {
        public String type() { return "answer"; }
    }

    /** 缺少必要参数或意图不明，向用户澄清。 */
    record Clarify(String message, double confidence, String reason) implements RoutePlan {
        public String type() { return "clarify"; }
    }

    /** 是否需要进入执行阶段（answer / clarify 直接收敛）。 */
    default boolean needsExecution() {
        return this instanceof CallTool || this instanceof RunSkill || this instanceof Delegate;
    }
}
