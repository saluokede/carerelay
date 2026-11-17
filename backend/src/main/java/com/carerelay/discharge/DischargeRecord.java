package com.carerelay.discharge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 出院小结的结构化结果。
 *
 * <p>JSON 列在 Java 侧按 String 存取：一是 MyBatis-Plus 不需要额外 TypeHandler，
 * 二是"原样保留模型的输出"比"反序列化成对象再存"更容易追溯问题。</p>
 */
@Data
@TableName("discharge_record")
public class DischargeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;

    private String hospital;
    private String department;
    private LocalDate admitDate;
    private LocalDate dischargeDate;

    private String diagnosisPrimary;
    private String diagnosesJson;
    private String surgeryJson;
    private String medicationsJson;
    private String tubesJson;
    private String woundJson;
    private String dietJson;
    private String activityJson;
    private String followupJson;
    private String redFlagsJson;

    /** 脱敏后的原文，用于复核时对照 */
    private String sourceText;
    private String sourceUrl;

    /** PENDING / PARSED / FAILED / MANUAL */
    private String extractStatus;
    private String extractConfidence;

    private Long createdBy;
    private LocalDateTime createdAt;
}
