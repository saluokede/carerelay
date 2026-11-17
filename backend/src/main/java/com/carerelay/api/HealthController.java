package com.carerelay.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 存活探针，给部署脚本与评委自检用。 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("app", "医养接力站 CareRelay");
        out.put("version", "0.1.0");
        out.put("time", OffsetDateTime.now().toString());
        return out;
    }
}
