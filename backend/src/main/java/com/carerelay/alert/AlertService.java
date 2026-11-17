package com.carerelay.alert;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.audit.AuditService;
import com.carerelay.auth.RoleRules;
import com.carerelay.auth.UserContext;
import com.carerelay.common.BizException;
import com.carerelay.alert.mapper.AlertEventMapper;
import com.carerelay.ticket.Ticket;
import com.carerelay.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 预警与工单的处置闭环：护士/家庭医生在这里把 OPEN 关掉，过程留审计。 */
@Service
public class AlertService {

    private final AlertEventMapper alertMapper;
    private final TicketMapper ticketMapper;
    private final AuditService audit;

    public AlertService(AlertEventMapper alertMapper, TicketMapper ticketMapper, AuditService audit) {
        this.alertMapper = alertMapper;
        this.ticketMapper = ticketMapper;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(String status) {
        var query = Wrappers.<AlertEvent>lambdaQuery().orderByDesc(AlertEvent::getId);
        if (status != null && !status.isBlank()) {
            query.eq(AlertEvent::getStatus, status);
        }
        return alertMapper.selectList(query.last("limit 100")).stream().map(this::view).toList();
    }

    public Map<String, Object> close(Long alertId, String note) {
        AlertEvent alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new BizException(404, "预警不存在");
        }
        if ("CLOSED".equals(alert.getStatus())) {
            throw new BizException(409, "该预警已关闭");
        }
        // 角色分工：高危预警必须医生确认关闭，护士只能关中/低危
        RoleRules.requireHighAlertCloser(UserContext.require(), alert.getLevel());
        alert.setStatus("CLOSED");
        alert.setClosedBy(UserContext.require().id());
        alert.setClosedAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            alert.setDetail(trim(alert.getDetail() + "｜处置：" + note, 500));
        }
        alertMapper.updateById(alert);
        audit.record("ALERT_CLOSE", "alert_event", alert.getId(), "关闭预警：" + alert.getRuleCode());
        return view(alert);
    }

    public List<Map<String, Object>> tickets(String status) {
        var query = Wrappers.<Ticket>lambdaQuery().orderByDesc(Ticket::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Ticket::getStatus, status);
        }
        return ticketMapper.selectList(query.last("limit 100")).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("elderId", t.getElderId());
            m.put("alertId", t.getAlertId());
            m.put("level", t.getLevel());
            m.put("reason", t.getReason());
            m.put("status", t.getStatus());
            m.put("note", t.getNote());
            m.put("createdAt", t.getCreatedAt());
            return m;
        }).toList();
    }

    public Map<String, Object> handle(Long ticketId, String note) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(404, "工单不存在");
        }
        ticket.setStatus("DONE");
        ticket.setHandlerId(UserContext.require().id());
        ticket.setNote(note == null ? "" : trim(note, 480));
        ticket.setHandledAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        audit.record("TICKET_HANDLE", "ticket", ticket.getId(), "工单处置完成：" + ticket.getReason());
        return Map.of("id", ticket.getId(), "status", ticket.getStatus(), "note", ticket.getNote());
    }

    private Map<String, Object> view(AlertEvent a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("elderId", a.getElderId());
        m.put("source", a.getSource());
        m.put("ruleCode", a.getRuleCode());
        m.put("level", a.getLevel());
        m.put("title", a.getTitle());
        m.put("detail", a.getDetail());
        m.put("status", a.getStatus());
        m.put("createdAt", a.getCreatedAt());
        m.put("closedAt", a.getClosedAt());
        return m;
    }

    private String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
