package com.rsi.agent.annotation;

import java.lang.annotation.*;

/**
 * 控制器形参上标注，直接注入当前登录用户。
 *
 * <p>由 {@code CurrentUserArgumentResolver} 实现，省去每个方法里
 * 解析 Token → 查库 → 判空 的重复代码。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
