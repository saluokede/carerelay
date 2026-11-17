package com.carerelay.admission;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.admission.dto.AdmissionRequest;
import com.carerelay.admission.mapper.ElderAdmissionMapper;
import com.carerelay.audit.AuditService;
import com.carerelay.auth.UserContext;
import com.carerelay.common.BizException;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.ElderService;
import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.mapper.ElderProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院记录：每次住院一条。
 *
 * <p>为什么要有这张表：{@code elder_profile} 上的 bed_no / hospital 只是"当前值"，
 * 一旦第二次住院就会被覆盖，护士事后想回答"上个月是在哪家医院住的院、住了几天"就查不到了。</p>
 */
@Service
public class AdmissionService {

    private final ElderAdmissionMapper mapper;
    private final ElderProfileMapper elders;
    private final ElderService elderService;
    private final AuditService audit;
    private final DischargeRecordMapper dischargeRecords;

    public AdmissionService(ElderAdmissionMapper mapper,
                            ElderProfileMapper elders,
                            ElderService elderService,
                            AuditService audit,
                            DischargeRecordMapper dischargeRecords) {
        this.mapper = mapper;
        this.elders = elders;
        this.elderService = elderService;
        this.audit = audit;
        this.dischargeRecords = dischargeRecords;
    }

    public List<Map<String, Object>> list(Long elderId) {
        elderService.require(elderId);
        return mapper.selectList(Wrappers.<ElderAdmission>lambdaQuery()
                        .eq(ElderAdmission::getElderId, elderId)
                        .ne(ElderAdmission::getStatus, "VOID")
                        .orderByDesc(ElderAdmission::getAdmitDate)
                        .orderByDesc(ElderAdmission::getId))
                .stream().map(this::toView).toList();
    }

    public ElderAdmission current(Long elderId) {
        return mapper.selectOne(Wrappers.<ElderAdmission>lambdaQuery()
                .eq(ElderAdmission::getElderId, elderId)
                .eq(ElderAdmission::getStatus, "IN_HOSPITAL")
                .orderByDesc(ElderAdmission::getAdmitDate)
                .last("limit 1"));
    }

    /**
     * 登记一次住院。带 dischargeDate 的当作"补登历史"，不带的是"现在就在院"。
     */
    @Transactional
    public Map<String, Object> admit(Long elderId, AdmissionRequest req) {
        elderService.require(elderId);
        if (req == null || req.admitDate() == null || req.admitDate().isBlank()) {
            throw new BizException(400, "请填写入院日期");
        }
        LocalDate admitDate = parseDate(req.admitDate(), "入院日期");
        LocalDate dischargeDate = req.dischargeDate() == null || req.dischargeDate().isBlank()
                ? null : parseDate(req.dischargeDate(), "出院日期");
        if (dischargeDate != null && dischargeDate.isBefore(admitDate)) {
            throw new BizException(400, "出院日期不能早于入院日期");
        }
        if (dischargeDate == null && current(elderId) != null) {
            throw new BizException(400, "已经有一条在院记录，请先给那一笔办理出院");
        }

        ElderAdmission a = new ElderAdmission();
        a.setElderId(elderId);
        a.setHospital(trim(req.hospital()));
        a.setDepartment(trim(req.department()));
        a.setBedNo(trim(req.bedNo()));
        a.setAdmitDate(admitDate);
        a.setDischargeDate(dischargeDate);
        a.setReason(trim(req.reason()));
        a.setStatus(dischargeDate == null ? "IN_HOSPITAL" : "DISCHARGED");
        a.setCreatedBy(UserContext.require().id());
        mapper.insert(a);

        if (dischargeDate == null) {
            syncCurrent(elderId, a);
        } else {
            a.setDischargeRecordId(findDischargeRecordId(elderId, dischargeDate));
            mapper.updateById(a);
        }
        audit.record("ADMISSION_CREATE", "ELDER_ADMISSION", a.getId(),
                (dischargeDate == null ? "登记住院：" : "补登住院记录：") + elderName(elderId));
        return toView(mapper.selectById(a.getId()));
    }

    /** 办理出院：写回出院日期，并把档案从"住院中"改回"居家随访"。 */
    @Transactional
    public Map<String, Object> discharge(Long admissionId, String dischargeDateText) {
        ElderAdmission a = mapper.selectById(admissionId);
        if (a == null) {
            throw new BizException(404, "住院记录不存在");
        }
        if ("DISCHARGED".equals(a.getStatus())) {
            throw new BizException(400, "这一笔已经办理过出院");
        }
        LocalDate date = dischargeDateText == null || dischargeDateText.isBlank()
                ? LocalDate.now() : parseDate(dischargeDateText, "出院日期");
        if (date.isBefore(a.getAdmitDate())) {
            throw new BizException(400, "出院日期不能早于入院日期");
        }
        a.setDischargeDate(date);
        a.setStatus("DISCHARGED");
        a.setDischargeRecordId(findDischargeRecordId(a.getElderId(), date));
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
        clearCurrent(a.getElderId());
        audit.record("ADMISSION_DISCHARGE", "ELDER_ADMISSION", admissionId,
                "办理出院：" + elderName(a.getElderId()));
        return toView(mapper.selectById(admissionId));
    }

    /**
     * 改一条住院记录。可改医院/科室/床号/入院-出院/原因；
     * 给在院的记录补上出院日期，等价于办理出院。
     */
    @Transactional
    public Map<String, Object> update(Long id, AdmissionRequest req) {
        ElderAdmission a = requireEditable(id);
        if (req == null) {
            throw new BizException(400, "没有要修改的内容");
        }
        if (req.hospital() != null) {
            a.setHospital(req.hospital().trim());
        }
        if (req.department() != null) {
            a.setDepartment(req.department().trim());
        }
        if (req.bedNo() != null) {
            a.setBedNo(req.bedNo().trim());
        }
        if (req.reason() != null) {
            a.setReason(req.reason().trim());
        }
        if (req.admitDate() != null && !req.admitDate().isBlank()) {
            a.setAdmitDate(parseDate(req.admitDate(), "入院日期"));
        }
        LocalDate discharge = a.getDischargeDate();
        if (req.dischargeDate() != null) {
            discharge = req.dischargeDate().isBlank() ? null : parseDate(req.dischargeDate(), "出院日期");
        }
        if (discharge != null && discharge.isBefore(a.getAdmitDate())) {
            throw new BizException(400, "出院日期不能早于入院日期");
        }
        if (discharge == null && "IN_HOSPITAL".equals(a.getStatus()) && current(a.getElderId()) != null
                && !current(a.getElderId()).getId().equals(a.getId())) {
            throw new BizException(400, "已经有一条在院记录，不能把这条也改成在院");
        }
        boolean wasInHospital = "IN_HOSPITAL".equals(a.getStatus());
        a.setDischargeDate(discharge);
        a.setStatus(discharge == null ? "IN_HOSPITAL" : "DISCHARGED");
        a.setUpdatedAt(LocalDateTime.now());
        if (discharge != null) {
            a.setDischargeRecordId(findDischargeRecordId(a.getElderId(), discharge));
        }
        mapper.updateById(a);

        if ("IN_HOSPITAL".equals(a.getStatus())) {
            syncCurrent(a.getElderId(), a);
        } else if (wasInHospital) {
            clearCurrent(a.getElderId());
        }
        audit.record("ADMISSION_UPDATE", "ELDER_ADMISSION", id,
                "修改住院记录：" + elderName(a.getElderId()));
        return toView(mapper.selectById(id));
    }

    /** 作废：录错了不能物理删，改成 VOID 并留原因。 */
    @Transactional
    public Map<String, Object> voidAdmission(Long id, String reason) {
        ElderAdmission a = mapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "住院记录不存在");
        }
        if ("VOID".equals(a.getStatus())) {
            throw new BizException(400, "这一笔已经作废过了");
        }
        boolean wasInHospital = "IN_HOSPITAL".equals(a.getStatus());
        a.setStatus("VOID");
        a.setVoidReason(reason == null || reason.isBlank() ? "录入有误" : reason.trim());
        a.setVoidedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
        if (wasInHospital) {
            clearCurrent(a.getElderId());
        }
        audit.record("ADMISSION_VOID", "ELDER_ADMISSION", id,
                "作废住院记录：" + elderName(a.getElderId()) + "｜" + a.getVoidReason());
        return toView(mapper.selectById(id));
    }

    /** 每位老人的住院统计：次数与累计天数（不含作废）。 */
    public List<Map<String, Object>> stats() {
        List<ElderAdmission> all = mapper.selectList(Wrappers.<ElderAdmission>lambdaQuery()
                .ne(ElderAdmission::getStatus, "VOID"));
        Map<Long, int[]> acc = new HashMap<>();
        for (ElderAdmission a : all) {
            int[] v = acc.computeIfAbsent(a.getElderId(), k -> new int[2]);
            v[0] += 1;
            LocalDate end = a.getDischargeDate() == null ? LocalDate.now() : a.getDischargeDate();
            v[1] += (int) (java.time.temporal.ChronoUnit.DAYS.between(a.getAdmitDate(), end) + 1);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : acc.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("elderId", e.getKey());
            m.put("count", e.getValue()[0]);
            m.put("days", e.getValue()[1]);
            rows.add(m);
        }
        return rows;
    }

    private ElderAdmission requireEditable(Long id) {
        ElderAdmission a = mapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "住院记录不存在");
        }
        if ("VOID".equals(a.getStatus())) {
            throw new BizException(400, "已作废的记录不能再改");
        }
        return a;
    }

    /** 按老人 + 出院日期找对应的出院小结，找不到就返回 null。 */
    private Long findDischargeRecordId(Long elderId, LocalDate dischargeDate) {
        DischargeRecord r = dischargeRecords.selectOne(Wrappers.<DischargeRecord>lambdaQuery()
                .eq(DischargeRecord::getElderId, elderId)
                .eq(DischargeRecord::getDischargeDate, dischargeDate)
                .orderByDesc(DischargeRecord::getId)
                .last("limit 1"));
        return r == null ? null : r.getId();
    }
    private void syncCurrent(Long elderId, ElderAdmission a) {
        ElderProfile e = elders.selectById(elderId);
        if (e == null) {
            return;
        }
        e.setCareStatus("HOSPITALIZED");
        e.setHospital(a.getHospital());
        e.setDepartment(a.getDepartment());
        e.setBedNo(a.getBedNo());
        e.setAdmitDate(a.getAdmitDate());
        e.setUpdatedAt(LocalDateTime.now());
        elders.updateById(e);
    }

    private void clearCurrent(Long elderId) {
        ElderProfile e = elders.selectById(elderId);
        if (e == null) {
            return;
        }
        e.setCareStatus("HOME");
        e.setBedNo("");
        e.setHospital("");
        e.setDepartment("");
        e.setAdmitDate(null);
        e.setUpdatedAt(LocalDateTime.now());
        elders.updateById(e);
    }

    private String elderName(Long elderId) {
        ElderProfile e = elders.selectById(elderId);
        return e == null ? ("#" + elderId) : e.getName();
    }

    public Map<String, Object> toView(ElderAdmission a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("elderId", a.getElderId());
        m.put("hospital", a.getHospital());
        m.put("department", a.getDepartment());
        m.put("bedNo", a.getBedNo());
        m.put("admitDate", a.getAdmitDate());
        m.put("dischargeDate", a.getDischargeDate());
        m.put("reason", a.getReason());
        m.put("status", a.getStatus());
        m.put("dischargeRecordId", a.getDischargeRecordId());
        m.put("voidReason", a.getVoidReason());
        m.put("voidedAt", a.getVoidedAt());
        m.put("days", a.getDischargeDate() == null
                ? null
                : java.time.temporal.ChronoUnit.DAYS.between(a.getAdmitDate(), a.getDischargeDate()) + 1);
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    private LocalDate parseDate(String text, String label) {
        try {
            return LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            throw new BizException(400, label + "格式应为 YYYY-MM-DD");
        }
    }
}
