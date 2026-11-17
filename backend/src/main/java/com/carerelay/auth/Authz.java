package com.carerelay.auth;

import com.carerelay.common.BizException;

/**
 * 角色校验工具：服务端二次校验，不信任前端隐藏按钮（测试 T09 / T10）。
 */
public final class Authz {

    private Authz() {
    }

    public static CurrentUser staff() {
        CurrentUser user = UserContext.require();
        if (!"ADMIN".equals(user.role()) && !"STAFF".equals(user.role())) {
            throw new BizException(403, "当前账号没有后台操作权限");
        }
        return user;
    }

    public static CurrentUser admin() {
        CurrentUser user = staff();
        if (!user.isAdmin()) {
            throw new BizException(403, "只有管理员可以执行该操作");
        }
        return user;
    }
}
