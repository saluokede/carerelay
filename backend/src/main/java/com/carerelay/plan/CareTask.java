package com.carerelay.plan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日任务。由计划展开而来，家属端「今天做什么」读的就是这张表。 */
@Data
@TableName("care_task")
public class CareTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;
    private Long elderId;
    private LocalDate taskDate;

    /** MED / VITAL / REHAB / DIET / WOUND / SYMPTOM */
    private String type;

    private String title;
    private String detail;

    /** MORNING / NOON / EVENING / ANYTIME */
    private String timeSlot;

    /** NONE / PHOTO / TEXT / VOICE */
    private String evidence;

    /** PENDING / DONE / MISSED / SKIPPED */
    private String status;

    /** AI / MANUAL */
    private String source;

    private LocalDateTime createdAt;
}
