package com.rsi.agent.mcp.transport;

import java.util.List;
import java.util.Map;

import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.mcp.ToolDescriptor;
import com.rsi.agent.mcp.ToolTransport;
import org.springframework.stereotype.Component;

/**
 * SSE 传输。协议层与 Streamable HTTP 同为 JSON-RPC，差异只在帧格式，
 * 因此直接委派给 {@link HttpTransport} —— 组合优于继承。
 */
@Component
public class SseTransport implements ToolTransport {

    private final HttpTransport delegate;

    public SseTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public String transport() { return "sse"; }

    @Override
    public List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
        return delegate.discover(server);
    }

    @Override
    public Object call(McpServerEntity server, String toolName, Map<String, Object> args) throws Exception {
        return delegate.call(server, toolName, args);
    }
}
