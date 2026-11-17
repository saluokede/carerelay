package com.carerelay.auth;

import com.carerelay.common.BizException;

/**
 * 请求级身份容器（ThreadLocal），由 AuthInterceptor 写入并在请求结束时清理。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(401, "请先登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
