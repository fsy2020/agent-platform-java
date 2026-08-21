package com.rsi.agent.annotation;

import java.lang.annotation.*;

/**
 * 工具入参的描述元数据。
 *
 * <p>参数说明会进入生成的 JSON Schema，并参与路由匹配打分 —— 例如
 * “费用类型：差旅/餐饮/办公用品” 里的业务词能显著提升规则引擎的命中准确率。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /** 参数描述。 */
    String value() default "";

    /** 是否必填。缺失必填项时平台转澄清，而不是带着空值硬调。 */
    boolean required() default false;
}
