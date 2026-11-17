package com.carerelay.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 调用记录：既用于频率限制，也是答辩时「过程可验证」的原始证据。
 * 只保存任务输入与结构化输出，不保存密钥、不保存老人真实信息。
 */
@Data
@TableName("ai_request")
public class AiRequestRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String scene;

    /** MODEL 真实模型 / LOCAL 本地演示生成 */
    private String mode;

    private String model;
    private String inputJson;
    private String outputJson;

    /** SUCCESS / FAILED */
    private String status;

    private String error;
    private Integer latencyMs;

    /** 1 = 已被人工采用为草稿 */
    private Integer adopted;

    private LocalDateTime createdAt;
}
