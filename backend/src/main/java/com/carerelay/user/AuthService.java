package com.carerelay.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.auth.JwtService;
import com.carerelay.common.BizException;
import com.carerelay.user.dto.AuthResponse;
import com.carerelay.user.dto.LoginRequest;
import com.carerelay.user.dto.RegisterRequest;
import com.carerelay.user.dto.UserView;
import com.carerelay.user.mapper.AppUserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AppUserMapper userMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(AppUserMapper userMapper, JwtService jwtService) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
    }

    /**
     * 参赛演示环境不开放自助注册：账号由种子数据初始化，避免任何人注册后进入后台。
     */
    public AuthResponse register(RegisterRequest request) {
        throw new BizException(403, "演示环境未开放自助注册，请使用演示账号：13800000000 / admin123（管理员）、13800000001 / nurse123（社区护士）");
    }

    public AuthResponse login(LoginRequest request) {
        AppUser user = userMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, request.phone())
                .last("limit 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(401, "手机号或密码不对");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(403, "这个账号已被停用");
        }
        return toAuthResponse(user);
    }

    public UserView findById(long userId) {
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "账号不存在");
        }
        return UserView.of(user);
    }

    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private AuthResponse toAuthResponse(AppUser user) {
        String token = jwtService.issue(user.getId(), user.getRole(), null);
        return new AuthResponse(token, jwtService.ttlHours(), UserView.of(user));
    }
}
