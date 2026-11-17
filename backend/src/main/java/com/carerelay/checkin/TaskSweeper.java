package com.carerelay.checkin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carerelay.alert.AlertEvent;
import com.carerelay.alert.mapper.AlertEventMapper;
import com.carerelay.audit.AuditService;
import com.carerelay.checkin.mapper.CareCheckinMapper;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.mapper.ElderProfileMapper;
import com.carerelay.plan.CareTask;
import com.carerelay.plan.mapper.CareTaskMapper;
import com.carerelay.ticket.Ticket;
import com.carerelay.ticket.mapper.TicketMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 逾期任务巡检。
 *
 * <p>为什么必须有它：任务只有 PENDING/DONE/MISSED 三种状态，而 MISSED 原来没有任何东西会写进去——
 * 家属今天不打卡，系统就安静地什么也不发生，这跟"最危险的是没人接着"的产品立论是矛盾的。</p>
 *
 * <p>规则（每小时整点后第 5 分钟跑一次，也可手动触发）：
 * <ul>
 *   <li>昨天及以前、以及今天已过时间段的 PENDING 任务 → MISSED；</li>
 *   <li>近 3 天累计漏做 ≥2 件 → 一条 MEDIUM 预警；≥3 件 → 一条 HIGH 预警，并同时开一张工单；</li>
 *   <li>同一位老人同一天只升级一次（按 ruleCode=TASK_MISSED 去重），避免每小时刷屏。</li>
 * </ul>
 */
@Component
public class TaskSweeper {

    private static final Logger log = LoggerFactory.getLogger(TaskSweeper.class);

    /** 连续漏做到几件开始预警 / 开工单。 */
    private static final int ALERT_AT = 2;
    private static final int TICKET_AT = 3;
    /** 只看最近这么多天，历史任务不追溯，避免第一次上线就炸出一堆预警。 */
    private static final int LOOKBACK_DAYS = 7;

    /** 时间段 → 当天分钟数；ANYTIME 与未知值不判逾期。 */
    private static final Map<String, Integer> SLOT_MINUTE = Map.of(
            "MORNING", 8 * 60,
            "NOON", 12 * 60,
            "EVENING", 18 * 60);

    private final CareTaskMapper tasks;
    private final ElderProfileMapper elders;
    private final AlertEventMapper alerts;
    private final TicketMapper tickets;
    private final CareCheckinMapper checkins;
    private final AuditService audit;

    @Value("${cr.sweep.enabled:true}")
    private boolean enabled;

    public TaskSweeper(CareTaskMapper tasks, ElderProfileMapper elders, AlertEventMapper alerts,
                       TicketMapper tickets, CareCheckinMapper checkins, AuditService audit) {
        this.tasks = tasks;
        this.elders = elders;
        this.alerts = alerts;
        this.tickets = tickets;
        this.checkins = checkins;
        this.audit = audit;
    }

    /** 每小时第 5 分钟。cron 用服务器本地时区（本项目部署在国内，够用）。 */
    @Scheduled(cron = "0 5 * * * *")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> result = sweepOnce();
            log.info("[sweep] 逾期巡检完成 {}", result);
        } catch (RuntimeException e) {
            log.warn("[sweep] 逾期巡检失败：{}", e.getMessage(), e);
        }
    }

    /** 手动触发（演示、验收、补跑都用它）。 */
    public Map<String, Object> sweepOnce() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        int minuteOfDay = now.getHour() * 60 + now.getMinute();

        List<CareTask> pending = tasks.selectList(new LambdaQueryWrapper<CareTask>()
                .eq(CareTask::getStatus, "PENDING")
                .ge(CareTask::getTaskDate, today.minusDays(LOOKBACK_DAYS))
                .le(CareTask::getTaskDate, today));

        // 住院中的老人整体跳过：任务保留，但不判逾期（人在医院，家里的活本来就不该催）
        Set<Long> hospitalized = elders.selectList(new LambdaQueryWrapper<ElderProfile>()
                        .eq(ElderProfile::getCareStatus, "HOSPITALIZED"))
                .stream().map(ElderProfile::getId).collect(Collectors.toSet());

        int missed = 0;
        for (CareTask task : pending) {
            if (hospitalized.contains(task.getElderId())) {
                continue;
            }
            if (isOverdue(task, today, minuteOfDay)) {
                task.setStatus("MISSED");
                tasks.updateById(task);
                missed++;
            }
        }

        List<CareTask> recentMissed = tasks.selectList(new LambdaQueryWrapper<CareTask>()
                .eq(CareTask::getStatus, "MISSED")
                .ge(CareTask::getTaskDate, today.minusDays(TICKET_AT - 1))
                .le(CareTask::getTaskDate, today));

        Map<Long, List<CareTask>> byElder = new LinkedHashMap<>();
        for (CareTask task : recentMissed) {
            byElder.computeIfAbsent(task.getElderId(), k -> new ArrayList<>()).add(task);
        }

        int raisedAlerts = 0;
        int raisedTickets = 0;
        for (Map.Entry<Long, List<CareTask>> entry : byElder.entrySet()) {
            Long elderId = entry.getKey();
            List<CareTask> missedTasks = entry.getValue();
            missedTasks.sort(Comparator.comparing(CareTask::getTaskDate).reversed());
            int count = missedTasks.size();
            if (count < ALERT_AT || alreadyRaisedToday(elderId, today)) {
                continue;
            }
            ElderProfile elder = elders.selectById(elderId);
            if (elder == null) {
                continue;
            }
            // 住院中的人不催"家里的任务"：任务照旧保留，但既不判逾期也不升级
            if ("HOSPITALIZED".equals(elder.getCareStatus())) {
                continue;
            }
            boolean severe = count >= TICKET_AT;
            String detail = describe(elder, missedTasks, count);

            AlertEvent alert = new AlertEvent();
            alert.setElderId(elderId);
            alert.setSource("RULE");
            alert.setRuleCode("TASK_MISSED");
            alert.setLevel(severe ? "HIGH" : "MEDIUM");
            alert.setTitle(severe ? "连续漏做，需要人工介入" : "有任务连续没做");
            alert.setDetail(detail);
            alert.setStatus("OPEN");
            alerts.insert(alert);
            raisedAlerts++;

            if (severe) {
                Ticket ticket = new Ticket();
                ticket.setElderId(elderId);
                ticket.setAlertId(alert.getId());
                ticket.setSource("RULE");
                ticket.setLevel("HIGH");
                ticket.setReason(detail.length() > 240 ? detail.substring(0, 240) : detail);
                ticket.setStatus("PENDING");
                tickets.insert(ticket);
                raisedTickets++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", now.toString());
        result.put("pendingScanned", pending.size());
        result.put("markedMissed", missed);
        result.put("alertsRaised", raisedAlerts);
        result.put("ticketsRaised", raisedTickets);
        if (missed > 0 || raisedAlerts > 0) {
            audit.record("TASK_SWEEP", "CARE_TASK", null,
                    "逾期巡检：置 MISSED " + missed + " 条，新增预警 " + raisedAlerts + " 条、工单 " + raisedTickets + " 张");
        }
        return result;
    }

    private boolean isOverdue(CareTask task, LocalDate today, int minuteOfDay) {
        if (task.getTaskDate() == null) {
            return false;
        }
        if (task.getTaskDate().isBefore(today)) {
            return true;
        }
        Integer at = SLOT_MINUTE.get(task.getTimeSlot() == null ? "" : task.getTimeSlot());
        return at != null && minuteOfDay > at + 30; // 给 30 分钟宽限，避免刚过点就被判"没做"
    }

    /** 同一位老人、同一条规则、今天是否已经报过。 */
    private boolean alreadyRaisedToday(Long elderId, LocalDate today) {
        Long count = alerts.selectCount(new LambdaQueryWrapper<AlertEvent>()
                .eq(AlertEvent::getElderId, elderId)
                .eq(AlertEvent::getRuleCode, "TASK_MISSED")
                .ge(AlertEvent::getCreatedAt, today.atStartOfDay()));
        return count != null && count > 0;
    }

    private String describe(ElderProfile elder, List<CareTask> missedTasks, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append(elder.getName()).append(" 近 ").append(TICKET_AT)
                .append(" 天累计 ").append(count).append(" 件照护任务没有完成。");
        List<CareTask> latest = missedTasks.subList(0, Math.min(3, missedTasks.size()));
        sb.append("最近漏做：");
        for (int i = 0; i < latest.size(); i++) {
            CareTask t = latest.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(t.getTaskDate()).append(' ').append(t.getTitle());
        }
        sb.append("。请核实是执行困难、提醒没到，还是家属不在身边。");
        return sb.toString();
    }
}
