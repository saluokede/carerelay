package com.carerelay.admission.dto;

/** 登记一次住院。dischargeDate 为空表示"现在就在院"。 */
public record AdmissionRequest(
        String hospital,
        String department,
        String bedNo,
        String admitDate,
        String dischargeDate,
        String reason) {
}
