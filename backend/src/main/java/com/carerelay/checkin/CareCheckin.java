package com.carerelay.checkin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日打卡。数值打卡由规则判读（judgeSource=RULE），文字/语音由模型判读（judgeSource=MODEL）。 */
@Data
@TableName("checkin")
public class CareCheckin {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Long elderId;
    private LocalDate checkinDate;

    /** 结构化值，如 {"kind":"BP","systolic":168,"diastolic":96} */
    private String valueJson;

    private String photoUrl;
    private String voiceUrl;
    private String voiceText;

    /** NORMAL / ABNORMAL */
    private String status;

    /** RULE / MODEL */
    private String judgeSource;

    private String judgeNote;
    private LocalDateTime createdAt;
}
