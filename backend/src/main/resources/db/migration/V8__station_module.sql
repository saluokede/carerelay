-- 医养接力站 · 新版工作台模块（六角色 · 菜单驱动）
-- 说明：本模块与原有 /api/v1 业务表完全隔离，自成一套 10 张表，
--       供指导老师新版前端（public/ 静态页）的 /api/* 接口使用。
--       表名与会话字段沿用新版接口约定（snake_case），前端无需改字段名。

CREATE TABLE IF NOT EXISTS `users` (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(64) UNIQUE NOT NULL,
  password    VARCHAR(128) NOT NULL COMMENT '演示用明文，生产须哈希加盐',
  name        VARCHAR(64) NOT NULL,
  role        VARCHAR(32) NOT NULL COMMENT 'hospital / family_doctor / nurse / family / elder / admin',
  elder_id    BIGINT NULL COMMENT '家属/老人账号绑定的老人档案',
  phone       VARCHAR(32) NULL,
  status      VARCHAR(16) DEFAULT '启用',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS elders (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64) NOT NULL,
  age         INT NULL,
  gender      VARCHAR(8) NULL,
  diag        VARCHAR(255) NOT NULL COMMENT '出院诊断',
  risk        VARCHAR(8) NOT NULL DEFAULT '低',
  risk_score  INT DEFAULT 0,
  rate        INT DEFAULT 0 COMMENT '康复任务完成率 %',
  alerts      INT DEFAULT 0 COMMENT '未闭环预警数',
  `day`       INT DEFAULT 0 COMMENT '出院第几天',
  contact     VARCHAR(32) NULL,
  address     VARCHAR(255) NULL,
  living      VARCHAR(32) DEFAULT '与子女同住',
  emergency_contact VARCHAR(64) NULL,
  emergency_phone   VARCHAR(32) NULL,
  status      VARCHAR(16) DEFAULT '随访中',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS plans (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  elder_id    BIGINT NOT NULL,
  `type`      VARCHAR(16) NOT NULL COMMENT '康复 / 用药 / 饮食',
  content     TEXT NOT NULL,
  ai_draft    TINYINT DEFAULT 1,
  status      VARCHAR(16) DEFAULT '待签发',
  issued_by   VARCHAR(64) NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_plans_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS followups (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  elder_id    BIGINT NOT NULL,
  `type`      VARCHAR(32) NOT NULL,
  scheduled_at VARCHAR(64) NULL,
  address     VARCHAR(255) NULL,
  note        TEXT NULL,
  status      VARCHAR(16) DEFAULT '待执行',
  reporter    VARCHAR(64) NULL,
  review_status VARCHAR(16) DEFAULT '待审核',
  review_note TEXT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_fu_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS messages (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  elder_id    BIGINT NOT NULL,
  sender_role VARCHAR(32) NOT NULL,
  sender_name VARCHAR(64) NOT NULL,
  content     TEXT NOT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_msg_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS announcements (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(255) NOT NULL,
  content     TEXT NOT NULL,
  publisher   VARCHAR(64) NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS revisits (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  elder_id    BIGINT NOT NULL,
  plan_at     VARCHAR(64) NULL,
  reason      VARCHAR(255) NULL,
  status      VARCHAR(16) DEFAULT '待复诊',
  doctor      VARCHAR(64) NULL,
  conclusion  TEXT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rv_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alerts (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  elder_id    BIGINT NOT NULL,
  level       VARCHAR(8) NOT NULL,
  reason      VARCHAR(255) NOT NULL,
  status      VARCHAR(16) DEFAULT '未闭环',
  conclusion  TEXT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_alert_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS records (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  elder_id    BIGINT NOT NULL,
  category    VARCHAR(16) NOT NULL,
  title       VARCHAR(255) NOT NULL,
  detail      TEXT NULL,
  `value`     VARCHAR(64) NULL,
  source      VARCHAR(64) NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rec_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_logs (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  feature     VARCHAR(32) NOT NULL,
  elder_id    BIGINT NULL,
  output      MEDIUMTEXT NULL,
  used_mock   TINYINT DEFAULT 1,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 种子数据（与指导老师版演示账号/病例一致） ----------
INSERT INTO `users` (id, username, password, name, role, elder_id, phone) VALUES
  (1, 'doctor',   'doctor123', '陈医生', 'family_doctor', NULL, '139****0001'),
  (2, 'hospital', 'hosp123',   '周医生', 'hospital',      NULL, '139****0002'),
  (3, 'nurse',    'nurse123',  '刘护士', 'nurse',         NULL, '139****0003'),
  (4, 'family',   'family123', '王女士', 'family',        1,    '138****2201'),
  (5, 'elder1',   'elder123',  '王桂兰', 'elder',         1,    '138****2201'),
  (6, 'admin',    'admin123',  '管理员', 'admin',         NULL, '139****0000')
ON DUPLICATE KEY UPDATE username = VALUES(username);

INSERT INTO elders (id, name, age, gender, diag, risk, risk_score, rate, alerts, `day`, contact, address, living, emergency_contact, emergency_phone) VALUES
  (1, '王桂兰', 72, '女', '股骨置换术后', '高', 72, 60, 1, 5,  '138****2201', '光复小区 3 栋 2 单元', '与子女同住', '王女士（女儿）', '138****2201'),
  (2, '李建国', 68, '男', '高血压慢病',   '低', 81, 90, 0, 12, '139****8830', '滨河小区 7 栋 501',   '独居',       '社区家庭医生',   '139****0001'),
  (3, '张淑珍', 75, '女', '脑卒中康复中', '高', 58, 45, 1, 3,  '137****5517', '光复小区 5 栋 1 单元', '与子女同住', '张先生（儿子）', '137****5517'),
  (4, '赵德海', 70, '男', '冠心病术后',   '低', 88, 85, 0, 20, '136****9042', '滨河小区 2 栋 302',   '独居',       '社区护士站',     '139****0003')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO plans (id, elder_id, `type`, content, ai_draft, status) VALUES
  (1, 1, '康复', '踝泵运动 20 次 × 2 组；坐姿缓慢完成，避免患肢负重', 1, '待签发'),
  (2, 1, '用药', '利伐沙班 10mg 午饭后 1 片；钙片 1 片晚饭后', 1, '待签发'),
  (3, 3, '康复', '肢体被动活动 + 吞咽训练，每日 2 次，每次 15 分钟', 1, '待签发')
ON DUPLICATE KEY UPDATE content = VALUES(content);

INSERT INTO followups (id, elder_id, `type`, scheduled_at, address, note, status, review_status) VALUES
  (1, 3, '上门随访', '今日 14:00', '光复小区 3 栋 2 单元', '脑卒中康复期，重点查看肢体活动与服药情况', '待执行', '待审核'),
  (2, 1, '电话随访', '今日 15:30', '电话回访', '血压连续偏高，需复测并记录伤口情况', '待执行', '待审核'),
  (3, 2, '体征采集', '昨日 09:00', '线上填报', '血压 138/86，心率 78，服药规律，无不适主诉', '已完成', '待审核'),
  (4, 2, '电话随访', '昨日 10:20', '电话回访', '血压控制平稳，按时服药，无不适主诉', '已完成', '已通过'),
  (5, 4, '上门随访', '昨日 16:00', '滨河小区 7 栋 501', '冠心病术后恢复良好，伤口无红肿，指导居家活动', '已完成', '已通过')
ON DUPLICATE KEY UPDATE note = VALUES(note);

INSERT INTO alerts (id, elder_id, level, reason, status, conclusion) VALUES
  (1, 1, '高', '术后血压连续 3 次测量偏高，建议复核用药并安排随访', '未闭环', NULL),
  (2, 3, '高', '今日康复任务与体征均未打卡，疑似失联，需尽快联系', '未闭环', NULL),
  (3, 4, '中', '上周心率偶发偏快，已提醒避免劳累并复测', '已闭环', '复测心率回落，继续观察，纳入下次随访')
ON DUPLICATE KEY UPDATE reason = VALUES(reason);

INSERT INTO records (id, elder_id, category, title, detail, `value`, source) VALUES
  (1, 1, '体征', '血压测量 148/92 mmHg', '数值偏高，系统已提示社区医生复核', '148/92', '老人上报'),
  (2, 1, '随访', '护士上门随访', '伤口愈合良好，肢体活动度较前日改善', NULL, '护士刘敏'),
  (3, 4, '用药', '用药记录 · 钙片已服', '家属可在用药提醒中查看完整清单', NULL, '老人上报')
ON DUPLICATE KEY UPDATE title = VALUES(title);

INSERT INTO messages (id, elder_id, sender_role, sender_name, content) VALUES
  (1, 1, 'family', '王女士', '陈医生您好，我母亲今天伤口附近有点肿，需要紧去医院吗？'),
  (2, 1, 'family_doctor', '陈医生', '您好，先观察肿胀范围和皮肤温度，若出现红肿发热、渗液或明显疼痛请立即联系我们，必要时就近急诊。')
ON DUPLICATE KEY UPDATE content = VALUES(content);

INSERT INTO announcements (id, title, content, publisher) VALUES
  (1, '冬季老年慢病随访提醒', '气温骤降，请家属协助老人按时测量血压、血糖并打卡；出现胸闷、头晕、肢体麻木及时上报。', '管理员')
ON DUPLICATE KEY UPDATE title = VALUES(title);

INSERT INTO revisits (id, elder_id, plan_at, reason, status, doctor) VALUES
  (1, 4, '本周六 09:30', '冠心病术后第 20 天，复查心电图与用药依从性', '待复诊', '周医生')
ON DUPLICATE KEY UPDATE reason = VALUES(reason);
