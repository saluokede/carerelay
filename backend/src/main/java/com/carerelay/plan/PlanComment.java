package com.carerelay.plan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 计划会诊留言：医生不能签发计划，但可以留意见；护士按意见改草案。
 */
@Getter
@Setter
@TableName("plan_comment")
public class PlanComment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Long elderId;
    private Long authorId;
    private String authorRole;
    private String authorName;
    private String content;
    private LocalDateTime createdAt;
}
