package com.rsi.agent.mcp;

/** 熔断打开时由切面抛出，网关据此归一化为 circuit_open 错误。 */
public class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String key) {
        super("服务 " + key + " 处于熔断状态，请稍后重试或改用其他执行路径");
    }
}
