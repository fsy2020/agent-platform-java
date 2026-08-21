package com.rsi.agent.mcp;

import java.util.List;
import java.util.Map;

/**
 * 工具描述符 —— 平台内部对「一个 MCP 工具」的统一表示。
 *
 * <p>无论来自内置 Bean（反射生成）还是远程 MCP Server（tools/list 返回），
 * 最终都归一到这个结构，注册中心与网关只认它。
 */
public record ToolDescriptor(
        String server,
        String name,
        String description,
        Map<String, Object> inputSchema,
        String risk,
        boolean readOnly,
        List<String> scopes
) {
    public String qualifiedName() {
        return server + "." + name;
    }
}
