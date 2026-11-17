package com.carerelay.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String userName;

    /** CREATE_ACTIVITY / SUBMIT / APPROVE / REJECT / WITHDRAW / CANCEL / ARCHIVE / REGISTER / CANCEL_REGISTRATION / CHECK_IN / EXPORT_STATS / AI_ADOPT */
    private String action;

    private String entity;
    private Long entityId;

    /** 变更摘要，不含隐私内容 */
    private String detail;

    private LocalDateTime createdAt;
}
