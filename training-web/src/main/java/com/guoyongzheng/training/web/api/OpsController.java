package com.guoyongzheng.training.web.api;

import com.guoyongzheng.training.web.service.OpsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维工作台接口：把《Linux 线上操作命令与排查实战》的常用命令、
 * 排查步骤与监控工具整合成只读接口，供排障训练直接调用。
 *
 * <p>安全：所有命令走白名单（OpsService 内部校验），不接受任意命令输入；
 * 每条命令独立进程 + 超时，不阻塞 HTTP 线程。
 */
@Tag(name = "运维工作台 Ops", description = "系统资源快照 / JVM 诊断 / 日志检索 / 命令速查 / 排查决策树")
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    @Operation(summary = "系统资源全景四件套（CPU/内存/磁盘/网络 + JVM 一次拉全）")
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return opsService.overview();
    }

    @Operation(summary = "CPU：top/mpstat/vmstat/负载")
    @GetMapping("/cpu")
    public Map<String, Object> cpu() {
        return opsService.cpu();
    }

    @Operation(summary = "内存：free/meminfo/进程 RSS")
    @GetMapping("/memory")
    public Map<String, Object> memory() {
        return opsService.memory();
    }

    @Operation(summary = "磁盘：df/du 大目录/已删未释放句柄")
    @GetMapping("/disk")
    public Map<String, Object> disk() {
        return opsService.disk();
    }

    @Operation(summary = "网络：连接态统计/CLOSE_WAIT/TIME_WAIT/监听端口")
    @GetMapping("/network")
    public Map<String, Object> network() {
        return opsService.network();
    }

    @Operation(summary = "JVM 诊断（本进程）：堆/非堆/GC/uptime")
    @GetMapping("/jvm")
    public Map<String, Object> jvm() {
        return opsService.jvm();
    }

    @Operation(summary = "线程诊断（本进程）：线程总数/状态分布/死锁检测")
    @GetMapping("/threads")
    public Map<String, Object> threads() {
        return opsService.threads();
    }

    @Operation(summary = "日志检索（白名单目录 app.log/diag.log）")
    @GetMapping("/logs")
    public Map<String, Object> logs(
            @RequestParam(name = "level", defaultValue = "") String level,
            @RequestParam(name = "lines", defaultValue = "100") int lines) {
        return opsService.logs(level, lines);
    }

    @Operation(summary = "运维命令速查表 + 速记数字表 + 三道分水岭题")
    @GetMapping("/guide")
    public Map<String, Object> guide() {
        return opsService.guide();
    }

    @Operation(summary = "五条排查决策树")
    @GetMapping("/decision")
    public Map<String, Object> decision() {
        return opsService.decision();
    }
}
