package com.rsi.agent.annotation;

import java.lang.annotation.*;

/**
 * 声明式链路观测：把方法调用记录为一条 Span。
 *
 * <p>由 {@code TraceAspect} 实现，自动记录耗时、成功/失败、异常类型，
 * 并挂到当前 turn 的 Trace 上，支撑轨迹回放。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Traced {

    /** Span 名称，留空取方法名。 */
    String value() default "";

    /** Span 类型：context / route / tool / skill / agent / llm / guard / approval。 */
    String type() default "internal";
}
