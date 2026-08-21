package com.rsi.agent.mcp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.rsi.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 服务级熔断：滑动窗口内失败率超阈值即打开，冷却后半开探活。
 *
 * <p>只有传输类故障计入统计 —— 业务错误（如「员工不存在」）是正常返回，
 * 不应该把整个下游服务拖进熔断。这条边界是实现时最容易踩错的地方。
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AgentProperties.Resilience config;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public CircuitBreaker(AgentProperties properties) {
        this.config = properties.resilience();
    }

    private static final class Window {
        final Deque<long[]> events = new ArrayDeque<>();   // [timestampMs, ok?1:0]
        State state = State.CLOSED;
        long openedAt = 0;
    }

    public synchronized State state(String key) {
        Window window = windows.computeIfAbsent(key, k -> new Window());
        if (window.state == State.OPEN
                && System.currentTimeMillis() - window.openedAt >= config.circuitProbeSeconds() * 1000L) {
            window.state = State.HALF_OPEN;
        }
        return window.state;
    }

    public boolean allow(String key) {
        return state(key) != State.OPEN;
    }

    /** 记录一次调用结果，返回熔断器的新状态。 */
    public synchronized State record(String key, boolean ok) {
        Window window = windows.computeIfAbsent(key, k -> new Window());
        long now = System.currentTimeMillis();
        window.events.addLast(new long[]{now, ok ? 1 : 0});

        long cutoff = now - config.circuitWindowSeconds() * 1000L;
        while (!window.events.isEmpty() && window.events.peekFirst()[0] < cutoff) {
            window.events.pollFirst();
        }

        if (window.state == State.HALF_OPEN) {
            // 探活结果直接决定恢复还是继续熔断
            window.state = ok ? State.CLOSED : State.OPEN;
            if (!ok) window.openedAt = now;
            return window.state;
        }

        if (window.state == State.CLOSED && window.events.size() >= 5
                && failureRate(window) > config.circuitFailureRate()) {
            window.state = State.OPEN;
            window.openedAt = now;
            log.warn("熔断开启: {} 窗口失败率={}%", key, Math.round(failureRate(window) * 100));
        }
        return window.state;
    }

    public synchronized void reset(String key) {
        windows.remove(key);
    }

    public synchronized Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        windows.forEach((key, window) -> result.put(key, Map.of(
                "state", state(key).name().toLowerCase(),
                "samples", window.events.size(),
                "failure_rate", Math.round(failureRate(window) * 1000) / 1000.0)));
        return result;
    }

    private double failureRate(Window window) {
        if (window.events.isEmpty()) return 0;
        long failures = window.events.stream().filter(e -> e[1] == 0).count();
        return (double) failures / window.events.size();
    }
}
