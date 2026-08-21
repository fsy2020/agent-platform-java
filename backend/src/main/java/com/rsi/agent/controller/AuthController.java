package com.rsi.agent.controller;

import com.rsi.agent.annotation.CurrentUser;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.dto.PlatformDtos.LoginRequest;
import com.rsi.agent.dto.PlatformDtos.TokenDto;
import com.rsi.agent.dto.PlatformDtos.UserDto;
import com.rsi.agent.security.Identity;
import com.rsi.agent.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 认证与系统信息。 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final AgentProperties properties;

    public AuthController(AuthService authService, AgentProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/auth/login")
    public TokenDto login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** 当前用户 —— 身份由 {@code @CurrentUser} 解析器注入，控制器无需碰 Token。 */
    @GetMapping("/auth/me")
    public UserDto me(@CurrentUser Identity identity) {
        return new UserDto(identity.id(), identity.username(), identity.displayName(),
                identity.role(), identity.tenantId());
    }

    /** 健康检查：同时暴露当前运行模式，前端据此显示「LLM / 规则引擎降级」标识。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean enabled = properties.llm().enabled();
        return Map.of(
                "status", "ok",
                "app", "RSI Agent Platform (Spring Boot)",
                "llm_enabled", enabled,
                "primary_model", properties.llm().primaryModel(),
                "fallback_model", properties.llm().fallbackModel(),
                "mode", enabled ? "llm" : "rule-engine");
    }
}
