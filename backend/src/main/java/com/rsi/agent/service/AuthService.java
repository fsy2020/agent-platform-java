package com.rsi.agent.service;

import java.util.Map;

import com.rsi.agent.common.BizException;
import com.rsi.agent.common.ErrorCode;
import com.rsi.agent.domain.UserEntity;
import com.rsi.agent.dto.PlatformDtos.LoginRequest;
import com.rsi.agent.dto.PlatformDtos.TokenDto;
import com.rsi.agent.repository.UserRepository;
import com.rsi.agent.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录与令牌签发。 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public TokenDto login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));

        if (!encoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!user.isActive()) {
            throw BizException.forbidden("账号已停用");
        }

        String token = jwtService.issue(user.getId(),
                Map.of("role", user.getRole(), "tenant", user.getTenantId()));
        return TokenDto.of(token, user);
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}
