package com.carerelay.common;

/**
 * 业务异常：由全局异常处理器翻译成 {@link ApiResult}，不打印堆栈。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int code() {
        return code;
    }
}
