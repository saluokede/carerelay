-- 随访会话需要保存多轮对话原文，并记录已问到第几题（本地脚本模式靠它推进）
ALTER TABLE followup_session
    ADD COLUMN transcript_json JSON NULL COMMENT '多轮对话原文 [{role,content,at}]' AFTER duration_sec,
    ADD COLUMN question_index INT NOT NULL DEFAULT 0 COMMENT '本地脚本模式已问到的题号' AFTER transcript_json,
    ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'SCRIPT' COMMENT 'MODEL/SCRIPT' AFTER question_index;
