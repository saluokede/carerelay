package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 医患消息：家属 / 老人只能看到并发送到绑定老人的会话。
 */
@RestController
@RequestMapping("/api/messages")
public class StationMessageController {

    private static final String JOIN = "SELECT m.id, m.elder_id, m.sender_role, m.sender_name, m.content, "
            + "e.name AS elder_name, DATE_FORMAT(m.created_at,'%Y-%m-%d %H:%i:%s') AS created_at "
            + "FROM messages m JOIN elders e ON e.id = m.elder_id ";

    private final JdbcTemplate jdbc;

    public StationMessageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(name = "elder_id", required = false) Long elderId) {
        StationUser user = StationContext.require();
        if (user.isFamilyLike()) {
            if (user.elderId() == null) {
                return ApiResult.ok(new ArrayList<>());
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT " + StationSupport.MESSAGE_COLS + " FROM messages WHERE elder_id = ? "
                            + "ORDER BY created_at ASC, id ASC", user.elderId());
            return ApiResult.ok(rows);
        }
        List<Map<String, Object>> rows = elderId == null
                ? jdbc.queryForList(JOIN + "ORDER BY m.created_at ASC, m.id ASC")
                : jdbc.queryForList(JOIN + "WHERE m.elder_id = ? ORDER BY m.created_at ASC, m.id ASC", elderId);
        return ApiResult.ok(rows);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> send(@RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationContext.require();
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        if (user.isFamilyLike()) {
            elderId = user.elderId();
        }
        if (elderId == null) {
            throw new BizException(400, "缺少会话对象");
        }
        String content = StationSupport.str(body, "content");
        if (content == null) {
            throw new BizException(400, "消息内容不能为空");
        }
        StationSupport.elder(jdbc, elderId);
        Long boundElderId = elderId;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO messages (elder_id,sender_role,sender_name,content) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, boundElderId);
            ps.setString(2, user.role());
            ps.setString(3, user.name());
            ps.setString(4, content.trim());
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.MESSAGE_COLS + " FROM messages WHERE id = ?", id);
        return ApiResult.ok(rows.isEmpty() ? Map.of() : rows.get(0));
    }
}
