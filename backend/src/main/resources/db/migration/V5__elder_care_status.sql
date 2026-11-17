-- V5：老人档案补"住院状态"与床位信息
-- 背景：出院老人会再次住院，人在医院时家里的打卡任务本来就不该催；
-- 同时护士交接需要知道当前床号、医院、科室与最近一次入院日期。
ALTER TABLE elder_profile
    ADD COLUMN care_status VARCHAR(16) NOT NULL DEFAULT 'HOME' COMMENT 'HOME 居家随访 / HOSPITALIZED 住院中 / CLOSED 已结案' AFTER nurse_id,
    ADD COLUMN bed_no VARCHAR(32) NULL COMMENT '床号（住院中才有意义）' AFTER care_status,
    ADD COLUMN hospital VARCHAR(64) NULL COMMENT '当前住院或最近出院医院' AFTER bed_no,
    ADD COLUMN department VARCHAR(64) NULL COMMENT '科室' AFTER hospital,
    ADD COLUMN admit_date DATE NULL COMMENT '最近一次入院日期' AFTER department;

UPDATE elder_profile SET care_status = 'HOME' WHERE care_status IS NULL OR care_status = '';
