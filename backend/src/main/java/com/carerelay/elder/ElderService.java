package com.carerelay.elder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.common.BizException;
import com.carerelay.common.JsonText;
import com.carerelay.audit.AuditService;
import com.carerelay.elder.dto.CreateElderRequest;
import com.carerelay.elder.dto.UpdateElderRequest;
import com.carerelay.elder.mapper.ElderProfileMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 老人档案：建档、检索、详情。编号由代码生成，不依赖数据库自增 id 对外展示。 */
@Service
public class ElderService {

    private final ElderProfileMapper mapper;
    private final AuditService audit;

    public ElderService(ElderProfileMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public Map<String, Object> create(CreateElderRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new BizException(400, "请填写老人姓名");
        }
        ElderProfile e = new ElderProfile();
        e.setName(req.name().trim());
        e.setGender(req.gender() == null ? "UNKNOWN" : req.gender());
        e.setBirthDate(parseDate(req.birthDate()));
        e.setPhone(nullToEmpty(req.phone()));
        e.setAddress(nullToEmpty(req.address()));
        e.setLivingStatus(req.livingStatus() == null ? "UNKNOWN" : req.livingStatus());
        e.setCaregiverName(nullToEmpty(req.caregiverName()));
        e.setCaregiverPhone(nullToEmpty(req.caregiverPhone()));
        e.setChronicJson(req.chronic() == null ? null : toJson(req.chronic()));
        e.setAllergyJson(req.allergy() == null ? null : toJson(req.allergy()));
        e.setNurseId(req.nurseId());
        e.setStatus("ACTIVE");
        e.setCareStatus("HOME");
        e.setCode(nextCode());
        mapper.insert(e);
        return toView(e);
    }

    public List<Map<String, Object>> list(String keyword) {
        var query = Wrappers.<ElderProfile>lambdaQuery().orderByDesc(ElderProfile::getId);
        if (keyword != null && !keyword.isBlank()) {
            query.like(ElderProfile::getName, keyword.trim());
        }
        return mapper.selectList(query).stream().map(this::toView).toList();
    }

    public ElderProfile require(Long id) {
        ElderProfile e = id == null ? null : mapper.selectById(id);
        if (e == null) {
            throw new BizException(404, "老人档案不存在");
        }
        return e;
    }

    public Map<String, Object> detail(Long id) {
        return toView(require(id));
    }

    /**
     * 改档案。只改传进来的字段（null = 不改），返回最新视图。
     *
     * <p>床位、医院、科室、入院日期这些字段护士是边问边补的，
     * 所以接口按"打补丁"设计，不做整份覆盖。</p>
     */
    public Map<String, Object> update(Long id, UpdateElderRequest req) {
        ElderProfile e = require(id);
        if (req == null) {
            throw new BizException(400, "没有要修改的内容");
        }
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty() || name.length() > 20) {
                throw new BizException(400, "姓名请控制在 1-20 个字");
            }
            e.setName(name);
        }
        if (req.gender() != null) {
            e.setGender(req.gender());
        }
        if (req.birthDate() != null) {
            e.setBirthDate(parseDate(req.birthDate()));
        }
        if (req.phone() != null) {
            e.setPhone(req.phone().trim());
        }
        if (req.address() != null) {
            e.setAddress(req.address().trim());
        }
        if (req.livingStatus() != null) {
            e.setLivingStatus(req.livingStatus());
        }
        if (req.caregiverName() != null) {
            e.setCaregiverName(req.caregiverName().trim());
        }
        if (req.caregiverPhone() != null) {
            e.setCaregiverPhone(req.caregiverPhone().trim());
        }
        if (req.chronic() != null) {
            e.setChronicJson(toJson(req.chronic()));
        }
        if (req.allergy() != null) {
            e.setAllergyJson(toJson(req.allergy()));
        }
        if (req.nurseId() != null) {
            e.setNurseId(req.nurseId());
        }
        if (req.careStatus() != null) {
            String status = req.careStatus().trim().toUpperCase();
            if (!status.equals("HOME") && !status.equals("HOSPITALIZED") && !status.equals("CLOSED")) {
                throw new BizException(400, "状态只能是 HOME / HOSPITALIZED / CLOSED");
            }
            e.setCareStatus(status);
        }
        if (req.bedNo() != null) {
            e.setBedNo(req.bedNo().trim());
        }
        if (req.hospital() != null) {
            e.setHospital(req.hospital().trim());
        }
        if (req.department() != null) {
            e.setDepartment(req.department().trim());
        }
        if (req.admitDate() != null) {
            e.setAdmitDate(parseDate(req.admitDate()));
        }
        e.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(e);
        audit.record("ELDER_UPDATE", "ELDER_PROFILE", id, "修改档案：" + e.getName());
        return toView(e);
    }

    public Map<String, Object> toView(ElderProfile e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.getId());
        view.put("code", e.getCode());
        view.put("name", e.getName());
        view.put("gender", e.getGender());
        view.put("age", JsonText.age(e.getBirthDate()));
        view.put("birthDate", e.getBirthDate());
        view.put("phone", e.getPhone());
        view.put("address", e.getAddress());
        view.put("livingStatus", e.getLivingStatus());
        view.put("caregiverName", e.getCaregiverName());
        view.put("caregiverPhone", e.getCaregiverPhone());
        view.put("chronic", JsonText.strings(e.getChronicJson(), null));
        view.put("allergy", JsonText.strings(e.getAllergyJson(), null));
        view.put("nurseId", e.getNurseId());
        view.put("status", e.getStatus());
        view.put("careStatus", e.getCareStatus());
        view.put("bedNo", e.getBedNo());
        view.put("hospital", e.getHospital());
        view.put("department", e.getDepartment());
        view.put("admitDate", e.getAdmitDate());
        return view;
    }

    private String nextCode() {
        for (int i = 1; i < 10000; i++) {
            String code = String.format("CR-%04d", i);
            Long exists = mapper.selectCount(Wrappers.<ElderProfile>lambdaQuery().eq(ElderProfile::getCode, code));
            if (exists == null || exists == 0) {
                return code;
            }
        }
        throw new BizException(500, "编号已用尽");
    }

    private String toJson(List<String> values) {
        return JsonText.of(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(values));
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            throw new BizException(400, "生日格式应为 YYYY-MM-DD");
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
