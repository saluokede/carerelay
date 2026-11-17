-- 演示账号精简：只保留 医院主治医生 / 社区照护员 / 出院老人 / 管理员 四个角色
-- 家庭医生（doctor）、家属（family）不再作为演示账号提供，避免演示时角色混淆。
DELETE FROM `users` WHERE username IN ('doctor', 'family');
