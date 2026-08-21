package com.rsi.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rsi.agent.annotation.Resilient;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.McpServerEntity;
import org.springframework.stereotype.Component;

/**
 * 传输分发器。
 *
 * <p>构造函数注入 {@code List<ToolTransport>} —— 容器把所有实现收集进来，
 * 这里按 transport 标识建索引。新增一种传输只要新增一个 Bean，本类零改动。
 *
 * <p>方法上的 {@link Resilient} 由切面织入超时重试与熔断；本类只关心
 * 「选哪个传输、把调用发出去」，容错逻辑完全不出现在这里。
 */
@Component
public class ToolInvoker {

    private final Map<String, ToolTransport> transports;

    public ToolInvoker(List<ToolTransport> transportBeans) {
        this.transports = transportBeans.stream()
                .collect(Collectors.toMap(ToolTransport::transport, Function.identity()));
    }

    public ToolTransport resolve(String transport) {
        ToolTransport impl = transports.get(transport);
        if (impl == null) {
            throw BizException.badRequest("不支持的传输方式：" + transport);
        }
        return impl;
    }

    /** 工具发现不做重试 —— 注册动作由人触发，失败应立即暴露。 */
    public List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
        return resolve(server.getTransport()).discover(server);
    }

    /** 真正的工具调用。切面在此处织入 超时 → 指数退避重试 → 熔断。 */
    @Resilient(key = "#server.name")
    public Object invoke(McpServerEntity server, String toolName, Map<String, Object> args)
            throws Exception {
        return resolve(server.getTransport()).call(server, toolName, args);
    }
}
