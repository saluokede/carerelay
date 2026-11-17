package com.carerelay.conf;

import com.carerelay.user.AppUser;
import com.carerelay.user.mapper.AppUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示账号种子数据。
 *
 * <p>为什么用代码而不是 SQL 插种子：BCrypt 散列每次生成都不同，写死在 SQL 里既不可读也不好换密码；
 * 用代码初始化可以保证"密码写在文档里、库里只有散列"。</p>
 *
 * <p>幂等：只要库里已经有账号就整体跳过，避免重启后重复插入。</p>
 */
@Slf4j
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private final AppUserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DemoDataSeeder(AppUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = userMapper.selectCount(null);
        if (existing != null && existing > 0) {
            log.info("账号种子已存在（{} 个），跳过初始化", existing);
            return;
        }

        // 密码表：写在这里，也写进 README 与答辩稿，评委可以照着重现
        save("13800000000", "admin123", "中心管理员", "ADMIN");
        save("13800000001", "nurse123", "王护士（责任护士）", "NURSE");
        save("13800000002", "doctor123", "李医生（家庭医生）", "DOCTOR");
        save("13800000003", "family123", "张阿姨（家属）", "FAMILY");

        log.info("已初始化 {} 个演示账号", 4);
    }

    private void save(String phone, String rawPassword, String nickname, String role) {
        AppUser user = new AppUser();
        user.setPhone(phone);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setNickname(nickname);
        user.setRole(role);
        user.setOrgName("阳光社区卫生服务中心");
        user.setStatus(1);
        userMapper.insert(user);
    }

    /** 供后续模块复用：返回全部演示账号，便于本地自检。 */
    public List<AppUser> all() {
        return userMapper.selectList(null);
    }
}
