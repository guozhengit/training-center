package com.guoyongzheng.training.web.api;

import com.guoyongzheng.training.web.service.DiagnosticService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 故障注入/排障验证端点：一个案例一个接口，通过 HTTP 触发真实生产故障现象。
 * 所有端点带安全规模参数，默认值在小内存机器上安全。
 */
@Tag(name = "Diagnostic", description = "Fault-injection endpoints for JVM production incident drills")
@RestController
@RequestMapping("/api/diag")
public class DiagnosticController {

    private final DiagnosticService service;

    public DiagnosticController(DiagnosticService service) {
        this.service = service;
    }

    @Operation(summary = "List all diagnostic cases")
    @GetMapping("/desc")
    public Map<String, Object> desc() {
        return service.catalogue();
    }

    @Operation(summary = "01 ReDoS catastrophic backtracking")
    @GetMapping("/redos")
    public Map<String, Object> redos(@RequestParam(name = "len", defaultValue = "40") int len) throws Exception {
        return service.redos(len);
    }

    @Operation(summary = "02 Large object toString into logs")
    @GetMapping("/biglog")
    public Map<String, Object> bigLog(@RequestParam(name = "size", defaultValue = "2000") int size) {
        return service.bigLog(size);
    }

    @Operation(summary = "03 Thread-pool parent-child self-deadlock")
    @GetMapping("/pool-deadlock")
    public Map<String, Object> poolDeadlock(@RequestParam(name = "poolSize", defaultValue = "8") int poolSize) throws Exception {
        return service.poolDeadlock(poolSize);
    }

    @Operation(summary = "05 Shared SimpleDateFormat concurrency")
    @GetMapping("/sdf")
    public Map<String, Object> sdf(@RequestParam(name = "threads", defaultValue = "30") int threads) throws Exception {
        return service.sdfConcurrent(threads);
    }

    @Operation(summary = "06 Slow SQL via pg_sleep")
    @GetMapping("/slow-sql")
    public Map<String, Object> slowSql(@RequestParam(name = "seconds", defaultValue = "3") int seconds) throws Exception {
        return service.slowSql(seconds);
    }

    @Operation(summary = "08 N+1 loop query")
    @GetMapping("/nplus1")
    public Map<String, Object> nplus1(@RequestParam(name = "n", defaultValue = "50") int n) throws Exception {
        return service.nplus1(n);
    }

    @Operation(summary = "10 Long blocking call holding threads")
    @GetMapping("/block-call")
    public Map<String, Object> blockCall(@RequestParam(name = "seconds", defaultValue = "5") int seconds) throws Exception {
        return service.blockCall(seconds);
    }

    @Operation(summary = "11 ThreadLocal leak / cross-request bleed")
    @GetMapping("/threadlocal-leak")
    public Map<String, Object> threadLocalLeak(@RequestParam(name = "iterations", defaultValue = "5000") int iterations) {
        return service.threadLocalLeak(iterations);
    }

    @Operation(summary = "12 Off-heap (DirectByteBuffer) allocation")
    @GetMapping("/direct-buffer")
    public Map<String, Object> directBuffer(@RequestParam(name = "mb", defaultValue = "32") int mb) {
        return service.directBuffer(mb);
    }

    @Operation(summary = "13 Oversize result set OOM pressure")
    @GetMapping("/oversize")
    public Map<String, Object> oversize(@RequestParam(name = "rows", defaultValue = "500000") int rows) {
        return service.oversize(rows);
    }

    @Operation(summary = "14 GC allocation pressure")
    @GetMapping("/gc-pressure")
    public Map<String, Object> gcPressure(@RequestParam(name = "mb", defaultValue = "80") int mb) throws Exception {
        return service.gcPressure(mb);
    }

    @Operation(summary = "15 Classic two-thread deadlock")
    @GetMapping("/deadlock")
    public Map<String, Object> deadlock() throws Exception {
        return service.deadlock();
    }
}
