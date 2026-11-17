package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.checkin.TaskSweeper;
import com.carerelay.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 执行环的"兜底"：把逾期未做的任务标成 MISSED，并按连续漏做次数升级。
 *
 * <p>定时任务每小时跑一次；这个接口是给演示和人工补跑用的，效果完全一样。</p>
 */
@Tag(name = "执行环 · 逾期扫描")
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskSweeper sweeper;

    public TaskController(TaskSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Operation(summary = "手动跑一次逾期扫描（医护）")
    @PostMapping("/sweep")
    public ApiResult<Map<String, Object>> sweep() {
        UserContext.require();
        return ApiResult.ok(sweeper.sweepOnce());
    }
}
