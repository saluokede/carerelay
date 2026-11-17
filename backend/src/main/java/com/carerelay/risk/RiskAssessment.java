package com.carerelay.risk;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风险评分卡结果。
 *
 * <p>注意分工：score/level/factors 全部由代码算出（可复算、可写测试），
 * explanation 才是模型写的白话解释。这样即使模型胡说，分数也不会变。</p>
 */
@Data
@TableName("risk_assessment")
public class RiskAssessment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;
    private Long recordId;

    private Integer score;

    /** LOW / MEDIUM / HIGH */
    private String level;

    private String factorsJson;
    private String explanation;

    /** RULE / AI / HUMAN */
    private String assessor;

    private LocalDateTime createdAt;
}
