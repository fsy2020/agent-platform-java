package com.rsi.agent.observability;

/**
 * 当前 turn 的观测上下文。
 *
 * <p>有了它，{@code @Traced} 切面才能在完全不知道业务参数的情况下，
 * 把方法调用挂到正确的 Trace 上。
 */
public final class TraceContext {

    private static final ThreadLocal<TraceCollector> HOLDER = new ThreadLocal<>();

    private TraceContext() {}

    public static void set(TraceCollector collector) { HOLDER.set(collector); }

    public static TraceCollector peek() { return HOLDER.get(); }

    public static void clear() { HOLDER.remove(); }
}
