-- V4：计划会诊留言（医生看得到计划，但签发是护士的事；留言是他们唯一的协作通道）
CREATE TABLE plan_comment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_id     BIGINT       NOT NULL,
    elder_id    BIGINT       NOT NULL,
    author_id   BIGINT,
    author_role VARCHAR(16)  NOT NULL,
    author_name VARCHAR(64),
    content     VARCHAR(600) NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_comment_plan (plan_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
