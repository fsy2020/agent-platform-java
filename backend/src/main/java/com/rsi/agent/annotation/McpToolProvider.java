package com.rsi.agent.annotation;

import java.lang.annotation.*;

import org.springframework.stereotype.Component;

/**
 * 标记一个 Spring Bean 为「内置 MCP Server」。
 *
 * <p>注解上叠加了 {@link Component}，因此被标注的类同时是 Bean —— 这是 Spring 的
 * 元注解（meta-annotation）组合能力：一个注解既声明业务语义，又完成容器注册。
 * 启动时 {@code McpToolScanner} 反射扫描这些 Bean，把方法转成 MCP 工具描述符。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface McpToolProvider {

    /** Server 名，作为工具全名前缀：{@code <server>.<tool>}。 */
    String name();

    String displayName() default "";

    String description() default "";

    /** 业务域，用于路由候选集分组。 */
    String domain() default "general";
}
