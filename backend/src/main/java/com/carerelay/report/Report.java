package com.carerelay.report;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 周报 / 30 天结局报告。同一老人同一类型同一周期只保留一份，重算即覆盖。 */
@Data
@TableName("report")
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;

    /** WEEKLY / MONTHLY / CLOSURE */
    private String type;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    /** 家属版：白话，三到四句，无术语 */
    private String familyMd;

    /** 医生版：指标表 + 事实清单，不含用药建议 */
    private String clinicalMd;

    private LocalDateTime createdAt;
}
