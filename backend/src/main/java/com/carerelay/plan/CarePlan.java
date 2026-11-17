package com.carerelay.plan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 30 天照护计划。DRAFT → ISSUED 由护士签发，签发后不可被模型覆盖，只能人工改版本。 */
@Data
@TableName("care_plan")
public class CarePlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;
    private Long assessmentId;
    private Integer version;

    /** DRAFT / ISSUED / CLOSED */
    private String status;

    private LocalDate startDate;
    private LocalDate endDate;

    private String goalsJson;
    private String redFlagsJson;
    private String followupJson;

    /** AI 写的计划总述，供护士审核时快速读 */
    private String aiSummary;

    private Long issuedBy;
    private LocalDateTime issuedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
