package com.rsi.agent.mcp;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.McpServerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MCP Gateway —— 所有工具调用的统一入口。
 *
 * <p>职责收敛为两件事：<b>错误码归一化</b>与<b>结果摘要</b>。
 * 超时、重试、熔断已被 {@code ResilienceAspect} 切走，传输差异被
 * {@code ToolInvoker} 的策略表吃掉，所以这里的代码短得几乎只剩语义。
 */
@Service
public class McpGateway {

    private static final Logger log = LoggerFactory.getLogger(McpGateway.class);

    private final ToolInvoker invoker;

    public McpGateway(ToolInvoker invoker) {
        this.invoker = invoker;
    }

    public java.util.List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
        return invoker.discover(server);
    }

    /**
     * 执行一次工具调用。任何异常都会被翻译成有限的 errorType，
     * 让模型和护栏拿到一致的语义，而不是五花八门的堆栈。
     */
    public ToolResult call(McpServerEntity server, String toolName, Map<String, Object> args) {
        long started = System.nanoTime();
        try {
            Object data = invoker.invoke(server, toolName, args);

            // 下游约定：返回体里带 error 字段即业务失败，重试无意义
            if (data instanceof Map<?, ?> map && map.get("error") != null) {
                Object message = map.get("message") != null ? map.get("message") : map.get("error");
                return ToolResult.fail("business_error", String.valueOf(message), false,
                                server.getName(), toolName)
                        .withTiming(elapsed(started), 1);
            }
            return ToolResult.ok(data, server.getName(), toolName).withTiming(elapsed(started), 1);

        } catch (CircuitOpenException e) {
            return ToolResult.fail("circuit_open", e.getMessage(), false, server.getName(), toolName)
                    .withTiming(elapsed(started), 1);
        } catch (TimeoutException e) {
            return ToolResult.fail("timeout", "调用超时", true, server.getName(), toolName)
                    .withTiming(elapsed(started), 1);
        } catch (BizException e) {
            return ToolResult.fail("invalid_arguments", e.getMessage(), false, server.getName(), toolName)
                    .withTiming(elapsed(started), 1);
        } catch (IllegalArgumentException e) {
            // 入参与工具 schema 不匹配：重试无意义，回传给模型自纠
            return ToolResult.fail("invalid_arguments", "参数与工具 schema 不匹配：" + e.getMessage(),
                            false, server.getName(), toolName)
                    .withTiming(elapsed(started), 1);
        } catch (Exception e) {
            log.warn("工具调用失败 {}.{}: {}", server.getName(), toolName, e.toString());
            return ToolResult.fail("transport_error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                            true, server.getName(), toolName)
                    .withTiming(elapsed(started), 1);
        }
    }

    /** 健康探活：返回 (状态, 错误信息)。 */
    public String[] healthCheck(McpServerEntity server) {
        try {
            var tools = discover(server);
            return tools.isEmpty() ? new String[]{"degraded", "未发现任何工具"} : new String[]{"healthy", ""};
        } catch (Exception e) {
            return new String[]{"down", e.getClass().getSimpleName() + ": " + e.getMessage()};
        }
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
