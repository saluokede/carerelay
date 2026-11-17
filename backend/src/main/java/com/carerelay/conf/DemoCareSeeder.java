package com.carerelay.conf;

import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.mapper.ElderProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 演示数据：3 位老人 + 各自已结构化的出院小结。
 *
 * <p>为什么必须预置结构化出院小结：没有模型 Key 时抽取会降级成手工填写，
 * 演示就没有内容可看。这里放的是**人工核对过的样例**（相当于护士已经复核过的状态），
 * 让"评分 → 计划 → 签发 → 任务"这条链在没有 Key 的情况下也能完整演示。</p>
 *
 * <p>幂等：elder_profile 已有数据就跳过。</p>
 */
@Slf4j
@Component
public class DemoCareSeeder implements ApplicationRunner {

    private final ElderProfileMapper elderMapper;
    private final DischargeRecordMapper recordMapper;

    public DemoCareSeeder(ElderProfileMapper elderMapper, DischargeRecordMapper recordMapper) {
        this.elderMapper = elderMapper;
        this.recordMapper = recordMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = elderMapper.selectCount(null);
        if (existing != null && existing > 0) {
            log.info("老人档案已有 {} 条，跳过演示数据初始化", existing);
            return;
        }

        // 1) 脑梗死 + 高血压 + 糖尿病，与家属同住，带尿管：中高风险
        ElderProfile wang = elder("CR-0001", "王秀兰", "FEMALE", "1948-03-12", "13800010001",
                "阳光街道 3 号楼 2 单元 501", "CHILDREN", "张秀英（女儿）", "13800010002", 2L,
                """
                ["脑梗死后遗症","高血压 2 级","2 型糖尿病"]""",
                """
                ["磺胺类药物"]""");
        elderMapper.insert(wang);
        record(wang.getId(), "某某市第一人民医院", "神经内科", LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 28),
                "脑梗死（左侧基底节区）",
                """
                ["脑梗死（左侧基底节区）","高血压 3 级 极高危","2 型糖尿病"]""",
                """
                [{"name":"阿司匹林肠溶片","dose":"100mg","frequency":"qd","route":"口服","duration":"长期","note":"餐后"},
                 {"name":"阿托伐他汀钙片","dose":"20mg","frequency":"qn","route":"口服","duration":"长期","note":null},
                 {"name":"苯磺酸氨氯地平片","dose":"5mg","frequency":"qd","route":"口服","duration":"长期","note":null},
                 {"name":"二甲双胍缓释片","dose":"0.5g","frequency":"bid","route":"口服","duration":"长期","note":"随餐"}]""",
                """
                [{"type":"留置导尿管","care":"每 7 天更换，注意会阴清洁，观察尿液颜色"}]""",
                "{\"present\":false,\"site\":null,\"care\":null,\"dressing_change\":null}",
                """
                ["低盐低脂糖尿病饮食","每日食盐 <5g","控制精制碳水"]""",
                """
                ["左侧肢体偏瘫，需助行器","家属陪同下每日步行 2 次，每次 10 分钟"]""",
                """
                {"date":"2026-11-11","department":"神经内科门诊","contact":"0571-8888xxxx","items":["复查头颅 CT","评估肢体功能"]}""",
                """
                [{"symptom":"言语不清、口角歪斜、肢体无力加重","action":"立即就诊或拨打 120"}]""");

        // 2) 髋部骨折术后，独居，伤口 + 多重用药：高风险
        ElderProfile li = elder("CR-0002", "李国强", "MALE", "1942-07-02", "13800020001",
                "阳光街道 7 号楼 1 单元 202", "ALONE", "李建军（儿子，外地）", "13800020002", 2L,
                """
                ["骨质疏松症","慢性胃炎","前列腺增生"]""",
                "[]");
        elderMapper.insert(li);
        record(li.getId(), "某某市第二人民医院", "骨科", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 31),
                "右侧股骨颈骨折术后",
                """
                ["右侧股骨颈骨折术后","骨质疏松症","慢性胃炎","前列腺增生"]""",
                """
                [{"name":"利伐沙班片","dose":"10mg","frequency":"qd","route":"口服","duration":"30 天","note":"防血栓"},
                 {"name":"碳酸钙 D3 片","dose":"600mg","frequency":"qd","route":"口服","duration":"长期","note":null},
                 {"name":"奥美拉唑肠溶胶囊","dose":"20mg","frequency":"qd","route":"口服","duration":"4 周","note":"餐前"},
                 {"name":"坦索罗辛缓释胶囊","dose":"0.2mg","frequency":"qn","route":"口服","duration":"长期","note":null},
                 {"name":"塞来昔布胶囊","dose":"0.2g","frequency":"qd","route":"口服","duration":"2 周","note":"必要时"}]""",
                "[]",
                """
                {"present":true,"site":"右髋手术切口","care":"保持干燥，观察有无红肿渗液","dressing_change":"每 3 天换药一次"}""",
                """
                ["高蛋白饮食促进愈合","补充维生素 D"]""",
                """
                ["术后 6 周内避免患侧负重","助行器辅助下床，需有人陪护"]""",
                """
                {"date":"2026-11-14","department":"骨科门诊","contact":"0571-6666xxxx","items":["拍片复查","评估负重时间"]}""",
                """
                [{"symptom":"切口红肿、渗液增多或发热","action":"当天联系手术医院或社区"},
                 {"symptom":"突发胸闷、气促、单侧小腿肿痛","action":"警惕血栓，立即就医"}]""");

        // 3) 胆囊切除术后，与配偶同住，恢复良好：低风险
        ElderProfile chen = elder("CR-0003", "陈美英", "FEMALE", "1957-11-20", "13800030001",
                "阳光街道 1 号楼 3 单元 101", "SPOUSE", "王德明（配偶）", "13800030002", 2L,
                """
                ["2 型糖尿病","高脂血症"]""",
                """
                ["青霉素类"]""");
        elderMapper.insert(chen);
        record(chen.getId(), "某某市第一人民医院", "肝胆外科", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 1),
                "胆囊结石伴慢性胆囊炎（腹腔镜胆囊切除术后）",
                """
                ["胆囊结石伴慢性胆囊炎","脂肪肝"]""",
                """
                [{"name":"熊去氧胆酸胶囊","dose":"250mg","frequency":"bid","route":"口服","duration":"1 个月","note":"随餐"},
                 {"name":"头孢克洛缓释片","dose":"0.5g","frequency":"bid","route":"口服","duration":"5 天","note":null}]""",
                "[]",
                """
                {"present":true,"site":"腹部 4 个小切口","care":"保持敷料干燥，可淋浴不可盆浴","dressing_change":"术后 7 天拆线"}""",
                """
                ["低脂饮食","少量多餐","避免油炸"]""",
                """
                ["术后 2 周内避免提重物（>5kg）","每日散步 20 分钟"]""",
                """
                {"date":"2026-11-08","department":"肝胆外科门诊","contact":"0571-9999xxxx","items":["拆线","复查肝功能"]}""",
                """
                [{"symptom":"腹痛加剧、发热、皮肤发黄","action":"及时就医"}]""");

        log.info("已初始化 3 位演示老人与对应出院小结");
    }

    private ElderProfile elder(String code, String name, String gender, String birth, String phone,
                               String address, String living, String caregiver, String caregiverPhone, Long nurseId,
                               String chronicJson, String allergyJson) {
        ElderProfile e = new ElderProfile();
        e.setCode(code);
        e.setName(name);
        e.setGender(gender);
        e.setBirthDate(LocalDate.parse(birth));
        e.setPhone(phone);
        e.setAddress(address);
        e.setLivingStatus(living);
        e.setCaregiverName(caregiver);
        e.setCaregiverPhone(caregiverPhone);
        e.setChronicJson(chronicJson);
        e.setAllergyJson(allergyJson);
        e.setNurseId(nurseId);
        e.setStatus("ACTIVE");
        return e;
    }

    private void record(Long elderId, String hospital, String department, LocalDate admit, LocalDate discharge,
                        String diagnosis, String diagnoses, String medications, String tubes, String wound,
                        String diet, String activity, String followup, String redFlags) {
        DischargeRecord r = new DischargeRecord();
        r.setElderId(elderId);
        r.setHospital(hospital);
        r.setDepartment(department);
        r.setAdmitDate(admit);
        r.setDischargeDate(discharge);
        r.setDiagnosisPrimary(diagnosis);
        r.setDiagnosesJson(diagnoses.strip());
        r.setSurgeryJson("[]");
        r.setMedicationsJson(medications.strip());
        r.setTubesJson(tubes.strip());
        r.setWoundJson(wound.strip());
        r.setDietJson(diet.strip());
        r.setActivityJson(activity.strip());
        r.setFollowupJson(followup.strip());
        r.setRedFlagsJson(redFlags.strip());
        r.setSourceText("（演示数据：结构化结果相当于护士已复核过的状态）");
        r.setSourceUrl("");
        r.setExtractStatus("PARSED");
        r.setExtractConfidence("{}");
        r.setCreatedBy(2L);
        recordMapper.insert(r);
    }
}
