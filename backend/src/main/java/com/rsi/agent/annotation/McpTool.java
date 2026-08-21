package com.rsi.agent.annotation;

import java.lang.annotation.*;

/**
 * 把一个普通 Java 方法暴露为 MCP 工具。
 *
 * <p>工具的 JSON Schema 不需要手写：启动时通过反射读取方法的形参名、类型和
 * {@link ToolParam} 注解自动生成。这正是「150+ 内部 REST API 标准化封装为 MCP 工具」
 * 的落地方式 —— 接入方只写业务方法，schema、注册、鉴权、观测全部由平台生成。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpTool {

    /** 工具名，留空则取方法名。 */
    String value() default "";

    /** 工具描述 —— 模型选工具的主要依据，必须写清楚用途。 */
    String description();

    /** low / medium / high；high 或写操作会在执行前触发人工审批。 */
    String risk() default "low";

    /** 是否只读。写操作纳入 HITL 审批与防幻觉校验。 */
    boolean readOnly() default true;

    /** 调用该工具所需的业务 scope，由 ScopeCheckAspect 在执行前校验。 */
    String[] scopes() default {};
}
