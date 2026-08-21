package com.rsi.agent.security;

import com.rsi.agent.common.BizException;
import com.rsi.agent.common.ErrorCode;

/**
 * 请求级身份上下文。
 *
 * <p>用 ThreadLocal 承载，使切面、服务、控制器都能拿到当前身份，
 * 而不必把 Identity 一层层当参数传下去 —— 这是权限切面能做到「零侵入」的前提。
 */
public final class IdentityContext {

    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();

    private IdentityContext() {}

    public static void set(Identity identity) { HOLDER.set(identity); }

    public static void clear() { HOLDER.remove(); }

    public static Identity peek() { return HOLDER.get(); }

    /** 取当前身份，未登录直接抛 401。 */
    public static Identity require() {
        Identity identity = HOLDER.get();
        if (identity == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少访问令牌或令牌已过期");
        }
        return identity;
    }
}
