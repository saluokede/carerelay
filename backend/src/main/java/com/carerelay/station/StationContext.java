package com.carerelay.station;

import com.carerelay.common.BizException;

/**
 * 新版工作台请求级身份容器（ThreadLocal），由 {@link StationAuthInterceptor} 写入并在请求结束时清理。
 */
public final class StationContext {

    private static final ThreadLocal<StationUser> HOLDER = new ThreadLocal<>();

    private StationContext() {
    }

    public static void set(StationUser user) {
        HOLDER.set(user);
    }

    public static StationUser require() {
        StationUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(401, "未登录或登录已失效");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
