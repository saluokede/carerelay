package com.carerelay.elder.dto;

import java.util.List;

/**
 * 修改老人档案。所有字段都可为 null，表示"这一项不改"。
 *
 * <p>为什么用"可空即不改"而不是整份覆盖：护士经常只补一个床号或改一次状态，
 * 整份覆盖会把其他字段清空（这个坑在出院档案上已经踩过一次）。</p>
 */
public record UpdateElderRequest(
        String name,
        String gender,
        String birthDate,
        String phone,
        String address,
        String livingStatus,
        String caregiverName,
        String caregiverPhone,
        List<String> chronic,
        List<String> allergy,
        Long nurseId,
        String careStatus,
        String bedNo,
        String hospital,
        String department,
        String admitDate) {
}
