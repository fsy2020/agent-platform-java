package com.rsi.agent.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rsi.agent.agent.transport.AgentTransport;
import com.rsi.agent.agent.transport.DelegationContracts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent Gateway —— 所有子 Agent 委派的统一入口。
 *
 * <p>与 {@code McpGateway} 同构：传输差异被策略表吃掉，这里只剩
 * <b>路由到实现</b>与<b>异常归一化</b>两件事。新增一种 Agent 互操作协议
 * 只需新增一个 {@link AgentTransport} Bean，本类不改一行。
 */
@Service
public class AgentGateway {

    private static final Logger log = LoggerFactory.getLogger(AgentGateway.class);

    private final Map<String, AgentTransport> transports;

    public AgentGateway(List<AgentTransport> transports) {
        this.transports = transports.stream().collect(
                Collectors.toUnmodifiableMap(AgentTransport::transport, Function.identity()));
        log.info("已装配子 Agent 传输：{}", this.transports.keySet());
    }

    public boolean supports(String transport) {
        return transports.containsKey(normalize(transport));
    }

    public List<String> supported() {
        return transports.keySet().stream().sorted().toList();
    }

    /**
     * 执行一次委派。
     *
     * <p>任何未被传输层消化的异常都会翻译成 {@code failed} 结果，
     * 让 Supervisor 拿到一致的语义而不是一个堆栈 —— 子 Agent 挂了不该
     * 让整条编排崩掉，而应作为一条证据参与后续决策。
     */
    public DelegationContracts.Result delegate(DelegationContracts.Request request,
                                               DelegationContracts.Host host,
                                               Consumer<AgentEvent> emit) {
        String transport = normalize(request.agent().getTransport());
        AgentTransport impl = transports.get(transport);

        if (impl == null) {
            String message = "子 Agent %s 声明了未知传输 `%s`，可用：%s"
                    .formatted(request.agent().getName(), transport, supported());
            emit.accept(AgentEvent.of("guard", "kind", "unknown_agent_transport", "message", message));
            return DelegationContracts.Result.failed("unknown_transport", message,
                    List.of("[路由错误] " + message), 0, 0);
        }

        try {
            return impl.delegate(request, host, emit);
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("子 Agent 委派异常 {}（{}）: {}", request.agent().getName(), transport, detail);
            return DelegationContracts.Result.failed("delegation_error", detail,
                    List.of("[%s] 委派异常：%s".formatted(request.agent().getName(), detail)), 0, 0);
        }
    }

    private String normalize(String transport) {
        return transport == null || transport.isBlank() ? "local" : transport;
    }
}
