package com.carerelay.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "请填写手机号") String phone,
        @NotBlank(message = "请填写密码") String password) {
}
