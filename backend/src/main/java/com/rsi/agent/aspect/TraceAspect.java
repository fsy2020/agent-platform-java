package com.rsi.agent.aspect;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rsi.agent.annotation.Traced;
import com.rsi.agent.observability.TraceCollector;
import com.rsi.agent.observability.TraceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 观测切面：把标注了 {@code @Traced} 的方法调用记录成一条 Span。
 *
 * <p>业务方法里因此看不到任何埋点代码 —— 耗时、成功/失败、异常类型
 * 全部由切面采集。Order 排在权限之后、容错之前：被权限拒绝的调用
 * 不该产生 Span，而重试的每一次尝试都应各记一条。
 */
@Aspect
@Component
@Order(100)
public class TraceAspect {

    @Around("@annotation(traced)")
    public Object record(ProceedingJoinPoint joinPoint, Traced traced) throws Throwable {
        TraceCollector collector = TraceContext.peek();
        if (collector == null) {
            // 不在一次 turn 内（例如注册中心的管理调用），不采集
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String name = traced.value().isBlank() ? signature.getName() : traced.value();
        long started = System.nanoTime();

        try {
            Object result = joinPoint.proceed();
            collector.addSpan(name, traced.type(), describeArgs(joinPoint),
                    describeResult(result), "success", "", elapsed(started));
            return result;
        } catch (Throwable error) {
            collector.addSpan(name, traced.type(), describeArgs(joinPoint), Map.of(),
                    "failed", error.getClass().getSimpleName() + ": " + error.getMessage(),
                    elapsed(started));
            throw error;
        }
    }

    /** 入参摘要：只留可读的标量，避免把整个实体塞进 Span。 */
    private Map<String, Object> describeArgs(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] values = joinPoint.getArgs();
        if (names == null) return Map.of();

        Map<String, Object> described = new LinkedHashMap<>();
        for (int i = 0; i < names.length && i < values.length; i++) {
            described.put(names[i], summarize(values[i]));
        }
        return described;
    }

    private Map<String, Object> describeResult(Object result) {
        if (result == null) return Map.of();
        return Map.of("result", summarize(result));
    }

    private Object summarize(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            String text = String.valueOf(value);
            return text.length() > 800 ? text.substring(0, 800) + "…" : text;
        }
        return value.getClass().getSimpleName();
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
