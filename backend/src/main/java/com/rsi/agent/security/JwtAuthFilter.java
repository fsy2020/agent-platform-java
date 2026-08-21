package com.rsi.agent.security;

import java.io.IOException;

import com.rsi.agent.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证过滤器：解析 Bearer Token，把身份放进 {@link IdentityContext}。
 *
 * <p>只做「认证」（你是谁），不做「授权」（你能干什么）—— 授权交给
 * {@code @RequiresScope} + 切面，两者职责分离，权限规则集中在注解上可读可审计。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String userId = jwtService.verify(header.substring(7).trim());
                if (userId != null) {
                    userRepository.findById(userId)
                            .filter(com.rsi.agent.domain.UserEntity::isActive)
                            .map(Identity::of)
                            .ifPresent(IdentityContext::set);
                }
            }
            chain.doFilter(request, response);
        } finally {
            // ThreadLocal 必须清理，否则线程复用会造成身份串号
            IdentityContext.clear();
        }
    }
}
