package com.carerelay.report;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.ai.AiCallService;
import com.carerelay.alert.AlertEvent;
import com.carerelay.alert.mapper.AlertEventMapper;
import com.carerelay.audit.AuditService;
import com.carerelay.checkin.CareCheckin;
import com.carerelay.checkin.mapper.CareCheckinMapper;
import com.carerelay.common.BizException;
import com.carerelay.common.JsonText;
import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.ElderService;
import com.carerelay.elder.mapper.ElderProfileMapper;
import com.carerelay.followup.FollowupRecord;
import com.carerelay.followup.FollowupSession;
import com.carerelay.followup.mapper.FollowupRecordMapper;
import com.carerelay.followup.mapper.FollowupSessionMapper;
import com.carerelay.plan.CareTask;
import com.carerelay.plan.mapper.CareTaskMapper;
import com.carerelay.report.mapper.ReportMapper;
import com.carerelay.risk.RiskAssessment;
import com.carerelay.risk.mapper.RiskAssessmentMapper;
import com.carerelay.ticket.Ticket;
import com.carerelay.ticket.mapper.TicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 复盘环：把一周的打卡、预警、随访记录收成一页。
 *
 * <p>两个版本共用一份快照：家属版说人话，医生版摆事实。数字全部由代码统计，
 * 模型只负责组织语言；模型不可用时用模板兜底。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final String SCENE_FAMILY = "REPORT_FAMILY";
    private static final String SCENE_CLINICAL = "REPORT_CLINICAL";
    private static final String PROMPT_FAMILY = "prompts/report_family.v1.md";
    private static final String PROMPT_CLINICAL = "prompts/report_clinical.v1.md";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");

    private final ReportMapper reportMapper;
    private final ElderService elderService;
    private final ElderProfileMapper elderMapper;
    private final DischargeRecordMapper dischargeMapper;
    private final FollowupSessionMapper sessionMapper;
    private final CareTaskMapper taskMapper;
    private final CareCheckinMapper checkinMapper;
    private final AlertEventMapper alertMapper;
    private final TicketMapper ticketMapper;
    private final FollowupRecordMapper followupMapper;
    private final RiskAssessmentMapper riskMapper;
    private final AiCallService ai;
    private final AuditService audit;
    private final String familyPrompt;
    private final String clinicalPrompt;

    public ReportService(ReportMapper reportMapper,
                         ElderService elderService,
                         ElderProfileMapper elderMapper,
                         DischargeRecordMapper dischargeMapper,
                         FollowupSessionMapper sessionMapper,
                         CareTaskMapper taskMapper,
                         CareCheckinMapper checkinMapper,
                         AlertEventMapper alertMapper,
                         TicketMapper ticketMapper,
                         FollowupRecordMapper followupMapper,
                         RiskAssessmentMapper riskMapper,
                         AiCallService ai,
                         AuditService audit) {
        this.reportMapper = reportMapper;
        this.elderService = elderService;
        this.elderMapper = elderMapper;
        this.dischargeMapper = dischargeMapper;
        this.sessionMapper = sessionMapper;
        this.taskMapper = taskMapper;
        this.checkinMapper = checkinMapper;
        this.alertMapper = alertMapper;
        this.ticketMapper = ticketMapper;
        this.followupMapper = followupMapper;
        this.riskMapper = riskMapper;
        this.ai = ai;
        this.audit = audit;
        this.familyPrompt = load(PROMPT_FAMILY);
        this.clinicalPrompt = load(PROMPT_CLINICAL);
    }

    // ---------------------------------------------------------------- 周报

    /** 生成（或重算）某位老人的周报；weekEnd 为空取今天，周期＝从 weekEnd 往前 6 天。 */
    @Transactional
    public Map<String, Object> weekly(Long elderId, LocalDate weekEnd) {
        ElderProfile elder = elderService.require(elderId);
        LocalDate end = weekEnd == null ? LocalDate.now() : weekEnd;
        LocalDate start = end.minusDays(6);
        Snapshot s = snapshot(elderId, start, end);

        String family = familyVersion(elder, s);
        String clinical = clinicalVersion(elder, s);

        Report report = reportMapper.selectOne(Wrappers.<Report>lambdaQuery()
                .eq(Report::getElderId, elderId)
                .eq(Report::getType, "WEEKLY")
                .eq(Report::getPeriodEnd, end)
                .last("limit 1"));
        boolean created = report == null;
        if (created) {
            report = new Report();
            report.setElderId(elderId);
            report.setType("WEEKLY");
        }
        report.setPeriodStart(start);
        report.setPeriodEnd(end);
        report.setFamilyMd(family);
        report.setClinicalMd(clinical);
        if (created) {
            reportMapper.insert(report);
        } else {
            reportMapper.updateById(report);
        }
        audit.record("REPORT_WEEKLY", "report", report.getId(),
                "周报 " + start + "~" + end + "，完成率 " + s.compliance() + "%");
        Map<String, Object> out = view(report);
        out.put("stats", statsView(s));
        out.put("mode", ai.configured() ? "MODEL" : "LOCAL");
        return out;
    }

    public List<Map<String, Object>> list(Long elderId) {
        elderService.require(elderId);
        return reportMapper.selectList(Wrappers.<Report>lambdaQuery()
                        .eq(Report::getElderId, elderId)
                        .orderByDesc(Report::getPeriodEnd)
                        .orderByDesc(Report::getId)
                        .last("limit 20"))
                .stream().map(this::view).toList();
    }

    public Map<String, Object> detail(Long id) {
        Report r = reportMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "报告不存在");
        }
        Map<String, Object> out = view(r);
        ElderProfile elder = elderService.require(r.getElderId());
        out.put("elderName", elder.getName());
        out.put("stats", statsView(snapshot(r.getElderId(), r.getPeriodStart(), r.getPeriodEnd())));
        return out;
    }

    // ---------------------------------------------------------------- 30 天结局

    /** 出院满 30 天（或已经满）的结局小结：依从、预警、随访、风险变化、是否再入院登记。 */
    @Transactional
    public Map<String, Object> outcome(Long elderId) {
        ElderProfile elder = elderService.require(elderId);
        LocalDate discharge = dischargeDate(elderId);
        LocalDate end = discharge.plusDays(29);
        Snapshot s = snapshot(elderId, discharge, end);

        RiskAssessment first = riskAt(elderId, discharge.atStartOfDay(), true);
        RiskAssessment last = riskAt(elderId, end.atTime(23, 59, 59), false);
        String trend = trend(first, last);

        StringBuilder md = new StringBuilder();
        md.append("## 30 天结局（").append(discharge).append(" ~ ").append(end).append("）\n\n");
        md.append("- 老人：").append(elder.getName()).append("，")
                .append(JsonText.age(elder.getBirthDate())).append(" 岁\n");
        md.append("- 照护任务：共 ").append(s.taskTotal()).append(" 项，完成 ")
                .append(s.taskDone()).append(" 项，完成率 ").append(s.compliance()).append("%\n");
        md.append("- 打卡：").append(s.checkinTotal()).append(" 次，其中异常 ")
                .append(s.checkinAbnormal()).append(" 次\n");
        md.append("- 预警：").append(s.alertTotal()).append(" 条（高 ").append(s.alertHigh())
                .append("），未关闭 ").append(s.alertOpen()).append(" 条\n");
        md.append("- 工单：").append(s.ticketTotal()).append(" 条，待处置 ").append(s.ticketPending()).append(" 条\n");
        md.append("- 随访：").append(s.followupTotal()).append(" 次，红旗随访 ")
                .append(s.followupRed()).append(" 次\n");
        md.append("- 风险等级：").append(first == null ? "未评估" : first.getLevel())
                .append(" → ").append(last == null ? "未评估" : last.getLevel())
                .append("（").append(trend).append("）\n");
        md.append("- 再入院：未登记\n");

        Report r = reportMapper.selectOne(Wrappers.<Report>lambdaQuery()
                .eq(Report::getElderId, elderId)
                .eq(Report::getType, "CLOSURE")
                .last("limit 1"));
        boolean created = r == null;
        if (created) {
            r = new Report();
            r.setElderId(elderId);
            r.setType("CLOSURE");
        }
        r.setPeriodStart(discharge);
        r.setPeriodEnd(end);
        r.setClinicalMd(md.toString());
        r.setFamilyMd(familyOutcome(elder, s, discharge, end));
        if (created) {
            reportMapper.insert(r);
        } else {
            reportMapper.updateById(r);
        }
        audit.record("REPORT_OUTCOME", "report", r.getId(), "30 天结局：" + elder.getName());
        Map<String, Object> out = view(r);
        out.put("stats", statsView(s));
        out.put("riskTrend", trend);
        return out;
    }

    // ---------------------------------------------------------------- 总览

    /** 护士台首页：人数、风险分布、今日随访、未处理预警与工单、近 7 天平均完成率。 */
    public Map<String, Object> overview() {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(6);
        List<ElderProfile> elders = elderMapper.selectList(Wrappers.<ElderProfile>lambdaQuery()
                .orderByAsc(ElderProfile::getId));

        Map<String, Integer> byLevel = new LinkedHashMap<>();
        byLevel.put("HIGH", 0);
        byLevel.put("MEDIUM", 0);
        byLevel.put("LOW", 0);
        byLevel.put("NONE", 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalCompliance = 0;
        int counted = 0;
        for (ElderProfile e : elders) {
            RiskAssessment risk = latestRisk(e.getId());
            String level = risk == null ? "NONE" : risk.getLevel();
            byLevel.merge(level, 1, Integer::sum);
            Snapshot s = snapshot(e.getId(), weekAgo, today);
            if (s.taskTotal() > 0) {
                totalCompliance += s.compliance();
                counted++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("elderId", e.getId());
            row.put("name", e.getName());
            row.put("code", e.getCode());
            row.put("level", level);
            row.put("score", risk == null ? null : risk.getScore());
            row.put("compliance", s.compliance());
            row.put("openAlerts", s.alertOpen());
            row.put("pendingTickets", s.ticketPending());
            row.put("redFollowups", s.followupRed());
            rows.add(row);
        }
        long dueFollowups = followupDue(today);
        long openAlerts = alertMapper.selectCount(Wrappers.<AlertEvent>lambdaQuery()
                .ne(AlertEvent::getStatus, "CLOSED"));
        long pendingTickets = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getStatus, "PENDING"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today);
        out.put("elderCount", elders.size());
        out.put("riskLevels", byLevel);
        out.put("dueFollowups", dueFollowups);
        out.put("openAlerts", openAlerts);
        out.put("pendingTickets", pendingTickets);
        out.put("avgCompliance", counted == 0 ? 0 : totalCompliance / counted);
        out.put("elders", rows);
        return out;
    }

    // ---------------------------------------------------------------- 快照

    private Snapshot snapshot(Long elderId, LocalDate start, LocalDate end) {
        List<CareTask> tasks = taskMapper.selectList(Wrappers.<CareTask>lambdaQuery()
                .eq(CareTask::getElderId, elderId)
                .between(CareTask::getTaskDate, start, end));
        int done = 0;
        int missed = 0;
        int skipped = 0;
        int pending = 0;
        Map<LocalDate, int[]> trend = new TreeMap<>();
        for (CareTask t : tasks) {
            int[] cell = trend.computeIfAbsent(t.getTaskDate(), k -> new int[2]);
            cell[1]++;
            switch (String.valueOf(t.getStatus())) {
                case "DONE" -> {
                    done++;
                    cell[0]++;
                }
                case "MISSED" -> missed++;
                case "SKIPPED" -> skipped++;
                default -> pending++;
            }
        }

        List<CareCheckin> checkins = checkinMapper.selectList(Wrappers.<CareCheckin>lambdaQuery()
                .eq(CareCheckin::getElderId, elderId)
                .between(CareCheckin::getCheckinDate, start, end));
        int abnormal = (int) checkins.stream().filter(c -> "ABNORMAL".equals(c.getStatus())).count();

        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(23, 59, 59);
        List<AlertEvent> alerts = alertMapper.selectList(Wrappers.<AlertEvent>lambdaQuery()
                .eq(AlertEvent::getElderId, elderId)
                .between(AlertEvent::getCreatedAt, from, to));
        int highAlerts = (int) alerts.stream().filter(a -> "HIGH".equals(a.getLevel())).count();
        int openAlerts = (int) alerts.stream().filter(a -> !"CLOSED".equals(a.getStatus())).count();

        List<Ticket> tickets = ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getElderId, elderId)
                .between(Ticket::getCreatedAt, from, to));
        int pendingTickets = (int) tickets.stream().filter(t -> "PENDING".equals(t.getStatus())).count();

        List<FollowupRecord> records = followupMapper.selectList(Wrappers.<FollowupRecord>lambdaQuery()
                .eq(FollowupRecord::getElderId, elderId)
                .between(FollowupRecord::getCreatedAt, from, to));
        int redFollowups = (int) records.stream().filter(r -> Integer.valueOf(1).equals(r.getRedFlag())).count();

        RiskAssessment risk = riskAt(elderId, to, false);
        RiskAssessment before = riskAt(elderId, from.minusSeconds(1), false);

        List<String[]> vitals = latestVitals(checkins);
        List<Map<String, Object>> trendRows = new ArrayList<>();
        trend.forEach((day, cell) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day);
            row.put("done", cell[0]);
            row.put("total", cell[1]);
            trendRows.add(row);
        });

        return new Snapshot(start, end, tasks.size(), done, missed, skipped, pending,
                tasks.isEmpty() ? 0 : Math.round(done * 100f / tasks.size()),
                checkins.size(), abnormal, alerts.size(), highAlerts, openAlerts,
                tickets.size(), pendingTickets, records.size(), redFollowups,
                risk == null ? "NONE" : risk.getLevel(), risk == null ? null : risk.getScore(),
                riskScoreDelta(before, risk), vitals, trendRows);
    }

    /** 取窗口内每类体征最近一次的值，形如 ["血压", "138/86 mmHg", "11月4日"]。 */
    private List<String[]> latestVitals(List<CareCheckin> checkins) {
        Map<String, CareCheckin> latest = new LinkedHashMap<>();
        checkins.stream()
                .filter(c -> c.getCreatedAt() != null)
                .sorted(Comparator.comparing(CareCheckin::getCreatedAt).reversed())
                .forEach(c -> {
                    var node = JsonText.parse(c.getValueJson());
                    String kind = node != null && node.hasNonNull("kind") ? node.get("kind").asText() : null;
                    if (kind != null && !latest.containsKey(kind)) {
                        latest.put(kind, c);
                    }
                });
        List<String[]> out = new ArrayList<>();
        for (var entry : latest.entrySet()) {
            var node = JsonText.parse(entry.getValue().getValueJson());
            out.add(new String[]{labelOf(entry.getKey()), describeVital(entry.getKey(), node),
                    entry.getValue().getCheckinDate().format(DAY)});
        }
        return out;
    }

    private String labelOf(String kind) {
        return switch (kind) {
            case "BP" -> "血压";
            case "GLU" -> "血糖";
            case "TEMP" -> "体温";
            case "SPO2" -> "血氧";
            case "WEIGHT" -> "体重";
            default -> kind;
        };
    }

    private String describeVital(String kind, com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return "无记录";
        }
        return switch (kind) {
            case "BP" -> node.path("systolic").asText("?") + "/" + node.path("diastolic").asText("?") + " mmHg";
            case "GLU" -> node.path("value").asText("?") + " mmol/L";
            case "TEMP" -> node.path("value").asText("?") + " ℃";
            case "SPO2" -> node.path("value").asText("?") + " %";
            case "WEIGHT" -> node.path("value").asText("?") + " kg";
            default -> node.toString();
        };
    }

    // ---------------------------------------------------------------- 两个版本

    private String familyVersion(ElderProfile elder, Snapshot s) {
        if (ai.configured()) {
            try {
                String out = ai.chat(SCENE_FAMILY, null, familyPrompt, familyPromptData(elder, s));
                if (out != null && !out.isBlank()) {
                    return out.trim();
                }
            } catch (RuntimeException e) {
                log.warn("家属版周报生成失败，改用模板：{}", e.getMessage());
            }
        }
        return familyTemplate(elder, s);
    }

    private String clinicalVersion(ElderProfile elder, Snapshot s) {
        if (ai.configured()) {
            try {
                String out = ai.chat(SCENE_CLINICAL, null, clinicalPrompt, clinicalPromptData(elder, s));
                if (out != null && !out.isBlank()) {
                    return out.trim();
                }
            } catch (RuntimeException e) {
                log.warn("医生版周报生成失败，改用模板：{}", e.getMessage());
            }
        }
        return clinicalTemplate(elder, s);
    }

    private String familyTemplate(ElderProfile elder, Snapshot s) {
        String name = elder.getName();
        if (s.taskTotal() == 0) {
            return name + "这周还没有排上的照护任务。您可以先看看出院时医生交代的注意事项，"
                    + "有需要随时联系社区卫生服务中心，我们会安排护士上门。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("这周一共 ").append(s.taskTotal()).append(" 项照护任务，完成了 ")
                .append(s.taskDone()).append(" 项，完成率 ").append(s.compliance()).append("%。");
        if (s.taskMissed() > 0 || s.taskPending() > 0) {
            sb.append("还有 ").append(s.taskMissed() + s.taskPending()).append(" 项没完成，多数是忘记或者不方便，")
                    .append("下周我们把提醒时间调到您方便的时候。");
        }
        if (s.alertTotal() > 0) {
            sb.append("这周有 ").append(s.alertTotal()).append(" 次收到提醒");
            sb.append(s.alertOpen() == 0 ? "，都已经处理好了。" : "，还有 " + s.alertOpen() + " 件在处理，护士会回电话。");
        } else if (s.checkinAbnormal() > 0) {
            sb.append("打卡里有 ").append(s.checkinAbnormal()).append(" 次数值偏高，护士已经看过，暂时不用调整。");
        } else {
            sb.append("各项数值都还平稳。");
        }
        String vitals = vitalLine(s);
        if (!vitals.isEmpty()) {
            sb.append("最近一次：").append(vitals).append("。");
        }
        sb.append("下周照现在的清单继续做，饭前饭后记得量血压，有不舒服随时打电话。");
        return sb.toString();
    }

    private String clinicalTemplate(ElderProfile elder, Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 概况\n\n");
        sb.append("- 老人：").append(elder.getName()).append("（").append(elder.getCode()).append("），")
                .append(JsonText.age(elder.getBirthDate())).append(" 岁\n");
        sb.append("- 周期：").append(s.start()).append(" ~ ").append(s.end()).append("\n");
        sb.append("- 风险等级：").append(s.riskLevel()).append("（").append(s.riskScore() == null ? "-" : s.riskScore())
                .append(" 分，较上期 ").append(signed(s.riskDelta())).append("）\n\n");

        sb.append("## 指标\n\n");
        sb.append("| 项目 | 数值 |\n| --- | --- |\n");
        sb.append("| 任务完成率 | ").append(s.compliance()).append("%（").append(s.taskDone())
                .append("/").append(s.taskTotal()).append("） |\n");
        sb.append("| 未完成/漏做 | ").append(s.taskMissed() + s.taskPending() + s.taskSkipped()).append(" 项 |\n");
        sb.append("| 打卡次数 | ").append(s.checkinTotal()).append(" 次，异常 ").append(s.checkinAbnormal()).append(" 次 |\n");
        for (String[] v : s.vitals()) {
            sb.append("| ").append(v[0]).append(" | ").append(v[1]).append("（").append(v[2]).append("） |\n");
        }
        if (s.vitals().isEmpty()) {
            sb.append("| 体征 | 无记录 |\n");
        }
        sb.append("\n## 预警与随访\n\n");
        sb.append("- 预警 ").append(s.alertTotal()).append(" 条（高 ").append(s.alertHigh())
                .append("，未关闭 ").append(s.alertOpen()).append("）\n");
        sb.append("- 工单 ").append(s.ticketTotal()).append(" 条（待处置 ").append(s.ticketPending()).append("）\n");
        sb.append("- 随访 ").append(s.followupTotal()).append(" 次，红旗 ").append(s.followupRed()).append(" 次\n\n");
        sb.append("## 需关注\n\n");
        List<String> notes = new ArrayList<>();
        if (s.compliance() < 60) {
            notes.add("完成率低于 60%，需核实是否执行困难或认知/家庭支持问题");
        }
        if (s.alertHigh() > 0) {
            notes.add("出现高危预警 " + s.alertHigh() + " 次，建议医生评估");
        }
        if (s.followupRed() > 0) {
            notes.add("随访红旗 " + s.followupRed() + " 次，建议护士回访核实");
        }
        if (s.checkinAbnormal() > 0 && s.alertTotal() == 0) {
            notes.add("存在异常打卡未触发预警，请核查判读规则");
        }
        if (notes.isEmpty()) {
            notes.add("本期无特殊，按计划继续");
        }
        for (String n : notes) {
            sb.append("- ").append(n).append("\n");
        }
        return sb.toString();
    }

    private String familyOutcome(ElderProfile elder, Snapshot s, LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();
        sb.append(elder.getName()).append("出院这 30 天里，一共 ").append(s.taskTotal()).append(" 项照护任务，完成了 ")
                .append(s.taskDone()).append(" 项。");
        if (s.alertHigh() > 0) {
            sb.append("期间有 ").append(s.alertHigh()).append(" 次比较紧急的情况，护士都跟进了。");
        } else if (s.alertTotal() > 0) {
            sb.append("期间收到 ").append(s.alertTotal()).append(" 次提醒，都处理过了。");
        } else {
            sb.append("期间没有出现需要紧急处理的情况。");
        }
        sb.append("接下来照常复诊，后续由社区继续随访。");
        return sb.toString();
    }

    private String familyPromptData(ElderProfile elder, Snapshot s) {
        Map<String, Object> d = baseData(elder, s);
        d.put("提醒次数", s.alertTotal());
        d.put("未处理提醒", s.alertOpen());
        d.put("数值异常打卡", s.checkinAbnormal());
        return JsonText.of(d);
    }

    private String clinicalPromptData(ElderProfile elder, Snapshot s) {
        Map<String, Object> d = baseData(elder, s);
        d.put("预警", Map.of("总数", s.alertTotal(), "高危", s.alertHigh(), "未关闭", s.alertOpen()));
        d.put("工单", Map.of("总数", s.ticketTotal(), "待处置", s.ticketPending()));
        d.put("随访", Map.of("次数", s.followupTotal(), "红旗", s.followupRed()));
        d.put("体征", s.vitals());
        return JsonText.of(d);
    }

    private Map<String, Object> baseData(ElderProfile elder, Snapshot s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("姓名", elder.getName());
        d.put("年龄", JsonText.age(elder.getBirthDate()));
        d.put("周期", s.start() + " 至 " + s.end());
        d.put("任务总数", s.taskTotal());
        d.put("已完成", s.taskDone());
        d.put("漏做", s.taskMissed());
        d.put("跳过", s.taskSkipped());
        d.put("未开始", s.taskPending());
        d.put("完成率", s.compliance() + "%");
        d.put("打卡次数", s.checkinTotal());
        d.put("风险等级", s.riskLevel());
        d.put("风险分", s.riskScore());
        d.put("较上期变化", signed(s.riskDelta()));
        d.put("每日完成", s.trend());
        return d;
    }

    private String vitalLine(Snapshot s) {
        List<String> parts = new ArrayList<>();
        for (String[] v : s.vitals()) {
            parts.add(v[0] + " " + v[1]);
        }
        return String.join("，", parts);
    }

    private String signed(Integer delta) {
        if (delta == null) {
            return "无基线";
        }
        return delta > 0 ? "+" + delta + " 分" : delta == 0 ? "持平" : delta + " 分";
    }

    // ---------------------------------------------------------------- 视图

    private Map<String, Object> view(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("elderId", r.getElderId());
        m.put("type", r.getType());
        m.put("periodStart", r.getPeriodStart());
        m.put("periodEnd", r.getPeriodEnd());
        m.put("familyMd", r.getFamilyMd());
        m.put("clinicalMd", r.getClinicalMd());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<String, Object> statsView(Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskTotal", s.taskTotal());
        m.put("taskDone", s.taskDone());
        m.put("taskMissed", s.taskMissed());
        m.put("taskSkipped", s.taskSkipped());
        m.put("taskPending", s.taskPending());
        m.put("compliance", s.compliance());
        m.put("checkinTotal", s.checkinTotal());
        m.put("checkinAbnormal", s.checkinAbnormal());
        m.put("alertTotal", s.alertTotal());
        m.put("alertHigh", s.alertHigh());
        m.put("alertOpen", s.alertOpen());
        m.put("ticketTotal", s.ticketTotal());
        m.put("ticketPending", s.ticketPending());
        m.put("followupTotal", s.followupTotal());
        m.put("followupRed", s.followupRed());
        m.put("riskLevel", s.riskLevel());
        m.put("riskScore", s.riskScore());
        m.put("riskDelta", s.riskDelta());
        m.put("vitals", s.vitals());
        m.put("trend", s.trend());
        return m;
    }

    private long followupDue(LocalDate today) {
        return sessionMapper.selectCount(Wrappers.<FollowupSession>lambdaQuery()
                .eq(FollowupSession::getStatus, "PLANNED")
                .le(FollowupSession::getPlannedDate, today));
    }

    private RiskAssessment riskAt(Long elderId, LocalDateTime at, boolean earliest) {
        var list = riskMapper.selectList(Wrappers.<RiskAssessment>lambdaQuery()
                .eq(RiskAssessment::getElderId, elderId)
                .le(RiskAssessment::getCreatedAt, at)
                .orderBy(true, earliest, RiskAssessment::getCreatedAt)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private RiskAssessment latestRisk(Long elderId) {
        var list = riskMapper.selectList(Wrappers.<RiskAssessment>lambdaQuery()
                .eq(RiskAssessment::getElderId, elderId)
                .orderByDesc(RiskAssessment::getCreatedAt)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Integer riskScoreDelta(RiskAssessment before, RiskAssessment after) {
        if (before == null || after == null || before.getScore() == null || after.getScore() == null) {
            return null;
        }
        return after.getScore() - before.getScore();
    }

    private String trend(RiskAssessment before, RiskAssessment after) {
        if (before == null || after == null) {
            return "基线不足，暂不判断";
        }
        Integer delta = riskScoreDelta(before, after);
        if (delta == null || delta == 0) {
            return "持平";
        }
        return delta > 0 ? "上升 " + delta + " 分，风险加重" : "下降 " + (-delta) + " 分，情况好转";
    }

    private LocalDate dischargeDate(Long elderId) {
        DischargeRecord r = dischargeMapper.selectOne(Wrappers.<DischargeRecord>lambdaQuery()
                .eq(DischargeRecord::getElderId, elderId)
                .orderByDesc(DischargeRecord::getId)
                .last("limit 1"));
        return r == null || r.getDischargeDate() == null ? LocalDate.now() : r.getDischargeDate();
    }

    private String load(String path) {
        try (var in = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取提示词失败：{}", path);
            return "";
        }
    }

    private record Snapshot(LocalDate start, LocalDate end,
                            int taskTotal, int taskDone, int taskMissed, int taskSkipped, int taskPending,
                            int compliance,
                            int checkinTotal, int checkinAbnormal,
                            int alertTotal, int alertHigh, int alertOpen,
                            int ticketTotal, int ticketPending,
                            int followupTotal, int followupRed,
                            String riskLevel, Integer riskScore, Integer riskDelta,
                            List<String[]> vitals, List<Map<String, Object>> trend) {
    }
}
