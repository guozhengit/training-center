package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.persistence.TrainingDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 线上排障故障注入：每个方法复现一个真实生产故障案例，供通过 HTTP 接口触发验证。
 * 所有方法带规模参数，默认值在小内存机器上安全；调大参数才会真正放大故障现象。
 * 统一日志格式：DIAG[案例号] start/done，便于 journalctl -f 实时观察。
 */
@Service
public class DiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticService.class);
    private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();

    private final TrainingDatabaseProvider databaseProvider;

    /** Spring 托管诊断线程池（17/18 场景共用 → executor_* 指标在 Prometheus 可见） */
    private final ThreadPoolTaskExecutor diagThreadPool;

    /** 21 CLOSE_WAIT 演练：持有未关闭的服务端 socket（收到 FIN 但不 close） */
    private final List<Socket> connLeakSockets = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 23 已删句柄未释放演练：持有已 unlink 文件的输出流 */
    private final List<FileOutputStream> deletedFdHolds = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 24 磁盘写满演练：已填充文件（mode=clean 时删除释放） */
    private final List<File> diskFillerFiles = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 26 IO 狂写演练：正在写入的文件（mode=stop 时删除释放） */
    private final List<File> ioSpikeFiles = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 27 锁竞争演练：共享锁对象 + 争抢线程数 */
    private final Object contentionLock = new Object();
    private volatile int contentionThreads = 0;

    public DiagnosticService(TrainingDatabaseProvider databaseProvider, ThreadPoolTaskExecutor diagThreadPool) {
        this.databaseProvider = databaseProvider;
        this.diagThreadPool = diagThreadPool;
    }

    /** 案例清单与说明。 */
    public Map<String, Object> catalogue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", "故障注入端点：/api/diag/<case>，参数默认安全，调大才复现真实故障");
        m.put("cases", List.of(
                Map.of("path", "redos", "case", "01 ReDoS 正则回溯", "param", "len（默认40，越大回溯越久）"),
                Map.of("path", "biglog", "case", "02 大对象 toString 日志", "param", "size（对象条数）"),
                Map.of("path", "pool-deadlock", "case", "03 线程池父子同池死锁", "param", "poolSize"),
                Map.of("path", "sdf", "case", "05 SimpleDateFormat 并发竞争", "param", "threads"),
                Map.of("path", "slow-sql", "case", "06 慢 SQL（pg_sleep）", "param", "seconds"),
                Map.of("path", "nplus1", "case", "08 N+1 循环内查库", "param", "n（循环次数）"),
                Map.of("path", "block-call", "case", "10 长阻塞占满线程", "param", "seconds（线程被占时长）"),
                Map.of("path", "threadlocal-leak", "case", "11 ThreadLocal 泄漏/串号", "param", "iterations"),
                Map.of("path", "direct-buffer", "case", "12 堆外内存泄漏", "param", "mb"),
                Map.of("path", "oversize", "case", "13/07 超大结果集 OOM", "param", "rows"),
                Map.of("path", "gc-pressure", "case", "14 GC 频繁/分配压力", "param", "mb"),
                Map.of("path", "deadlock", "case", "15 两线程反向加锁死锁", "param", "无（jstack 可见 BLOCKED）"),
                Map.of("path", "conn-leak", "case", "21 CLOSE_WAIT 连接泄漏", "param", "mode=start|close, sockets"),
                Map.of("path", "short-conn", "case", "22 TIME_WAIT 短连接堆积", "param", "n"),
                Map.of("path", "deleted-fd", "case", "23 已删句柄未释放", "param", "mode=start|clean, mb"),
                Map.of("path", "disk-full", "case", "24 磁盘写满演练（仅云服务器）", "param", "mode=start|clean, mb≤4096"),
                Map.of("path", "net-latency", "case", "25 网络延迟 tc netem（仅云服务器，仅8080）", "param", "mode=on|off, delayMs"),
                Map.of("path", "io-spike", "case", "26 磁盘 IO 狂写（%util/wa/D 状态）", "param", "mode=start|stop, mb, seconds"),
                Map.of("path", "lock-contention", "case", "27 锁竞争毛刺（BLOCKED 堆积）", "param", "threads, ms")
        ));
        return m;
    }

    // 01 ReDoS：嵌套量词指数级回溯
    public Map<String, Object> redos(int len) {
        log.info("DIAG[01-redos] start len={}", len);
        captureSnapshot("redos-" + len);
        long start = System.nanoTime();
        Pattern bad = Pattern.compile("^(\\w+)*$");
        String input = "a".repeat(Math.max(10, len)) + "!";
        boolean matched;
        try {
            matched = bad.matcher(input).matches();
        } catch (Exception e) {
            matched = false;
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[01-redos] done cost={}ms matched={} inputLen={}", ms, matched, input.length());
        return result("01 ReDoS",
                "嵌套量词 (\\w+)* 遇到 N 个重复字符+结尾不匹配，回溯路径 2^N",
                "输入长度=" + input.length() + "，耗时=" + ms + "ms（长度+5，耗时约翻 30 倍）",
                "top -Hp <pid> 找高 CPU tid；jstack 必见 java.util.regex.Pattern$Loop.match");
    }

    // 02 大对象 toString + 日志
    public Map<String, Object> bigLog(int size) {
        log.info("DIAG[02-biglog] start size={}", size);
        List<BigRow> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) rows.add(new BigRow(i, "payload-" + i, "x".repeat(2048)));
        long bytes = rows.stream().mapToLong(r -> r.toString().length()).sum();
        log.info("DIAG[02-biglog] done rows={} bytes~={}（注意：此行本身就是大对象日志的反例）", size, bytes);
        log.error("DIAG[02-biglog] 反例：本次为 ERROR 级别大日志（真实场景严禁在日志打印集合 toString）rows={} bytes~={}", size, bytes);
        return result("02 大对象日志",
                "log.info 里打印集合 toString：字符串拼接+格式化+写盘三重杀伤",
                "对象数=" + size + "，估算日志字节=" + bytes,
                "jstack 见 StringBuilder.append / MessageFormatter.format；arthas logger --name ... --level WARN 动态止血");
    }

    // 03 线程池父子同池死锁
    public Map<String, Object> poolDeadlock(int poolSize) throws Exception {
        log.info("DIAG[03-pool-deadlock] start poolSize={}", poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<?>> parents = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < poolSize; i++) {
            parents.add(pool.submit(() -> {
                try {
                    Future<?> child = pool.submit(() -> "child-" + Thread.currentThread().getName());
                    return child.get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return "timeout-deadlocked";
                }
            }));
        }
        List<Object> out = new ArrayList<>();
        for (Future<?> f : parents) out.add(f.get(5, TimeUnit.SECONDS));
        pool.shutdownNow();
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[03-pool-deadlock] done cost={}ms result={}", ms, out);
        return result("03 线程池死锁",
                "父任务占满线程后在同池提交子任务并 get()，子任务无线程可跑，永久等待",
                "poolSize=" + poolSize + "，耗时=" + ms + "ms，结果=" + out,
                "CPU 低但 RT 高；jstack 见 biz 线程 WAITING(parking) 在 FutureTask.awaitDone");
    }

    // 05 SimpleDateFormat 并发
    public Map<String, Object> sdfConcurrent(int threads) throws Exception {
        log.info("DIAG[05-sdf] start threads={}", threads);
        SimpleDateFormat shared = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        List<Future<String>> fs = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                try {
                    for (int j = 0; j < 2000; j++) {
                        shared.format(new Date());
                        shared.parse("2026-01-01 00:00:00");
                    }
                    return "ok";
                } catch (Exception e) {
                    errors.incrementAndGet();
                    return "err:" + e.getClass().getSimpleName();
                }
            }));
        }
        for (Future<String> f : fs) f.get();
        pool.shutdownNow();
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[05-sdf] done cost={}ms errors={}", ms, errors.get());
        return result("05 SimpleDateFormat 并发",
                "static SimpleDateFormat 非线程安全，并发下串行甚至结果错乱",
                "threads=" + threads + "，耗时=" + ms + "ms，errors=" + errors.get() + "（并发下抛 NumberFormatException/结果错乱）",
                "arthas thread -n 5 -i 500 抓短时毛刺；jstack 见多线程 BLOCKED 在 SimpleDateFormat.format/parse");
    }

    // 06 慢 SQL（PG pg_sleep）
    public Map<String, Object> slowSql(int seconds) throws Exception {
        log.info("DIAG[06-slow-sql] start seconds={}", seconds);
        captureSnapshot("slow-sql-" + seconds);
        long start = System.nanoTime();
        try (Connection c = databaseProvider.readyDatabase().openConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT pg_sleep(" + Math.max(0, seconds) + ")")) {
            if (rs.next()) rs.getString(1);
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[06-slow-sql] done cost={}ms", ms);
        return result("06 慢 SQL",
                "单条 SQL 占住连接 N 秒；并发打满会拖垮连接池→Tomcat 线程→全站",
                "pg_sleep=" + seconds + "s，实际=" + ms + "ms",
                "PG: SELECT * FROM pg_stat_activity WHERE state='active'；jstack 见 HikariPool.getConnection");
    }

    // 08 N+1
    public Map<String, Object> nplus1(int n) throws Exception {
        log.info("DIAG[08-nplus1] start n={}", n);
        long start = System.nanoTime();
        int realQueries = 0;
        try (Connection c = databaseProvider.readyDatabase().openConnection()) {
            for (int i = 0; i < n; i++) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 1")) {
                    rs.next();
                    realQueries++;
                }
            }
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[08-nplus1] done cost={}ms queries={}", ms, realQueries);
        return result("08 N+1",
                "循环内单条查库，N 次往返替代 1 次批量（IN 查询）",
                "循环=" + n + "，实际 SQL=" + realQueries + " 次，耗时=" + ms + "ms",
                "trace 同一 span 重复 N 次；改 WHERE id IN (...) + 内存 Map");
    }

    // 10 长阻塞占线程
    public Map<String, Object> blockCall(int seconds) throws Exception {
        log.info("DIAG[10-block-call] start seconds={}", seconds);
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[10-block-call] done cost={}ms", ms);
        return result("10 长阻塞占线程",
                "同步阻塞 RT=秒级，Tomcat 线程被长期占用，普通接口排队",
                "sleep=" + seconds + "s，耗时=" + ms + "ms（并发超过 200/RT 即打满）",
                "jstack 见大量 http-nio-exec RUNNABLE 在 socketRead0；改异步任务化+独立线程池");
    }

    // 11 ThreadLocal 泄漏 + 串号
    public Map<String, Object> threadLocalLeak(int iterations) {
        log.info("DIAG[11-threadlocal-leak] start iterations={}", iterations);
        for (int i = 0; i < iterations; i++) {
            CTX.set(new UserContext("user-" + i, new byte[8 * 1024]));   // 故意不 remove
        }
        UserContext leaked = CTX.get();
        String leakedUser = leaked == null ? "none" : leaked.userId;
        log.info("DIAG[11-threadlocal-leak] done leakedUser={}（注意：未 remove，线程复用会串号）", leakedUser);
        return result("11 ThreadLocal 泄漏/串号",
                "Filter 里 set 不 remove：池复用线程导致 value 常驻内存 + 下一请求拿到上一个用户身份",
                "iterations=" + iterations + "，当前线程残留用户=" + leakedUser,
                "jcmd GC.heap_dump 后 MAT 看 ThreadLocalMap；必须 finally { CTX.remove() }");
    }

    // 12 堆外内存
    public Map<String, Object> directBuffer(int mb) {
        log.info("DIAG[12-direct-buffer] start mb={}", mb);
        List<ByteBuffer> held = new ArrayList<>();
        int allocate = Math.min(mb, 128);
        for (int i = 0; i < allocate; i++) held.add(ByteBuffer.allocateDirect(1024 * 1024));
        log.info("DIAG[12-direct-buffer] done allocated={}MB（方法返回后 held 出栈即释放）", allocate);
        return result("12 堆外内存",
                "DirectByteBuffer 堆外分配，堆 dump 看不出来，但 cgroup 内存超 limit 被 OOMKilled(137)",
                "已分配=" + allocate + "MB（线程结束自动释放；持续分配才泄漏）",
                "jcmd VM.native_memory detail；-XX:MaxDirectMemorySize 必须显式设");
    }

    // 13 超大结果集
    public Map<String, Object> oversize(int rows) {
        log.info("DIAG[13-oversize] start rows={}", rows);
        int real = Math.min(rows, 2_000_000);
        long start = System.nanoTime();
        List<String> big = new ArrayList<>(real);
        for (int i = 0; i < real; i++) big.add("row-" + i + "-" + "x".repeat(64));
        long mb = big.stream().mapToLong(String::length).sum() / 1024 / 1024;
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[13-oversize] done cost={}ms rows={} ~{}MB", ms, real, mb);
        return result("13 超大结果集 OOM",
                "一次把海量行加载进堆；导出/列表查询必须分页或流式",
                "rows=" + real + "，约=" + mb + "MB，耗时=" + ms + "ms",
                "heap dump 见 ArrayList 占大；MyBatis Cursor + fetchSize，禁止 selectAll");
    }

    // 14 GC 压力
    public Map<String, Object> gcPressure(int mb) throws Exception {
        log.info("DIAG[14-gc-pressure] start mb={}", mb);
        long start = System.nanoTime();
        int chunks = Math.min(mb, 300);
        for (int i = 0; i < chunks; i++) {
            byte[] b = new byte[1024 * 1024];
            b.hashCode();
        }
        System.gc();
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[14-gc-pressure] done cost={}ms chunks={}（观察 jstat YGC 暴涨）", ms, chunks);
        return result("14 GC 频繁/分配压力",
                "高速分配短命对象，Eden 瞬满 → Young GC 频繁 → 过早晋升老年代",
                "分配=" + chunks + "MB，耗时=" + ms + "ms（观察 jstat -gcutil YGC 暴涨）",
                "jstat -gcutil <pid> 1s；JFR 找分配热点；优先改代码而非调大堆");
    }

    // 16 OOM 场景：4 种典型溢出（触发后 JVM 自动 heap dump 并退出，systemd 重启）
    // 16a Java heap space：不停往堆塞大对象直到 -Xmx 满

    public Map<String, Object> oomHeap() {
        log.info("DIAG[16a-oom-heap] start - 不停分配 byte[] 直到 Java heap space");
        List<byte[]> hold = new ArrayList<>();
        try {
            while (true) hold.add(new byte[1024 * 1024]);   // 每次 1MB
        } catch (OutOfMemoryError e) {
            log.info("DIAG[16a-oom-heap] caught: {}", e.getMessage());
            return result("16a OOM Java heap space",
                    "不停分配堆对象直到 -Xmx 满；OOM 时 HeapDumpOnOutOfMemoryError 自动 dump",
                    "异常=" + e.getMessage() + "；进程即将退出重启",
                    "看 /var/log/training-center/heapdump.hprof；jstat -gcutil 观察 OU 涨满");
        }
    }

    // 16b Direct buffer memory：不停分配堆外内存直到 MaxDirectMemorySize 满
    public Map<String, Object> oomDirect() {
        log.info("DIAG[16b-oom-direct] start - 不停 allocateDirect 直到 Direct buffer memory");
        List<ByteBuffer> hold = new ArrayList<>();
        try {
            while (true) hold.add(ByteBuffer.allocateDirect(1024 * 1024));
        } catch (OutOfMemoryError e) {
            log.info("DIAG[16b-oom-direct] caught: {}", e.getMessage());
            return result("16b OOM Direct buffer memory",
                    "堆外内存溢出（堆 dump 看不到）；cgroup 内存超限也会 OOMKilled(137)",
                    "异常=" + e.getMessage(),
                    "jcmd VM.native_memory detail；-XX:MaxDirectMemorySize 必须显式设");
        }
    }

    // 16c Unable to create new native thread：不停 new Thread 直到操作系统线程数上限
    public Map<String, Object> oomThread() {
        log.info("DIAG[16c-oom-thread] start - 不停 new Thread 直到无法创建");
        List<Thread> hold = new ArrayList<>();
        try {
            while (true) {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(600_000); } catch (InterruptedException ignored) {}
                });
                t.setDaemon(true);
                t.start();
                hold.add(t);
            }
        } catch (OutOfMemoryError e) {
            log.info("DIAG[16c-oom-thread] caught: {} threads={}", e.getMessage(), hold.size());
            return result("16c OOM Unable to create new native thread",
                    "线程数超操作系统上限（ulimit -u）或 -Xss 太大；不是堆问题",
                    "异常=" + e.getMessage() + "；线程数=" + hold.size(),
                    "jstack 看线程总数；ulimit -a；-Xss512k 减小栈；线程池必须有界");
        }
    }

    // 16d GC overhead limit exceeded：堆快满但每次 GC 回收极少，GC 占 98% CPU 以上
    public Map<String, Object> oomGcOverhead() {
        log.info("DIAG[16d-oom-gc-overhead] start - 填充堆到临界，制造 GC overhead");
        List<byte[]> hold = new ArrayList<>();
        try {
            // 先填到堆快满
            while (hold.size() < 300) hold.add(new byte[1024 * 1024]);
            // 再疯狂分配小对象，触发频繁 Young GC 但回收不掉（hold 里的还在）
            while (true) {
                for (int i = 0; i < 1000; i++) {
                    byte[] b = new byte[1024 * 64];
                    b.hashCode();
                }
                System.gc();
            }
        } catch (OutOfMemoryError e) {
            log.info("DIAG[16d-oom-gc-overhead] caught: {}", e.getMessage());
            return result("16d OOM GC overhead limit exceeded",
                    "GC 占 98%+ CPU 但回收不到 2%；通常是内存泄漏的前兆",
                    "异常=" + e.getMessage(),
                    "jstat -gcutil 看 YGC 暴涨但 OU 不降；heap dump 找泄漏源");
        }
    }

    // 17 无界队列线程池：Spring 托管共享池（无界队列）→ executor_queued_tasks 实时可见
    public Map<String, Object> threadpoolUnbounded(int tasks, int slowMs) throws Exception {
        log.info("DIAG[17-unbounded] start tasks={} slowMs={}", tasks, slowMs);
        ThreadPoolTaskExecutor pool = diagThreadPool;
        long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            pool.submit(() -> {
                try { Thread.sleep(slowMs); } catch (InterruptedException ignored) {}
                return "done";
            });
        }
        Thread.sleep(2000);
        java.util.concurrent.ThreadPoolExecutor tp = pool.getThreadPoolExecutor();
        int queueSize = tp.getQueue().size();
        int active = tp.getActiveCount();
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[17-unbounded] done submitted={} active={} queueSize={} cost={}ms (共享池，任务后台消化)", tasks, active, queueSize, ms);
        return result("17 无界队列线程池",
                "共享 ThreadPoolTaskExecutor（无界队列）：任务产生速度 > 消费速度时队列无限涨，最终 OOM",
                "提交=" + tasks + "，活跃=" + active + "，队列堆积=" + queueSize + "，耗时=" + ms + "ms",
                "Grafana Executor 面板看 executor_queued_tasks 飙升；jstack 见大量任务在 QUEUED；改有界队列 + CallerRunsPolicy");
    }

    // 18 线程池饥饿挂起：共享池 core=2 被长任务占满，新任务排队等不到，RT 无限涨
    public Map<String, Object> threadpoolStarve(int holdSeconds) throws Exception {
        log.info("DIAG[18-starve] start holdSeconds={}", holdSeconds);
        ThreadPoolTaskExecutor pool = diagThreadPool;
        long start = System.nanoTime();
        List<Future<?>> busy = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            busy.add(pool.submit(() -> {
                try { Thread.sleep(holdSeconds * 1000L); } catch (InterruptedException ignored) {}
                return "occupied";
            }));
        }
        Thread.sleep(500);
        long t0 = System.nanoTime();
        Future<String> queued = pool.submit(() -> "i waited");
        String result;
        long waitMs;
        try {
            result = queued.get(3, TimeUnit.SECONDS);
            waitMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        } catch (java.util.concurrent.TimeoutException te) {
            waitMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            result = "TIMEOUT-after-" + waitMs + "ms (thread pool full, new task queued)";
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[18-starve] done waitForNewTask={}ms result={} total={}ms (active=2 全被占满)", waitMs, result, ms);
        return result("18 线程池饥饿挂起",
                "共享池 2 个线程被长任务占满，新任务在队列排队，RT 无限涨（CPU 低但服务不可用）",
                "长任务占=" + holdSeconds + "s，新任务等待=" + waitMs + "ms，结果=" + result,
                "Grafana Executor 面板看 active=2 且 queued>0；jstack 见 WAITING(parking)；解法：独立线程池隔离+超时+快速失败");
    }

    // 15 死锁（修复：线程设为 daemon，避免常驻非守护线程影响 JVM）
    public Map<String, Object> deadlock() throws Exception {
        log.info("DIAG[15-deadlock] start");
        captureSnapshot("deadlock");
        Object lockA = new Object();
        Object lockB = new Object();
        long start = System.nanoTime();
        Thread t1 = daemon(new Thread(() -> {
            synchronized (lockA) { sleepQuiet(200); synchronized (lockB) { } }
        }, "diag-deadlock-1"));
        Thread t2 = daemon(new Thread(() -> {
            synchronized (lockB) { sleepQuiet(200); synchronized (lockA) { } }
        }, "diag-deadlock-2"));
        t1.start(); t2.start();
        Thread.sleep(500);
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[15-deadlock] done cost={}ms（两线程已 BLOCKED，jstack 可见 deadlock 段；daemon 线程，重启即清理）", ms);
        return result("15 死锁",
                "两线程以相反顺序持有 A、B 锁，循环等待；jstack 直接打出 Found one Java-level deadlock",
                "两个线程均 BLOCKED 约=" + ms + "ms（daemon，不影响 JVM 退出）",
                "jstack <pid> 末尾见 deadlock 段；解法：统一加锁顺序（先排序再锁）");
    }

    /** 触发即抓现场：jstack 落盘到 /tmp/diag-captures/<case>-<ts>.txt */
    private void captureSnapshot(String caseTag) {
        try {
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String ts = LocalDateTime.now().toString().replace(':', '-');
            String dir = "/tmp/diag-captures";
            new java.io.File(dir).mkdirs();
            String out = dir + "/" + caseTag + "-" + ts + ".txt";
            new ProcessBuilder("/usr/lib/jvm/java-21-openjdk/bin/jstack", pid)
                    .redirectOutput(new java.io.File(out))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
            log.info("DIAG[{}] snapshot saved to {}", caseTag, out);
        } catch (Exception e) {
            log.warn("DIAG snapshot failed: {}", e.getMessage());
        }
    }

    /** 主动 heap dump：jcmd GC.heap_dump 落盘到工作区 output/training-runtime/logs/（相对 CWD=工作区根） */
    public Map<String, Object> heapDump() {
        log.info("DIAG[heap-dump] start");
        try {
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String ts = LocalDateTime.now().toString().replace(':', '-');
            String out = "output/training-runtime/logs/heapdump-" + ts + ".hprof";
            Process p = new ProcessBuilder("/usr/lib/jvm/java-21-openjdk/bin/jcmd", pid, "GC.heap_dump", "-all", out)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(30, TimeUnit.SECONDS);
            String output = new String(p.getInputStream().readAllBytes());
            java.io.File f = new java.io.File(out);
            long mb = f.exists() ? f.length() / 1024 / 1024 : 0;
            log.info("DIAG[heap-dump] done file={} size={}MB output={}", out, mb, output.trim());
            return result("heap dump",
                    "jcmd GC.heap_dump 主动落盘（OOM 时 HeapDumpOnOutOfMemoryError 也会自动触发）",
                    "文件=" + out + "，大小=" + mb + "MB",
                    "MAT 打开看 Dominator Tree；OOM 时自动 dump 到 output/training-runtime/logs/heapdump.hprof");
        } catch (Exception e) {
            log.warn("DIAG[heap-dump] failed: {}", e.getMessage());
            return result("heap dump", "失败", e.getMessage(), "检查 jcmd 路径和权限");
        }
    }

    private static Thread daemon(Thread t) {
        t.setDaemon(true);
        return t;
    }

    // 21 CLOSE_WAIT 连接泄漏：服务端 accept 后不读不关，客户端关闭 → 服务端连接停在 CLOSE_WAIT
    public Map<String, Object> connLeak(String mode, int sockets) throws Exception {
        if (mode == null) mode = "start";
        if ("close".equalsIgnoreCase(mode)) {
            int closed;
            synchronized (connLeakSockets) {
                closed = connLeakSockets.size();
                for (Socket s : connLeakSockets) {
                    try { s.close(); } catch (Exception ignored) {}
                }
                connLeakSockets.clear();
            }
            log.info("DIAG[21-conn-leak] close done closed={}", closed);
            return result("21 CLOSE_WAIT 连接泄漏",
                    "被动方收到 FIN 但应用不 close → CLOSE_WAIT 堆积；持续涨 = 代码漏关连接（必泄漏）",
                    "已关闭=" + closed + " 个连接（CLOSE_WAIT 随之下滑）",
                    "ss -antp | grep CLOSE_WAIT | wc -l 观察回落；排查见 /api/ops/network 连接态统计");
        }
        sockets = Math.min(Math.max(sockets, 1), 500);
        final int target = sockets;
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Thread acceptor = daemon(new Thread(() -> {
                for (int i = 0; i < target; i++) {
                    try { connLeakSockets.add(ss.accept()); } catch (Exception e) { break; }
                }
            }, "diag-conn-leak-acceptor"));
            acceptor.start();
            for (int i = 0; i < sockets; i++) {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    // 客户端立即关闭 → 服务端收到 FIN 但未 close → CLOSE_WAIT
                }
            }
            Thread.sleep(300);
            log.info("DIAG[21-conn-leak] start done held={}", connLeakSockets.size());
            return result("21 CLOSE_WAIT 连接泄漏",
                    "被动方收到 FIN 但应用不 close → CLOSE_WAIT 堆积；ss -antp 可见，持续涨 = 代码 bug",
                    "已持有未关闭连接=" + connLeakSockets.size() + "（查看: ss -ant | grep CLOSE_WAIT | wc -l）",
                    "curl /api/ops/network 或 ss -antp | grep CLOSE_WAIT | wc -l；演练后调用 mode=close 清理");
        }
    }

    // 22 TIME_WAIT 海量短连接：本进程作为主动关闭方，循环短连接 → TIME_WAIT 堆积
    public Map<String, Object> shortConn(int n) throws Exception {
        n = Math.min(Math.max(n, 1), 1000);
        int ok = 0;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", 8080), 300);
                s.getOutputStream().write("GET /api/health HTTP/1.0\r\n\r\n".getBytes());
                s.getOutputStream().flush();
                if (s.getInputStream().read() >= 0) ok++;
            } catch (Exception ignored) {}
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[22-short-conn] done n={} ok={} cost={}ms", n, ok, ms);
        return result("22 TIME_WAIT 短连接堆积",
                "本进程主动关闭大量连接 → 主动关闭方进入 TIME_WAIT 等待 2MSL；短连接/未用连接池的典型现象",
                "短连接=" + n + "，成功=" + ok + "，耗时=" + ms + "ms（查看: ss -ant | grep TIME_WAIT | wc -l）",
                "ss -ant | awk '{print $1}' | sort | uniq -c；解法：连接池/长连接复用；TIME_WAIT 约 60s 自然消退");
    }

    // 23 已删句柄未释放：写入大文件→unlink→保持 fd → du 找不到但空间不回收
    public Map<String, Object> deletedFd(String mode, int mb) throws Exception {
        if (mode == null) mode = "start";
        if ("clean".equalsIgnoreCase(mode)) {
            int closed;
            synchronized (deletedFdHolds) {
                closed = deletedFdHolds.size();
                for (FileOutputStream fos : deletedFdHolds) {
                    try { fos.close(); } catch (Exception ignored) {}
                }
                deletedFdHolds.clear();
            }
            log.info("DIAG[23-deleted-fd] clean done closed={}（空间已回收）", closed);
            return result("23 已删句柄未释放",
                    "关闭句柄后空间立即回收，无需删数据",
                    "已关闭=" + closed + " 个已删句柄（df 使用率回落）",
                    "df -h 对比清理前后；lsof | grep deleted 不再输出");
        }
        mb = Math.min(Math.max(mb, 8), 512);
        String dir = "/opt/training-center/filler";
        new File(dir).mkdirs();
        String path = dir + "/deleted-" + System.currentTimeMillis() + ".tmp";
        FileOutputStream fos = new FileOutputStream(path);
        byte[] buf = new byte[1024 * 1024];
        Arrays.fill(buf, (byte) 0x5A);
        long start = System.nanoTime();
        for (int i = 0; i < mb; i++) fos.write(buf);
        fos.flush();
        boolean unlinked = new File(path).delete();   // 目录中移除，但 fd 仍持有
        deletedFdHolds.add(fos);
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[23-deleted-fd] start done mb={} unlinked={} cost={}ms", mb, unlinked, ms);
        return result("23 已删句柄未释放",
                "进程持有已删除文件的 fd：du 找不到（文件已从目录移除），但空间不回收；磁盘满排查经典场景",
                "占盘=" + mb + "MB（文件已 unlink=" + unlinked + "；df 使用率仍高），写入耗时=" + ms + "ms",
                "lsof +L1 或 lsof | grep deleted 看 <path> (deleted)；演练后 mode=clean 回收空间");
    }

    // 24 磁盘写满演练（⚠️ 仅云服务器演练机）：填充磁盘指定大小，观察 df 飙升与写失败
    public Map<String, Object> diskFull(String mode, int mb) throws Exception {
        if (mode == null) mode = "start";
        if ("clean".equalsIgnoreCase(mode)) {
            int freed;
            synchronized (diskFillerFiles) {
                freed = 0;
                for (File f : diskFillerFiles) {
                    if (f.exists() && f.delete()) freed++;
                }
                diskFillerFiles.clear();
            }
            log.info("DIAG[24-disk-full] clean done freed={}", freed);
            return result("24 磁盘写满演练",
                    "删除填充文件即释放空间（本演练的文件不在系统关键路径）",
                    "已删除=" + freed + " 个填充文件",
                    "df -h 对比清理前后，使用率应回落");
        }
        mb = Math.min(Math.max(mb, 256), 4096);   // 上限 4G，40G 盘安全
        String dir = "/opt/training-center/disk-filler";
        new File(dir).mkdirs();
        String path = dir + "/fill-" + System.currentTimeMillis() + ".tmp";
        long start = System.nanoTime();
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.setLength((long) mb * 1024 * 1024);
            raf.seek(0);
            byte[] buf = new byte[1024 * 1024];
            Arrays.fill(buf, (byte) 0x5A);
            for (int i = 0; i < mb; i++) raf.write(buf);
            raf.getFD().sync();
        }
        diskFillerFiles.add(new File(path));
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[24-disk-full] start done mb={} path={} cost={}ms", mb, path, ms);
        return result("24 磁盘写满演练（云服务器专用）",
                "填充磁盘至指定占用：df 使用率上升、写盘变慢/写失败、日志告警；验证磁盘满排查链路",
                "已填充=" + mb + "MB → " + path + "，耗时=" + ms + "ms",
                "df -h 看 / 使用率；du -h --max-depth=1 /opt/training-center/disk-filler | sort -h；演练后 mode=clean 删除释放");
    }

    // 25 网络延迟演练（⚠️ 仅云服务器演练机）：tc netem 对 eth0 上 8080 端口流量注入延迟，SSH(22) 不受影响
    public Map<String, Object> netLatency(String mode, int delayMs) throws Exception {
        if (mode == null) mode = "on";
        if ("off".equalsIgnoreCase(mode)) {
            String nic = detectNic();
            String out = runTc("tc qdisc del dev " + nic + " root 2>/dev/null || true; echo cleared:" + nic);
            log.info("DIAG[25-net-latency] off done {}", out);
            return result("25 网络延迟演练（tc netem）",
                    "清除 netem qdisc，恢复 8080 原有网络（不影响 SSH）",
                    out,
                    "curl -s -o /dev/null -w '%{time_total}' http://<公网IP>:8080/api/health 应恢复正常");
        }
        delayMs = Math.min(Math.max(delayMs, 50), 2000);
        String nic = detectNic();
        String cmds = "tc qdisc add dev " + nic + " root handle 1: prio 2>/dev/null; "
                + "tc filter add dev " + nic + " parent 1: protocol ip prio 1 u32 match ip dport 8080 0xffff flowid 1:1 2>/dev/null; "
                + "tc qdisc add dev " + nic + " parent 1:1 handle 10: netem delay " + delayMs + "ms";
        String out = runTc(cmds);
        log.info("DIAG[25-net-latency] on done nic={} delay={}ms out={}", nic, delayMs, out);
        return result("25 网络延迟演练（tc netem · 云服务器专用）",
                "对 " + nic + " 上目标 8080 端口流量注入延迟（SSH 22 不受影响）；验证需用公网 IP 访问 8080（本机 loopback 不经过 eth0）",
                "已注入 delay=" + delayMs + "ms 到 " + nic + " 的 8080 端口；执行输出=" + out,
                "外部 curl http://154.8.163.202:8080/api/health 看 time_total ≈ delay+原耗时；演练后 mode=off 恢复");
    }

    // 26 磁盘 IO 狂写演练：后台线程持续写文件制造 %util 高 / load 高但 CPU us 低（IO 等待/D 状态）
    public Map<String, Object> ioSpike(String mode, int mb, int seconds) throws Exception {
        if (mode == null) mode = "start";
        if ("stop".equalsIgnoreCase(mode)) {
            int freed = 0;
            synchronized (ioSpikeFiles) {
                for (File f : ioSpikeFiles) {
                    if (f.exists() && f.delete()) freed++;
                }
                ioSpikeFiles.clear();
            }
            log.info("DIAG[26-io-spike] stop done freed={}", freed);
            return result("26 磁盘 IO 狂写演练",
                    "停止写入并删除填充文件，IO 压力解除",
                    "已删除=" + freed + " 个填充文件",
                    "iostat -x 1 看 %util 回落；top 看 wa 下降、load 回落");
        }
        mb = Math.min(Math.max(mb, 64), 512);
        seconds = Math.min(Math.max(seconds, 5), 60);
        String dir = "/opt/training-center/io-spike";
        new File(dir).mkdirs();
        final String path = dir + "/spike-" + System.currentTimeMillis() + ".tmp";
        final int chunkMb = mb;
        final int durationSec = seconds;
        Thread writer = daemon(new Thread(() -> {
            try {
                FileOutputStream fos = new FileOutputStream(path);
                byte[] buf = new byte[1024 * 1024];
                Arrays.fill(buf, (byte) 0x7A);
                long deadline = System.currentTimeMillis() + (long) durationSec * 1000;
                long written = 0;
                while (System.currentTimeMillis() < deadline) {
                    fos.write(buf);
                    written += buf.length;
                }
                fos.flush();
                fos.close();
                ioSpikeFiles.add(new File(path));
                log.info("DIAG[26-io-spike] writer done total={}MB", written / 1024 / 1024);
            } catch (Exception e) {
                log.warn("DIAG[26-io-spike] writer failed: {}", e.getMessage());
            }
        }, "diag-io-spike-writer"));
        writer.start();
        log.info("DIAG[26-io-spike] start mb/s={} duration={}s path={}", mb, seconds, path);
        return result("26 磁盘 IO 狂写演练",
                "后台线程以 ~" + mb + "MB/s 持续写盘" + seconds + " 秒：iostat %util 飙高、load 升高、top 的 wa(iowait) 上升、写入线程呈 D 状态——复现'负载高但 CPU 低'",
                "正在以 " + mb + "MB/s 写 " + seconds + "s → " + path + "（观测: iostat -x 1 / top 看 wa / pidstat -d 1）",
                "iostat -x 1 看 %util；top 看 wa 列；iotop 看进程 IO；日志/大文件狂写是 IO 元凶——写入结束后文件保留在 io-spike/，可 mode=stop 删除");
    }

    // 27 锁竞争毛刺：多线程争抢同一把锁，制造 BLOCKED 堆积（短时毛刺）
    public Map<String, Object> lockContention(int threads, int ms) throws Exception {
        threads = Math.min(Math.max(threads, 2), 100);
        ms = Math.min(Math.max(ms, 500), 10000);
        final int holdMs = ms;
        long start = System.nanoTime();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = daemon(new Thread(() -> {
                long end = System.currentTimeMillis() + holdMs;
                while (System.currentTimeMillis() < end) {
                    synchronized (contentionLock) {
                        Thread.yield();
                    }
                }
            }, "diag-lock-contend-" + i));
            t.start();
            workers.add(t);
        }
        Thread.sleep(300);
        int blocked = 0;
        for (Thread t : workers) if (t.getState() == Thread.State.BLOCKED) blocked++;
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[27-lock-contention] start threads={} blocked-at-300ms={} duration={}ms", threads, blocked, ms);
        return result("27 锁竞争毛刺",
                "多线程争抢同一把 synchronized 锁：同一时刻多数线程 BLOCKED，CPU 低但接口 RT 毛刺",
                "线程=" + threads + "，300ms 采样时 BLOCKED=" + blocked + "，演练持续=" + ms + "ms",
                "arthas thread -n 5 -i 500 抓毛刺；jstack 见大量 BLOCKED 在同一锁；修复：减少临界区/锁粒度/用 ConcurrentHashMap");
    }

    private static String detectNic() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "ip -o -4 route show to default | awk '{print $5}'")
                    .redirectErrorStream(true).start();
            p.waitFor(2, TimeUnit.SECONDS);
            String nic = new String(p.getInputStream().readAllBytes()).trim();
            return nic.isBlank() ? "eth0" : nic;
        } catch (Exception e) {
            return "eth0";
        }
    }

    private static String runTc(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        boolean done = p.waitFor(15, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return "(tc 执行超时，请手动 tc qdisc del dev $(ip route | awk '/default/ {print $5}') root)";
        }
        return new String(p.getInputStream().readAllBytes()).trim();
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static Map<String, Object> result(String caseName, String rootCause, String observed, String how) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case", caseName);
        m.put("rootCause", rootCause);
        m.put("observed", observed);
        m.put("howToVerify", how);
        m.put("at", LocalDateTime.now().toString());
        return m;
    }

    private record UserContext(String userId, byte[] payload) { }
    private record BigRow(int id, String tag, String payload) { }
}
