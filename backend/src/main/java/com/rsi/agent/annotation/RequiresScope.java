package com.rsi.agent.annotation;

import java.lang.annotation.*;

/**
 * 声明式权限：方法执行前校验当前身份是否持有全部所需 scope。
 *
 * <p>由 {@code ScopeCheckAspect} 以环绕通知实现。控制器里因此看不到任何
 * if (role != admin) throw 的样板代码，权限规则集中且可审计。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresScope {

    /** 所需 scope，全部满足才放行。 */
    String[] value();
}
