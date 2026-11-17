package com.carerelay.auth;

import com.carerelay.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一鉴权：Authorization: Bearer &lt;token&gt;，白名单路径在 WebConfig 里排除。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            throw new BizException(401, "请先登录");
        }
        UserContext.set(jwtService.parse(authorization.substring(PREFIX.length()).trim()));
        // 登录态确认后再做角色边界校验：家属可以打卡、看周报，但不能签发计划或关预警。
        RoleRules.check(request.getMethod(), request.getRequestURI(), UserContext.require());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
