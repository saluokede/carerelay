package com.carerelay.followup;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 随访会话：按计划的随访间隔生成，一条会话对应一次电话/上门。 */
@Data
@TableName("followup_session")
public class FollowupSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;

    /** PHONE / APP / CHAT */
    private String channel;

    private LocalDate plannedDate;

    /** PLANNED / DONE / MISSED */
    private String status;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationSec;

    /** 多轮对话原文 JSON：[{role,content,at}] */
    private String transcriptJson;

    /** 本地脚本模式已问到的题号 */
    private Integer questionIndex;

    /** MODEL / SCRIPT */
    private String mode;

    private LocalDateTime createdAt;
}
