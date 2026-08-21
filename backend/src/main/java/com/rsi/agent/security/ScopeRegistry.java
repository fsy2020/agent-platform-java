package com.rsi.agent.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → scope 映射。
 *
 * <p>平台里有两类 scope：
 * <ul>
 *   <li><b>平台管理 scope</b>（mcp:write / agent:write …）：控制谁能改注册中心</li>
 *   <li><b>业务数据 scope</b>（hr:read / fin:write …）：控制 Agent 运行时能碰哪些工具</li>
 * </ul>
 * 两者共用一套 role 映射，因此「注册中心的操作权限」与「Agent 的数据权限」
 * 天然一致，不会出现能改配置却调不动工具的割裂。
 */
public final class ScopeRegistry {

    private ScopeRegistry() {}

    private static final Map<String, Set<String>> ROLE_SCOPES = Map.of(
            "admin", Set.of("*"),
            "developer", Set.of(
                    "mcp:read", "mcp:write", "skill:read", "skill:write",
                    "agent:read", "agent:write", "chat:invoke", "approval:decide",
                    "trace:read", "eval:read", "eval:write",
                    "hr:read", "hr:write", "it:read", "it:write",
                    "crm:read", "crm:write", "fin:read", "fin:write"),
            "viewer", Set.of(
                    "mcp:read", "skill:read", "agent:read", "trace:read", "eval:read",
                    "hr:read", "it:read", "crm:read", "fin:read"));

    public static Set<String> scopesOf(String role) {
        return ROLE_SCOPES.getOrDefault(role, Set.of());
    }

    public static boolean allows(String role, List<String> required) {
        if (required == null || required.isEmpty()) return true;
        Set<String> granted = scopesOf(role);
        return granted.contains("*") || granted.containsAll(required);
    }
}
