package com.rsi.agent.annotation;

import java.lang.annotation.*;

/**
 * 声明式容错：超时 → 指数退避重试 → 熔断。
 *
 * <p>由 {@code ResilienceAspect} 实现。重试只针对传输类故障；业务错误
 * （如「员工不存在」）不重试，直接归一化回传给模型自纠。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Resilient {

    /** 熔断分组键的 SpEL 表达式，默认按第一个入参（MCP Server 名）分组。 */
    String key() default "";
}
