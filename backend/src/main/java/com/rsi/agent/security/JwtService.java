package com.rsi.agent.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import com.rsi.agent.config.AgentProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

/** JWT 签发与校验。 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(AgentProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = properties.jwt().expireMinutes();
    }

    public String issue(String subject, Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 subject（用户 ID）；无效返回 null，由调用方决定如何处理。 */
    public String verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
