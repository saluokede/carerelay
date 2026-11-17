package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.user.AuthService;
import com.carerelay.user.dto.AuthResponse;
import com.carerelay.user.dto.LoginRequest;
import com.carerelay.user.dto.RegisterRequest;
import com.carerelay.user.dto.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "账号")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "机构注册，注册即登录")
    @PostMapping("/register")
    public ApiResult<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(authService.register(request));
    }

    @Operation(summary = "手机号 + 密码登录")
    @PostMapping("/login")
    public ApiResult<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(authService.login(request));
    }

    @Operation(summary = "当前登录人信息")
    @GetMapping("/me")
    public ApiResult<UserView> me() {
        return ApiResult.ok(authService.findById(UserContext.require().id()));
    }
}
