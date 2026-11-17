package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.checkin.CheckinService;
import com.carerelay.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "执行环 · 打卡与判读")
@RestController
@RequestMapping("/api/v1/checkins")
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @Operation(summary = "家属端：今天要做什么")
    @GetMapping("/today")
    public ApiResult<Map<String, Object>> today(@RequestParam Long elderId,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UserContext.require();
        return ApiResult.ok(checkinService.today(elderId, date));
    }

    @Operation(summary = "提交打卡（数值走规则，文字走模型，照片排队目视）")
    @PostMapping
    public ApiResult<Map<String, Object>> submit(@RequestBody CheckinService.SubmitRequest request) {
        UserContext.require();
        return ApiResult.ok(checkinService.submit(request));
    }

    @Operation(summary = "打卡记录")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam Long elderId,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UserContext.require();
        return ApiResult.ok(checkinService.listByElder(elderId, date));
    }
}
