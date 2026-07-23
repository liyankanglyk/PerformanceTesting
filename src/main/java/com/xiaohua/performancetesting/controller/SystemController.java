package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.OperationLog;
import com.xiaohua.performancetesting.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "System", description = "系统接口 — 运行状态监控与操作日志（需管理员 Token）")
@RestController
@RequestMapping("/api/admin")
public class SystemController {

    private final OperationLogService logService;

    public SystemController(OperationLogService logService) {
        this.logService = logService;
    }

    @Operation(
        summary = "系统运行状态",
        description = "获取服务器实时运行状态，包括：运行时间、CPU核心数、堆内存使用/上限、非堆内存使用、线程数（当前/峰值）、JVM内存等。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("heapUsed", memoryBean.getHeapMemoryUsage().getUsed());
        info.put("heapMax", memoryBean.getHeapMemoryUsage().getMax());
        info.put("nonHeapUsed", memoryBean.getNonHeapMemoryUsage().getUsed());
        info.put("threadCount", threadBean.getThreadCount());
        info.put("peakThreadCount", threadBean.getPeakThreadCount());
        info.put("totalMemory", runtime.totalMemory());
        info.put("freeMemory", runtime.freeMemory());
        info.put("maxMemory", runtime.maxMemory());

        return Result.ok(info);
    }

    @Operation(
        summary = "操作日志列表",
        description = "获取最近 100 条管理员操作日志，支持按关键词搜索。记录包含：操作人、操作类型（CREATE_USER/UPDATE_USER/DELETE_USER/CREATE_GOODS/UPDATE_GOODS/DELETE_GOODS/PAY_ORDER/ADMIN_LOGIN）、操作详情、操作时间。"
    )
    @GetMapping("/logs")
    public Result<List<OperationLog>> logs(@RequestParam(required = false) String keyword) {
        return Result.ok(logService.recent(100, keyword));
    }
}
