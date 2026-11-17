package com.carerelay.common;

/**
 * 统一返回体：code = 0 表示成功，其余为业务错误码（400 参数错 / 401 未登录 / 403 无权限 / 404 不存在 / 409 冲突 / 500 服务端错）。
 */
public record ApiResult<T>(int code, String message, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "ok", data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(0, "ok", null);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
