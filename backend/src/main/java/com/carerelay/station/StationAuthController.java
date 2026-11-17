package com.carerelay.station;

import com.carerelay.auth.JwtService;
import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 登录 / 自助注册（仅家属、老人本人）/ 本人资料。
 */
@RestController
@RequestMapping("/api/auth")
public class StationAuthController {

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;

    public StationAuthController(JdbcTemplate jdbc, JwtService jwtService) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        String username = StationSupport.str(body, "username");
        String password = StationSupport.str(body, "password");
        if (username == null || password == null) {
            throw new BizException(400, "请输入账号和密码");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password, name, role, elder_id, phone, status FROM `users` WHERE username = ?", username);
        if (rows.isEmpty() || !password.equals(String.valueOf(rows.get(0).get("password")))) {
            throw new BizException(401, "账号或密码错误");
        }
        Map<String, Object> user = rows.get(0);
        if ("停用".equals(String.valueOf(user.get("status")))) {
            throw new BizException(403, "账号已停用，请联系管理员");
        }
        long id = StationSupport.longOf(user.get("id"));
        String role = String.valueOf(user.get("role"));
        String token = jwtService.issue(id, role, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("id", id);
        data.put("role", role);
        data.put("name", user.get("name"));
        data.put("elderId", StationSupport.longOrNull(user.get("elder_id")));
        data.put("phone", user.get("phone"));
        return ApiResult.ok(data);
    }

    @PostMapping("/register")
    public ApiResult<Map<String, Object>> register(@RequestBody(required = false) Map<String, Object> body) {
        String role = StationSupport.str(body, "role");
        String username = StationSupport.str(body, "username");
        String password = StationSupport.str(body, "password");
        String name = StationSupport.str(body, "name");
        String phone = StationSupport.str(body, "phone");
        if (!"family".equals(role) && !"elder".equals(role)) {
            throw new BizException(403, "该角色不支持自助注册，请联系管理员开通");
        }
        if (username == null || password == null || name == null) {
            throw new BizException(400, "账号、密码和姓名不能为空");
        }
        if (username.length() < 3) {
            throw new BizException(400, "账号至少 3 个字符");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM `users` WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new BizException(409, "该账号已被注册");
        }

        Long elderId;
        if ("elder".equals(role)) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO elders (name,age,gender,diag,risk,rate,`day`,contact,address,living,emergency_contact,emergency_phone) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                Integer age = StationSupport.integer(body, "age");
                if (age == null) {
                    ps.setObject(2, null);
                } else {
                    ps.setInt(2, age);
                }
                ps.setString(3, StationSupport.str(body, "gender", ""));
                ps.setString(4, StationSupport.str(body, "diag", "待补充诊断"));
                ps.setString(5, "低");
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setString(8, phone == null ? "" : phone);
                ps.setString(9, StationSupport.str(body, "address", ""));
                ps.setString(10, StationSupport.str(body, "living", "与子女同住"));
                ps.setString(11, "");
                ps.setString(12, phone == null ? "" : phone);
                return ps;
            }, keyHolder);
            elderId = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        } else {
            String elderName = StationSupport.str(body, "elder_name");
            List<Map<String, Object>> elders = jdbc.queryForList(
                    "SELECT id FROM elders WHERE name = ? ORDER BY id LIMIT 1", elderName == null ? "" : elderName.trim());
            if (elders.isEmpty()) {
                throw new BizException(400, "未找到该老人档案，请核对姓名，或由老人先完成注册");
            }
            elderId = StationSupport.longOrNull(elders.get(0).get("id"));
        }

        Long boundElderId = elderId;
        KeyHolder userKey = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO `users` (username,password,name,role,elder_id,phone) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, password);
            ps.setString(3, name);
            ps.setString(4, role);
            if (boundElderId == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, boundElderId);
            }
            ps.setString(6, phone == null ? "" : phone);
            return ps;
        }, userKey);
        long userId = userKey.getKey() == null ? 0 : userKey.getKey().longValue();
        String token = jwtService.issue(userId, role, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("id", userId);
        data.put("role", role);
        data.put("name", name);
        data.put("elderId", boundElderId);
        data.put("phone", phone);
        return ApiResult.ok(data);
    }

    @PutMapping("/profile")
    public ApiResult<Map<String, Object>> updateProfile(@RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, role, name, phone, elder_id FROM `users` WHERE id = ?", user.id());
        if (rows.isEmpty()) {
            throw new BizException(404, "账号不存在");
        }
        Map<String, Object> row = rows.get(0);
        String nextName = StationSupport.str(body, "name", String.valueOf(row.get("name"))).trim();
        String nextPhone = StationSupport.str(body, "phone", row.get("phone") == null ? null : String.valueOf(row.get("phone")));
        jdbc.update("UPDATE `users` SET name = ?, phone = ? WHERE id = ?", nextName, nextPhone, user.id());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.id());
        data.put("role", row.get("role"));
        data.put("name", nextName);
        data.put("phone", nextPhone);
        data.put("elderId", StationSupport.longOrNull(row.get("elder_id")));
        return ApiResult.ok(data);
    }

    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me() {
        StationUser user = StationContext.require();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.id());
        data.put("role", user.role());
        data.put("name", user.name());
        data.put("elderId", user.elderId());
        data.put("phone", user.phone());
        return ApiResult.ok(data);
    }
}
