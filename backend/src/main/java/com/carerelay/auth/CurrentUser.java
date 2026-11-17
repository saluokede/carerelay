package com.carerelay.auth;

/**
 * 当前请求的登录身份，由 JWT 解出。
 */
public record CurrentUser(long id, String role, Long orgId) {

    public boolean isAdmin() {
        return "ADMIN".equals(role) || "ORG_ADMIN".equals(role);
    }
}
