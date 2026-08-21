package com.rsi.agent.security;

import java.util.List;
import java.util.Set;

import com.rsi.agent.domain.UserEntity;

/**
 * 当前调用方身份。运行时的能力裁剪、工具权限校验、租户隔离都以它为准。
 */
public record Identity(
        String id,
        String username,
        String displayName,
        String role,
        String tenantId,
        Set<String> scopes
) {
    public static Identity of(UserEntity user) {
        return new Identity(user.getId(), user.getUsername(),
                user.getDisplayName().isBlank() ? user.getUsername() : user.getDisplayName(),
                user.getRole(), user.getTenantId(), ScopeRegistry.scopesOf(user.getRole()));
    }

    /** 是否持有全部所需 scope。admin 持有通配符。 */
    public boolean hasScopes(List<String> required) {
        if (required == null || required.isEmpty()) return true;
        if (scopes.contains("*")) return true;
        return scopes.containsAll(required);
    }

    public List<String> sortedScopes() {
        return scopes.stream().sorted().toList();
    }
}
