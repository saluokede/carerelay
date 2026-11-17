-- V7：住院记录补三件事
-- 1) 和出院小结建立关联（哪一次住院对应哪一份出院记录）
-- 2) 支持"作废"（录错了不能物理删，要留痕）
-- 3) 记录修改时间
ALTER TABLE elder_admission
    ADD COLUMN discharge_record_id BIGINT NULL COMMENT '对应的 discharge_record.id' AFTER status,
    ADD COLUMN void_reason VARCHAR(200) NULL COMMENT '作废原因' AFTER discharge_record_id,
    ADD COLUMN voided_at DATETIME NULL COMMENT '作废时间' AFTER void_reason;

ALTER TABLE elder_admission
    ADD KEY idx_admission_record (discharge_record_id);
