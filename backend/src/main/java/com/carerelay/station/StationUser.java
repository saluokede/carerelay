package com.carerelay.station;

/**
 * 新版工作台（六角色）登录用户。
 *
 * @param id       账号主键
 * @param username 登录账号
 * @param name     姓名
 * @param role     hospital / family_doctor / nurse / family / elder / admin
 * @param elderId  家属、老人本人绑定的老人档案，可为空
 * @param phone    联系电话
 */
public record StationUser(long id, String username, String name, String role, Long elderId, String phone) {

    public boolean isFamilyLike() {
        return "family".equals(role) || "elder".equals(role);
    }
}
