-- =====================================================================
-- 医养接力站 · V1 初始化
-- 设计原则（与 docs/01-AI运行方向说明.md 一致）：
--   1) 结构化优先：AI 产出先落 JSON 字段，再映射成任务/预警等业务行；
--   2) 数值归代码：评分、阈值判断结果落在独立的数值列，不塞进文本；
--   3) 全过程留痕：ai_request 记录每次模型调用，审计表记录人的动作。
-- 字符集统一 utf8mb4，存储引擎统一 InnoDB。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 账号与基础设施
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    phone         VARCHAR(32)  NOT NULL COMMENT '登录手机号',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 散列，绝不存明文',
    nickname      VARCHAR(64)  NOT NULL DEFAULT '',
    role          VARCHAR(16)  NOT NULL DEFAULT 'NURSE' COMMENT 'ADMIN/NURSE/DOCTOR/FAMILY',
    org_name      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '所属社区卫生服务中心',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '后台账号（医护与管理员）';

CREATE TABLE audit_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NULL,
    user_name  VARCHAR(64)  NOT NULL DEFAULT '',
    action     VARCHAR(64)  NOT NULL COMMENT '如 PLAN_ISSUE/TICKET_CLOSE',
    entity     VARCHAR(32)  NOT NULL DEFAULT '',
    entity_id  BIGINT       NULL,
    detail     VARCHAR(1000) NOT NULL DEFAULT '',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_entity (entity, entity_id),
    KEY idx_audit_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '关键操作审计';

CREATE TABLE ai_request (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    scene       VARCHAR(32)  NOT NULL COMMENT 'EXTRACT/PLAN/CHECKIN/FOLLOWUP/RAG/REPORT',
    mode        VARCHAR(16)  NOT NULL DEFAULT 'MODEL' COMMENT 'MODEL 真实调用 / LOCAL 模板兜底',
    model       VARCHAR(64)  NOT NULL DEFAULT '',
    input_json  MEDIUMTEXT   NULL,
    output_json MEDIUMTEXT   NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
    error       VARCHAR(500) NOT NULL DEFAULT '',
    latency_ms  INT          NOT NULL DEFAULT 0,
    adopted     TINYINT      NOT NULL DEFAULT 0 COMMENT '人工是否采纳',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_scene_time (scene, created_at),
    KEY idx_ai_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每次 AI 调用的输入输出与耗时';

-- ---------------------------------------------------------------------
-- 交接环：老人档案 → 出院小结 → 风险分层 → 照护计划
-- ---------------------------------------------------------------------
CREATE TABLE elder_profile (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)  NOT NULL COMMENT '站内编号，如 CR-0001',
    name           VARCHAR(64)  NOT NULL,
    gender         VARCHAR(8)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'MALE/FEMALE/UNKNOWN',
    birth_date     DATE         NULL,
    phone          VARCHAR(32)  NOT NULL DEFAULT '',
    address        VARCHAR(255) NOT NULL DEFAULT '',
    living_status  VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ALONE/SPOUSE/CHILDREN/FACILITY',
    caregiver_name VARCHAR(64)  NOT NULL DEFAULT '',
    caregiver_phone VARCHAR(32) NOT NULL DEFAULT '',
    chronic_json   JSON         NULL COMMENT '慢病列表',
    allergy_json   JSON         NULL COMMENT '过敏史',
    nurse_id       BIGINT       NULL COMMENT '责任护士 app_user.id',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elder_code (code),
    KEY idx_elder_nurse (nurse_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '出院老人档案';

CREATE TABLE discharge_record (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    elder_id          BIGINT       NOT NULL,
    hospital          VARCHAR(64)  NOT NULL DEFAULT '',
    department        VARCHAR(64)  NOT NULL DEFAULT '',
    admit_date        DATE         NULL,
    discharge_date    DATE         NULL,
    diagnosis_primary VARCHAR(255) NOT NULL DEFAULT '',
    diagnoses_json    JSON         NULL,
    surgery_json      JSON         NULL,
    medications_json  JSON         NULL COMMENT '药名/剂量/频次/疗程，剂量由原文抽取，不由模型杜撰',
    tubes_json        JSON         NULL COMMENT '管路：尿管/胃管/引流管等',
    wound_json        JSON         NULL,
    diet_json         JSON         NULL,
    activity_json     JSON         NULL COMMENT '活动限制与康复禁忌',
    followup_json     JSON         NULL COMMENT '复诊时间/科室/联系人',
    red_flags_json    JSON         NULL COMMENT '需要立即就医的红线症状',
    source_text       MEDIUMTEXT   NULL COMMENT '出院小结原文（脱敏后）',
    source_url        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '拍照/PDF 上传地址',
    extract_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSED/FAILED/MANUAL',
    extract_confidence JSON        NULL COMMENT '逐字段置信度',
    created_by        BIGINT       NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_record_elder (elder_id, discharge_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '出院小结（结构化后）';

CREATE TABLE risk_assessment (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    elder_id     BIGINT      NOT NULL,
    record_id    BIGINT      NULL,
    score        INT         NOT NULL DEFAULT 0 COMMENT '0-14，代码计算',
    level        VARCHAR(8)  NOT NULL DEFAULT 'LOW' COMMENT 'LOW/MEDIUM/HIGH',
    factors_json JSON        NULL COMMENT '命中项：code/label/score/evidence',
    explanation  TEXT        NULL COMMENT '模型生成的白话解释，不参与打分',
    assessor     VARCHAR(16) NOT NULL DEFAULT 'RULE' COMMENT 'RULE/AI/HUMAN',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_risk_elder (elder_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '风险评分卡结果';

CREATE TABLE care_plan (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    elder_id      BIGINT      NOT NULL,
    assessment_id BIGINT      NULL,
    version       INT         NOT NULL DEFAULT 1,
    status        VARCHAR(8)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ISSUED/CLOSED',
    start_date    DATE        NULL,
    end_date      DATE        NULL,
    goals_json    JSON        NULL,
    red_flags_json JSON       NULL,
    followup_json JSON        NULL COMMENT '随访频次与渠道',
    ai_summary    TEXT        NULL COMMENT 'AI 生成的计划说明，供护士审核时快速阅读',
    issued_by     BIGINT      NULL,
    issued_at     DATETIME    NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_plan_elder (elder_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '30 天康复照护计划（人在环签发）';

CREATE TABLE care_task (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id   BIGINT       NOT NULL,
    elder_id  BIGINT       NOT NULL,
    task_date DATE         NOT NULL,
    type      VARCHAR(16)  NOT NULL COMMENT 'MED/VITAL/REHAB/DIET/WOUND/SYMPTOM',
    title     VARCHAR(128) NOT NULL,
    detail    VARCHAR(512) NOT NULL DEFAULT '',
    time_slot VARCHAR(16)  NOT NULL DEFAULT 'MORNING' COMMENT 'MORNING/NOON/EVENING/ANYTIME',
    evidence  VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PHOTO/TEXT/VOICE',
    status    VARCHAR(8)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE/MISSED/SKIPPED',
    source    VARCHAR(8)   NOT NULL DEFAULT 'AI' COMMENT 'AI/MANUAL',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_elder_date (elder_id, task_date),
    KEY idx_task_plan (plan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每日任务（家属端"今天做什么"）';

-- ---------------------------------------------------------------------
-- 执行环：打卡 → 判读 → 预警
-- ---------------------------------------------------------------------
CREATE TABLE checkin (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    task_id      BIGINT       NULL,
    elder_id     BIGINT       NOT NULL,
    checkin_date DATE         NOT NULL,
    value_json   JSON         NULL COMMENT '血压/血糖/用药等结构化值',
    photo_url    VARCHAR(255) NOT NULL DEFAULT '',
    voice_url    VARCHAR(255) NOT NULL DEFAULT '',
    voice_text   VARCHAR(1000) NOT NULL DEFAULT '' COMMENT 'ASR 转写',
    status       VARCHAR(8)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/ABNORMAL',
    judge_source VARCHAR(8)   NOT NULL DEFAULT 'RULE' COMMENT 'RULE/MODEL',
    judge_note   VARCHAR(255) NOT NULL DEFAULT '',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_checkin_elder_date (elder_id, checkin_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每日打卡记录';

CREATE TABLE alert_event (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    elder_id   BIGINT       NOT NULL,
    source     VARCHAR(16)  NOT NULL DEFAULT 'CHECKIN' COMMENT 'CHECKIN/FOLLOWUP/MANUAL',
    rule_code  VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '命中规则，如 BP_HIGH_SYS180',
    level      VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM' COMMENT 'MEDIUM/HIGH',
    title      VARCHAR(128) NOT NULL,
    detail     VARCHAR(512) NOT NULL DEFAULT '',
    status     VARCHAR(8)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_by  BIGINT       NULL,
    closed_at  DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_alert_elder (elder_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '预警事件（规则命中的产物）';

CREATE TABLE ticket (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    elder_id   BIGINT       NOT NULL,
    alert_id   BIGINT       NULL,
    source     VARCHAR(16)  NOT NULL DEFAULT 'ALERT' COMMENT 'ALERT/FOLLOWUP/MANUAL',
    level      VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM',
    reason     VARCHAR(255) NOT NULL DEFAULT '',
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE',
    handler_id BIGINT       NULL,
    note       VARCHAR(512) NOT NULL DEFAULT '',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_ticket_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '工单：预警升级到人的处置队列';

-- ---------------------------------------------------------------------
-- 随访环：会话 → 结构化记录
-- ---------------------------------------------------------------------
CREATE TABLE followup_session (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    elder_id      BIGINT      NOT NULL,
    channel       VARCHAR(8)  NOT NULL DEFAULT 'PHONE' COMMENT 'PHONE/APP/CHAT',
    planned_date  DATE        NULL,
    status        VARCHAR(8)  NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED/DONE/MISSED',
    started_at    DATETIME    NULL,
    ended_at      DATETIME    NULL,
    duration_sec  INT         NOT NULL DEFAULT 0,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_elder (elder_id, planned_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '随访会话';

CREATE TABLE followup_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    session_id BIGINT       NOT NULL,
    elder_id   BIGINT       NOT NULL,
    payload_json JSON       NULL COMMENT '固定脚本字段：症状/用药/跌倒/伤口/情绪/需求',
    summary    TEXT         NULL COMMENT '给医护看的三行摘要',
    red_flag   TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_followup_elder (elder_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '随访结构化结果';

-- ---------------------------------------------------------------------
-- 复盘环：知识库与报告
-- ---------------------------------------------------------------------
CREATE TABLE kb_doc (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(200) NOT NULL,
    source     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '指南/院内宣教/社区资源',
    category   VARCHAR(32)  NOT NULL DEFAULT 'GUIDE',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识库文档';

CREATE TABLE kb_chunk (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    doc_id     BIGINT      NOT NULL,
    seq        INT         NOT NULL DEFAULT 1,
    content    TEXT        NOT NULL,
    char_count INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kb_doc (doc_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识库切片，RAG 检索单元';

CREATE TABLE report (
    id           BIGINT     NOT NULL AUTO_INCREMENT,
    elder_id     BIGINT     NOT NULL,
    type         VARCHAR(8) NOT NULL DEFAULT 'WEEKLY' COMMENT 'WEEKLY/MONTHLY/CLOSURE',
    period_start DATE       NULL,
    period_end   DATE       NULL,
    family_md    MEDIUMTEXT NULL COMMENT '给家属的白话版',
    clinical_md  MEDIUMTEXT NULL COMMENT '给医护的要点版',
    created_at   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_report_elder (elder_id, type, period_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '周报与 30 天结局复盘';
