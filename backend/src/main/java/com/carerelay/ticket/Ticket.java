package com.carerelay.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单：预警升级成"必须有人处置"的队列项，家庭医生在这里闭环。 */
@Data
@TableName("ticket")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long elderId;
    private Long alertId;

    /** ALERT / FOLLOWUP / MANUAL */
    private String source;

    /** MEDIUM / HIGH */
    private String level;

    private String reason;

    /** PENDING / PROCESSING / DONE */
    private String status;

    private Long handlerId;
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}
