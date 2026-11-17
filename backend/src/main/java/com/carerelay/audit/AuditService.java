package com.carerelay.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.audit.mapper.AuditLogMapper;
import com.carerelay.auth.CurrentUser;
import com.carerelay.auth.UserContext;
import com.carerelay.user.AppUser;
import com.carerelay.user.mapper.AppUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 关键操作审计：活动审核发布、报名、导出、AI 采用都留痕，
 * 对应赛题「权限与过程可追溯」的要求。
 */
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final AppUserMapper appUserMapper;

    public AuditService(AuditLogMapper auditLogMapper, AppUserMapper appUserMapper) {
        this.auditLogMapper = auditLogMapper;
        this.appUserMapper = appUserMapper;
    }

    public void record(String action, String entity, Long entityId, String detail) {
        CurrentUser current = UserContext.get();
        AuditLog log = new AuditLog();
        if (current != null) {
            log.setUserId(current.id());
            AppUser user = appUserMapper.selectById(current.id());
            log.setUserName(user == null ? "未知账号" : user.getNickname());
        } else {
            log.setUserName("系统");
        }
        log.setAction(action);
        log.setEntity(entity);
        log.setEntityId(entityId);
        log.setDetail(detail == null ? null : truncate(detail, 900));
        auditLogMapper.insert(log);
    }

    public List<AuditLog> recent(int limit) {
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .orderByDesc(AuditLog::getId)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
