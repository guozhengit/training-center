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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

    public DiagnosticService(TrainingDatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
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
                Map.of("path", "deadlock", "case", "15 两线程反向加锁死锁", "param", "无（jstack 可见 BLOCKED）")
        ));
        return m;
    }

    // 01 ReDoS：嵌套量词指数级回溯
    public Map<String, Object> redos(int len) {
        log.info("DIAG[01-redos] start len={}", len);
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
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                for (int j = 0; j < 2000; j++) {
                    shared.format(new Date());
                    shared.parse("2026-01-01 00:00:00");
                }
                return "ok";
            }));
        }
        for (Future<String> f : fs) f.get();
        pool.shutdownNow();
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[05-sdf] done cost={}ms", ms);
        return result("05 SimpleDateFormat 并发",
                "static SimpleDateFormat 非线程安全，并发下串行甚至结果错乱",
                "threads=" + threads + "，耗时=" + ms + "ms（BLOCKED 在 SDF）",
                "arthas thread -n 5 -i 500 抓短时毛刺；jstack 见多线程 BLOCKED 在 SimpleDateFormat.format/parse");
    }

    // 06 慢 SQL（PG pg_sleep）
    public Map<String, Object> slowSql(int seconds) throws Exception {
        log.info("DIAG[06-slow-sql] start seconds={}", seconds);
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

    // 15 死锁（修复：线程设为 daemon，避免常驻非守护线程影响 JVM）
    public Map<String, Object> deadlock() throws Exception {
        log.info("DIAG[15-deadlock] start");
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

    private static Thread daemon(Thread t) {
        t.setDaemon(true);
        return t;
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
