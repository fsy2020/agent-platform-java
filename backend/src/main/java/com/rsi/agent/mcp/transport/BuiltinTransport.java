package com.rsi.agent.mcp.transport;

import java.util.List;
import java.util.Map;

import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.mcp.BuiltinToolRegistry;
import com.rsi.agent.mcp.ToolDescriptor;
import com.rsi.agent.mcp.ToolTransport;
import org.springframework.stereotype.Component;

/**
 * 内置传输：工具就是本进程里的 Spring Bean 方法，经反射调用。
 *
 * <p>用于演示与联调，无需任何外部依赖；生产接入真实系统时换成
 * stdio / streamable_http 即可，上层编排逻辑完全不变。
 */
@Component
public class BuiltinTransport implements ToolTransport {

    private final BuiltinToolRegistry registry;

    public BuiltinTransport(BuiltinToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String transport() { return "builtin"; }

    @Override
    public List<ToolDescriptor> discover(McpServerEntity server) {
        return registry.descriptors(server.getName());
    }

    @Override
    public Object call(McpServerEntity server, String toolName, Map<String, Object> args) throws Exception {
        return registry.invoke(server.getName(), toolName, args);
    }
}
