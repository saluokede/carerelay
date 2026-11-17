package com.carerelay.auth;

import com.carerelay.common.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlHours;

    public JwtService(JwtProperties properties) {
        byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("cr.jwt.secret 至少需要 32 字节，当前 " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlHours = properties.ttlHours();
    }

    public String issue(long userId, String role, Long orgId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(Long.toString(userId))
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(ttlHours))));
        if (orgId != null) {
            builder.claim("orgId", orgId);
        }
        return builder.signWith(key).compact();
    }

    public CurrentUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object orgId = claims.get("orgId");
            return new CurrentUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("role", String.class),
                    orgId instanceof Number number ? number.longValue() : null);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
    }

    public long ttlHours() {
        return ttlHours;
    }
}
