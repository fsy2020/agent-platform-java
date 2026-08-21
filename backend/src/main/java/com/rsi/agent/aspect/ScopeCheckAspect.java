package com.rsi.agent.aspect;

import java.util.ArrayList;
import java.util.List;

import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.security.Identity;
import com.rsi.agent.security.IdentityContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 权限切面：在方法执行前校验 {@code @RequiresScope} 声明的 scope。
 *
 * <p>Order 取 10 —— 要排在观测（100）与容错（200）之前，否则会出现
 * 「记录了一次不该发生的调用」或「对无权调用做了三次重试」；但<b>不能用
 * {@link Ordered#HIGHEST_PRECEDENCE}</b>：那会让本切面跑在 Spring 的
 * {@code ExposeInvocationInterceptor} 之前，导致 {@code @within} 这类
 * 需要当前 MethodInvocation 的切点直接抛 IllegalStateException。
 */
@Aspect
@Component
@Order(10)
public class ScopeCheckAspect {

    private static final Logger log = LoggerFactory.getLogger(ScopeCheckAspect.class);

    /**
     * 同时匹配方法级与类级注解。用 @Before 而非 @Around：
     * 校验只需要「放行或抛出」，不需要包裹返回值。
     */
    @Before("@annotation(com.rsi.agent.annotation.RequiresScope) "
            + "|| @within(com.rsi.agent.annotation.RequiresScope)")
    public void check(org.aspectj.lang.JoinPoint joinPoint) {
        var signature = (org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature();

        List<String> required = new ArrayList<>();
        // getDeclaringType() 返回原始 Class，先收窄成 Class<?> 才能拿到泛型化的 getAnnotation
        Class<?> declaringType = signature.getDeclaringType();
        RequiresScope onType = declaringType.getAnnotation(RequiresScope.class);
        if (onType != null) required.addAll(List.of(onType.value()));
        RequiresScope onMethod = signature.getMethod().getAnnotation(RequiresScope.class);
        if (onMethod != null) required.addAll(List.of(onMethod.value()));

        Identity identity = IdentityContext.require();
        if (!identity.hasScopes(required)) {
            log.warn("权限拒绝 user={} role={} 需要={} 实际={}",
                    identity.username(), identity.role(), required, identity.sortedScopes());
            throw BizException.forbidden("当前角色缺少权限：" + String.join(", ", required));
        }
    }
}
