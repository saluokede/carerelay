package com.carerelay.followup;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 随访结构化结果：固定脚本字段 + 给医护看的三行摘要 + 红旗位。 */
@Data
@TableName("followup_record")
public class FollowupRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private Long elderId;

    /** {symptoms:[], medication:{}, falls, wound, mood, needs} */
    private String payloadJson;

    /** 给医护看的三行摘要 */
    private String summary;

    /** 1 = 命中红旗，需升级 */
    private Integer redFlag;

    private LocalDateTime createdAt;
}
