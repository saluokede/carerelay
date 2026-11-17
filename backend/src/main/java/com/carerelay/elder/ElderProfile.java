package com.carerelay.elder;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 出院老人档案。code 是站内编号（CR-0001），对外展示不暴露数据库 id。 */
@Data
@TableName("elder_profile")
public class ElderProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;

    /** MALE / FEMALE / UNKNOWN */
    private String gender;

    private LocalDate birthDate;
    private String phone;
    private String address;

    /** ALONE / SPOUSE / CHILDREN / FACILITY / UNKNOWN */
    private String livingStatus;

    private String caregiverName;
    private String caregiverPhone;

    private String chronicJson;
    private String allergyJson;

    /** 责任护士 app_user.id */
    private Long nurseId;

    /** HOME 居家随访 / HOSPITALIZED 住院中 / CLOSED 已结案 */
    private String careStatus;

    /** 床号，住院中才有意义 */
    private String bedNo;

    /** 当前住院或最近出院的医院 */
    private String hospital;

    /** 科室 */
    private String department;

    /** 最近一次入院日期 */
    private LocalDate admitDate;

    /** ACTIVE / CLOSED */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
