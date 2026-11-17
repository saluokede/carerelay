package com.carerelay.admission;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一次住院。床位是"当前值"，这张表是"历史值"——每次住院一条。 */
@Data
@TableName("elder_admission")
public class ElderAdmission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;
    private String hospital;
    private String department;
    private String bedNo;

    private LocalDate admitDate;
    private LocalDate dischargeDate;

    /** 入院原因/诊断，选填 */
    private String reason;

    /** IN_HOSPITAL / DISCHARGED / VOID（录错作废） */
    private String status;

    /** 对应的出院记录 id（可空） */
    private Long dischargeRecordId;

    /** 作废原因 */
    private String voidReason;

    private LocalDateTime voidedAt;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
