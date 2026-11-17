package com.carerelay.elder.dto;

import java.util.List;

/** 建档入参。chronic/allergy 直接收数组，落库时序列化成 JSON。 */
public record CreateElderRequest(
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
        Long nurseId) {
}
