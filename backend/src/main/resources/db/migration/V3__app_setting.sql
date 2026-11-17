-- 系统设置：目前只放 AI 网关的运行时配置（Base URL / Key / 模型 / 超时）
-- 存库而不是写 env，是为了让护士长或管理员在界面上就能换 Key，不用重启服务。
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   VARCHAR(64)  NOT NULL,
    setting_value VARCHAR(2000) NULL,
    updated_by    BIGINT NULL,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
