package com.carerelay.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统设置（键值对）。目前只放 AI 网关的四项。 */
@Data
@TableName("app_setting")
public class AppSetting {

    @TableId(type = IdType.INPUT)
    private String settingKey;

    private String settingValue;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
