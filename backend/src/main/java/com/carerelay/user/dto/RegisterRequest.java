package com.carerelay.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "请填写昵称")
        @Size(max = 32, message = "昵称最多 32 个字")
        String nickname,

        @NotBlank(message = "请填写手机号")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不对")
        String phone,

        @NotBlank(message = "请填写密码")
        @Size(min = 8, max = 32, message = "密码长度 8—32 位")
        String password) {
}
