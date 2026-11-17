package com.carerelay.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT 配置。secret 必须 ≥ 32 字节，生产环境用环境变量 CR_JWT_SECRET 覆盖。
 */
@ConfigurationProperties(prefix = "cr.jwt")
public record JwtProperties(
        @DefaultValue("dev-only-secret-please-override-0123456789") String secret,
        @DefaultValue("72") long ttlHours) {
}
