-- V6：每次住院一条记录（床位只是"当前值"，历史要留下）
CREATE TABLE IF NOT EXISTS elder_admission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    elder_id BIGINT NOT NULL,
    hospital VARCHAR(64) NULL,
    department VARCHAR(64) NULL,
    bed_no VARCHAR(32) NULL,
    admit_date DATE NOT NULL,
    discharge_date DATE NULL,
    reason VARCHAR(200) NULL COMMENT '入院原因/诊断，可选',
    status VARCHAR(16) NOT NULL DEFAULT 'IN_HOSPITAL' COMMENT 'IN_HOSPITAL 在院 / DISCHARGED 已出院',
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_admission_elder (elder_id, admit_date),
    KEY idx_admission_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
