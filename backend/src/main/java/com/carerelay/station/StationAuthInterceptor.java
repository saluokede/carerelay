package com.carerelay.station;

import com.carerelay.auth.JwtService;
import com.carerelay.common.BizException;
import com.carerelay.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 新版工作台鉴权：Authorization: Bearer &lt;token&gt;，token 由 {@link JwtService} 签发，
 * 角色与归属以数据库 users 表为准（停用账号立即失效）。
 */
@Component
public class StationAuthInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public StationAuthInterceptor(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            throw new BizException(401, "未登录或登录已失效");
        }
        CurrentUser token = jwtService.parse(authorization.substring(PREFIX.length()).trim());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, name, role, elder_id, phone, status FROM `users` WHERE id = ?", token.id());
        if (rows.isEmpty()) {
            throw new BizException(401, "未登录或登录已失效");
        }
        Map<String, Object> row = rows.get(0);
        if ("停用".equals(String.valueOf(row.get("status")))) {
            throw new BizException(403, "账号已停用，请联系管理员");
        }
        Object elderId = row.get("elder_id");
        StationContext.set(new StationUser(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")),
                String.valueOf(row.get("name")),
                String.valueOf(row.get("role")),
                elderId == null ? null : ((Number) elderId).longValue(),
                row.get("phone") == null ? null : String.valueOf(row.get("phone"))));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        StationContext.clear();
    }
}
