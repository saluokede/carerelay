package com.carerelay.conf;

import com.carerelay.auth.AuthInterceptor;
import com.carerelay.station.StationAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 两套接口并存：
 * <ul>
 *   <li>{@code /api/v1/**} —— 原版手机号体系的业务接口，走 {@link AuthInterceptor}；</li>
 *   <li>其余 {@code /api/**} —— 新版工作台接口，走 {@link StationAuthInterceptor}（用户名密码 + users 表）。</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final StationAuthInterceptor stationAuthInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, StationAuthInterceptor stationAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.stationAuthInterceptor = stationAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .order(1)
                .excludePathPatterns(
                        "/api/v1/health",
                        // 只有登录/注册是匿名接口；/api/v1/auth/me 必须带 token，不能整段放行
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/public/**",
                        "/api/v1/ai/ping");

        registry.addInterceptor(stationAuthInterceptor)
                .addPathPatterns("/api/**")
                .order(0)
                .excludePathPatterns(
                        "/api/v1/**",
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
