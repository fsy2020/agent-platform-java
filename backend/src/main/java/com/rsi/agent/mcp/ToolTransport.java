package com.rsi.agent.mcp;

import java.util.List;
import java.util.Map;

import com.rsi.agent.domain.McpServerEntity;

/**
 * 传输层策略接口。
 *
 * <p>每种传输（builtin / stdio / streamable_http / sse）是一个 Spring Bean，
 * 由 {@code McpGateway} 通过构造注入 {@code List<ToolTransport>} 自动收集并按
 * {@link #transport()} 建索引 —— 新增一种传输只需新增一个 Bean，网关代码不改一行。
 * 这是 IoC 消除 switch-case 的典型用法。
 */
public interface ToolTransport {

    /** 该实现支持的传输标识，与 McpServerEntity.transport 对应。 */
    String transport();

    /** 探测 Server 暴露的工具清单（动态热加载）。 */
    List<ToolDescriptor> discover(McpServerEntity server) throws Exception;

    /** 执行一次工具调用。异常交由网关统一归一化。 */
    Object call(McpServerEntity server, String toolName, Map<String, Object> args) throws Exception;
}
