package com.rsi.agent.agent.transport;

import java.util.function.Consumer;

import com.rsi.agent.agent.AgentEvent;

/**
 * 子 Agent 传输层策略接口。
 *
 * <p>与 {@code ToolTransport} 完全同构：每种传输是一个 Spring Bean，
 * 由 {@code AgentGateway} 构造注入 {@code List<AgentTransport>} 自动收集并按
 * {@link #transport()} 建索引 —— 将来接入别的 Agent 互操作协议只需新增一个 Bean。
 *
 * <p>当前两种实现：
 * <ul>
 *   <li>{@code local} —— 子 Agent 是平台上的一份配置，ReAct 循环跑在本进程</li>
 *   <li>{@code a2a} —— 子 Agent 是外部部门自己部署的服务，走 A2A 协议远程调用</li>
 * </ul>
 */
public interface AgentTransport {

    /** 该实现支持的传输标识，与 {@code SubAgentEntity.transport} 对应。 */
    String transport();

    /**
     * 执行一次委派。
     *
     * <p>实现方<b>不得</b>抛出异常来表达业务失败 —— 失败一律用
     * {@code Result.failed(...)} 表达，让父 Agent 拿到一致的语义。
     * 传输层意外异常由 {@code AgentGateway} 兜底归一化。
     */
    DelegationContracts.Result delegate(DelegationContracts.Request request,
                                        DelegationContracts.Host host,
                                        Consumer<AgentEvent> emit);
}
