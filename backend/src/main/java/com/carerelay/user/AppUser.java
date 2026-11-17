package com.carerelay.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号（管理员 / 社区护士 / 家庭医生 / 家属）。演示环境不开放自助注册，账号由种子数据初始化。
 */
@Data
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    /** BCrypt 散列 */
    private String passwordHash;

    private String nickname;

    /** ADMIN / NURSE / DOCTOR / FAMILY */
    private String role;

    /** 所属社区卫生服务中心，为多机构隔离预留 */
    private String orgName;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
