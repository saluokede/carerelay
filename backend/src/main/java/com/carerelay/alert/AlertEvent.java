package com.carerelay.alert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预警事件。规则命中或模型判定红旗后落一条，护士端「待处理预警」读的就是它。
 * rule_code 是给护士看的"哪条线被跨了"，不是给人读的句子；人话在 detail 里。
 */
@Data
@TableName("alert_event")
public class AlertEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;

    /** CHECKIN / FOLLOWUP / MANUAL */
    private String source;

    private String ruleCode;

    /** MEDIUM / HIGH */
    private String level;

    private String title;
    private String detail;

    /** OPEN / CLOSED */
    private String status;

    private LocalDateTime createdAt;
    private Long closedBy;
    private LocalDateTime closedAt;
}
