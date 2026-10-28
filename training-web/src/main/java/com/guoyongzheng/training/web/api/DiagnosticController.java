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
    private final com.guoyongzheng.training.web.service.TokenBucketRateLimiter rateLimiter;

    public DiagnosticController(DiagnosticService service,
                                 com.guoyongzheng.training.web.service.TokenBucketRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
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

    @Operation(summary = "Trigger heap dump via jcmd")
    @GetMapping("/heap-dump")
    public Map<String, Object> heapDump() {
        return service.heapDump();
    }

    @Operation(summary = "17 Unbounded queue thread pool (task pileup)")
    @GetMapping("/threadpool-unbounded")
    public Map<String, Object> threadpoolUnbounded(@RequestParam(name = "tasks", defaultValue = "500") int tasks,
                                                    @RequestParam(name = "slowMs", defaultValue = "100") int slowMs) throws Exception {
        return service.threadpoolUnbounded(tasks, slowMs);
    }

    @Operation(summary = "18 Thread pool starvation (all threads busy, new task waits)")
    @GetMapping("/threadpool-starve")
    public Map<String, Object> threadpoolStarve(@RequestParam(name = "holdSeconds", defaultValue = "10") int holdSeconds) throws Exception {
        return service.threadpoolStarve(holdSeconds);
    }

    @Operation(summary = "19 Token bucket rate limiter burst test")
    @GetMapping("/rate-limit")
    public Map<String, Object> rateLimit(@RequestParam(name = "n", defaultValue = "50") int n) {
        return rateLimiter.burst(n);
    }

    @Operation(summary = "20 Show traceId + logback file output")
    @GetMapping("/trace-demo")
    public Map<String, Object> traceDemo() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("case", "20 traceId + logback 文件落盘");
        m.put("rootCause", "MDC Filter 给每个请求生成 traceId，logback pattern %X{traceId} 打印，同时落盘到 /var/log/training-center/app.log 和 diag.log");
        m.put("observed", "看响应头 X-Trace-Id；grep traceId /var/log/training-center/diag.log");
        m.put("howToVerify", "curl -i 看响应头；grep <traceId> /var/log/training-center/diag.log");
        return m;
    }

    @Operation(summary = "16a OOM Java heap space (fills heap, triggers auto heap dump then exit)")
    @GetMapping("/oom-heap")
    public Map<String, Object> oomHeap() {
        return service.oomHeap();
    }

    @Operation(summary = "16b OOM Direct buffer memory (off-heap)")
    @GetMapping("/oom-direct")
    public Map<String, Object> oomDirect() {
        return service.oomDirect();
    }

    @Operation(summary = "16c OOM Unable to create new native thread")
    @GetMapping("/oom-thread")
    public Map<String, Object> oomThread() {
        return service.oomThread();
    }

    @Operation(summary = "16d OOM GC overhead limit exceeded")
    @GetMapping("/oom-gc-overhead")
    public Map<String, Object> oomGcOverhead() {
        return service.oomGcOverhead();
    }

    @Operation(summary = "21 CLOSE_WAIT connection leak (server accepts but never closes)")
    @GetMapping("/conn-leak")
    public Map<String, Object> connLeak(@RequestParam(name = "mode", defaultValue = "start") String mode,
                                        @RequestParam(name = "sockets", defaultValue = "50") int sockets) throws Exception {
        return service.connLeak(mode, sockets);
    }

    @Operation(summary = "22 Short-lived connections piling up TIME_WAIT")
    @GetMapping("/short-conn")
    public Map<String, Object> shortConn(@RequestParam(name = "n", defaultValue = "100") int n) throws Exception {
        return service.shortConn(n);
    }

    @Operation(summary = "23 Deleted-but-held fd (lsof | grep deleted)")
    @GetMapping("/deleted-fd")
    public Map<String, Object> deletedFd(@RequestParam(name = "mode", defaultValue = "start") String mode,
                                         @RequestParam(name = "mb", defaultValue = "64") int mb) throws Exception {
        return service.deletedFd(mode, mb);
    }

    @Operation(summary = "24 Disk fill drill (⚠️ cloud server only)")
    @GetMapping("/disk-full")
    public Map<String, Object> diskFull(@RequestParam(name = "mode", defaultValue = "start") String mode,
                                        @RequestParam(name = "mb", defaultValue = "1024") int mb) throws Exception {
        return service.diskFull(mode, mb);
    }

    @Operation(summary = "25 Network latency injection via tc netem (⚠️ cloud server only, affects port 8080 only)")
    @GetMapping("/net-latency")
    public Map<String, Object> netLatency(@RequestParam(name = "mode", defaultValue = "on") String mode,
                                          @RequestParam(name = "delayMs", defaultValue = "200") int delayMs) throws Exception {
        return service.netLatency(mode, delayMs);
    }

    @Operation(summary = "26 Disk IO spike (background writer, %util / wa / D-state)")
    @GetMapping("/io-spike")
    public Map<String, Object> ioSpike(@RequestParam(name = "mode", defaultValue = "start") String mode,
                                       @RequestParam(name = "mb", defaultValue = "200") int mb,
                                       @RequestParam(name = "seconds", defaultValue = "10") int seconds) throws Exception {
        return service.ioSpike(mode, mb, seconds);
    }

    @Operation(summary = "27 Lock contention (many threads BLOCKED on one lock)")
    @GetMapping("/lock-contention")
    public Map<String, Object> lockContention(@RequestParam(name = "threads", defaultValue = "30") int threads,
                                              @RequestParam(name = "ms", defaultValue = "3000") int ms) throws Exception {
        return service.lockContention(threads, ms);
    }
}
