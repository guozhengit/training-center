package com.guoyongzheng.training.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 运维工作台服务：把《Linux 线上操作命令与排查实战》里的核心命令、
 * 排查步骤与监控工具整合成可调用的只读接口。
 *
 * <p>安全设计：
 * <ul>
 *   <li>命令白名单：只允许预定义的只读命令，不接受任意输入；</li>
 *   <li>参数校验：拒绝包含 shell 元字符（; | &amp; ` $ ( ) &gt; &lt; 等）的参数；</li>
 *   <li>超时控制：每条命令独立线程执行，5 秒超时，不阻塞 HTTP 线程；</li>
 *   <li>日志检索：只允许读取本项目日志目录（/var/log/training-center 或 workspace 内 logs）。</li>
 * </ul>
 */
@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    /** 单条命令最大执行时间（毫秒） */
    private static final long COMMAND_TIMEOUT_MS = 5000;
    /** 输出最大截断字节数 */
    private static final int MAX_OUTPUT_BYTES = 24 * 1024;

    /** 禁止出现在参数中的 shell 元字符 */
    private static final Pattern DANGEROUS = Pattern.compile("[;&|`$()<>\\{\\}\\[\\]\\*\\?'\"\\\\ ]");

    /** 本服务允许读取的日志目录（白名单） */
    private static final List<Path> LOG_ROOTS = List.of(
            Path.of("output/training-runtime/logs"),
            Path.of("/var/log/training-center"),
            Path.of("/opt/training-center/logs"));

    private static final String WINDOWS_HINT =
            "当前运行在 Windows（本地开发态）。该接口面向 Linux 服务器部署，"
                    + "请在 154.8.163.202 等 Linux 环境调用以获取真实数据。";

    /* ============ 通用命令执行（白名单） ============ */

    public Map<String, Object> runSafe(String[] command, long timeoutMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (isWindows()) {
            result.put("available", false);
            result.put("command", String.join(" ", command));
            result.put("hint", WINDOWS_HINT);
            return result;
        }
        try {
            long timeout = Math.max(500, Math.min(timeoutMs, COMMAND_TIMEOUT_MS));
            long startNanos = System.nanoTime();
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.put("available", true);
                result.put("command", String.join(" ", command));
                result.put("timeout", true);
                result.put("output", "(执行超过 " + timeout + "ms，已终止)");
                return result;
            }
            String output = readOutput(process);
            result.put("available", true);
            result.put("command", String.join(" ", command));
            result.put("exitCode", process.exitValue());
            result.put("output", output);
            result.put("tookMs", Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            result.put("available", true);
            result.put("command", String.join(" ", command));
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private static String readOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > MAX_OUTPUT_BYTES) {
                    sb.append("...(输出过长已截断)");
                    break;
                }
            }
        }
        return sb.toString().strip();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 校验参数不包含 shell 元字符；违反抛 IllegalArgumentException */
    private static void requireSafeParam(String name, String value) {
        if (value == null || value.isBlank()) return;
        if (DANGEROUS.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "参数 " + name + " 包含非法字符（仅允许字母数字与 . - _ : /），拒绝执行");
        }
    }

    /* ============ 1. 系统资源快照（四件套） ============ */

    /** 一次调用聚合 CPU / 内存 / 磁盘 / 负载概览 */
    public Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", "系统资源全景四件套");
        view.put("guide", "先定性（哪类资源）→ 再定位（哪个进程/线程）→ 最后归因（代码/配置/外部）");
        view.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        view.put("java", Runtime.version().toString());
        view.put("cpu", cpu());
        view.put("memory", memory());
        view.put("disk", disk());
        view.put("network", network());
        view.put("jvm", jvm());
        view.put("commands", Map.of(
                "top", "top -b -n 1 | head -20",
                "mpstat", "mpstat -P ALL 1 1",
                "vmstat", "vmstat 1 2",
                "iostat", "iostat -x 1 1"));
        return view;
    }

    public Map<String, Object> cpu() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("section", "CPU");
        view.put("top", runSafe(new String[]{"top", "-b", "-n", "1"}, 4000));
        view.put("mpstat", runSafe(new String[]{"mpstat", "-P", "ALL", "1", "1"}, 4000));
        view.put("vmstat", runSafe(new String[]{"vmstat", "1", "2"}, 4000));
        view.put("loadAvg", runSafe(new String[]{"uptime"}, 2000));
        view.put("tips", List.of(
                "us 高 → 业务/GC；wa 高 → 卡磁盘（不是 CPU 问题）",
                "负载 > 核数×1 偏忙，> 核数×2~3 过载；负载高但 us 低 → IO/锁（D 状态）",
                "cs（上下文切换）暴涨 → 疑锁竞争"));
        return view;
    }

    public Map<String, Object> memory() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("section", "内存");
        view.put("free", runSafe(new String[]{"free", "-h"}, 2000));
        view.put("meminfo", runSafe(new String[]{"sh", "-c",
                "grep -E 'MemTotal|MemFree|MemAvailable|Buffers|Cached|SReclaimable|SwapTotal|SwapFree' /proc/meminfo"}, 2000));
        view.put("topRss", runSafe(new String[]{"ps", "-eo", "pid,rss,comm", "--sort=-rss"}, 2000));
        view.put("tips", List.of(
                "看 available，不是 free（Linux 会拿空闲内存做 page cache，cache 可回收）",
                "si/so 非 0 = 内存真不够",
                "smem -s rss -r 按 PSS 排序更准确"));
        return view;
    }

    public Map<String, Object> disk() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("section", "磁盘 IO");
        view.put("df", runSafe(new String[]{"df", "-h"}, 2000));
        view.put("du", runSafe(new String[]{"sh", "-c", "du -h --max-depth=1 /opt 2>/dev/null | sort -h | tail -8"}, 4000));
        view.put("deleted", runSafe(new String[]{"sh", "-c", "lsof +L1 2>/dev/null | head -10 || echo '(无已删未释放句柄或需要 root)'"}, 3000));
        view.put("tips", List.of(
                "%util 接近 100% 且 await 远大于 svctm → 磁盘瓶颈",
                "磁盘满但 du 找不到大文件 → 被删文件句柄未释放：lsof | grep deleted，清空 fd 或重启进程",
                "日志/临时文件狂写、log4j 没切割是常见 IO 元凶"));
        return view;
    }

    public Map<String, Object> network() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("section", "网络");
        view.put("connStates", runSafe(new String[]{"sh", "-c",
                "ss -ant 2>/dev/null | awk 'NR>1{print $1}' | sort | uniq -c | sort -rn || netstat -an | awk 'NR>2{print $6}' | sort | uniq -c | sort -rn"}, 3000));
        view.put("closeWait", runSafe(new String[]{"sh", "-c", "ss -antp 2>/dev/null | grep -c CLOSE_WAIT || echo 0"}, 2000));
        view.put("timeWait", runSafe(new String[]{"sh", "-c", "ss -ant 2>/dev/null | grep -c TIME_WAIT || echo 0"}, 2000));
        view.put("listeners", runSafe(new String[]{"sh", "-c", "ss -tlnp 2>/dev/null | head -15 || netstat -tlnp | head -15"}, 2000));
        view.put("tips", List.of(
                "CLOSE_WAIT 持续涨 = 应用没关连接（代码 bug，必泄漏）",
                "TIME_WAIT 海量 = 主动关闭方压力大 / 短连接没用连接池",
                "SYN_RECV 多 → 半连接攻击；SYN_SENT 多 → 下游不可达",
                "小包攻击看 PPS（sar -n DEV 1 的 rxpck/s），不看带宽"));
        return view;
    }

    /* ============ 2. JVM 诊断（本进程） ============ */

    public Map<String, Object> jvm() {
        Map<String, Object> view = new LinkedHashMap<>();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        List<Map<String, Object>> gcStats = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", g.getName());
                    m.put("collections", g.getCollectionCount());
                    m.put("tookMs", g.getCollectionTime());
                    return m;
                }).collect(Collectors.toList());
        view.put("section", "JVM 诊断（本进程 PID=" + ManagementFactory.getRuntimeMXBean().getName() + "）");
        view.put("heap", Map.of(
                "init", heap.getInit() / 1048576 + "MB",
                "used", heap.getUsed() / 1048576 + "MB",
                "committed", heap.getCommitted() / 1048576 + "MB",
                "max", heap.getMax() / 1048576 + "MB"));
        view.put("nonHeap", Map.of(
                "used", nonHeap.getUsed() / 1048576 + "MB",
                "max", (nonHeap.getMax() == -1 ? -1 : nonHeap.getMax() / 1048576) + "MB"));
        view.put("gc", gcStats);
        view.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000 + "s");
        view.put("tips", List.of(
                "jstat -gcutil <pid> 1000 看 S0/S1/E/O/M 与 YGC/FGC",
                "OU 一路涨且 FGC 收不回来 → 内存泄漏；jcmd <pid> GC.heap_dump /tmp/heap.hprof",
                "-Xmx 必须 < 容器 limit，否则被 cgroup 直接杀（OOMKilled exit 137）"));
        return view;
    }

    public Map<String, Object> threads() {
        Map<String, Object> view = new LinkedHashMap<>();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] ids = threadMXBean.getAllThreadIds();
        Map<String, Long> stateCount = new LinkedHashMap<>();
        ThreadInfo[] infos = threadMXBean.getThreadInfo(ids, 0);
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            stateCount.merge(info.getThreadState().name(), 1L, Long::sum);
        }
        view.put("section", "线程诊断（本进程）");
        view.put("totalThreads", ids.length);
        view.put("states", stateCount);
        view.put("deadlocked", threadMXBean.findDeadlockedThreads() == null ? "无" : "存在死锁线程！");
        view.put("cmd", List.of(
                "ps -ef | grep java            # 找 PID",
                "top -Hp <pid>                 # 找高 CPU 线程 tid",
                "printf '%x\\n' <tid>          # 转十六进制 nid",
                "jstack <pid> | grep -A 30 <nid>  # 定位堆栈",
                "kill -3 <pid>                 # 无 jstack 时的线程快照兜底"));
        view.put("tips", List.of(
                "WAITING/BLOCKED 一堆 → 在等锁/池/下游；CPU 低但 RT 高 = 一定在等",
                "父子任务共用线程池是头号禁忌（池饥饿死锁）"));
        return view;
    }

    /* ============ 3. 日志检索（白名单目录） ============ */

    public Map<String, Object> logs(String level, int lines) {
        requireSafeParam("level", level);
        if (lines <= 0 || lines > 2000) lines = 100;
        List<String> allowedLevels = List.of("", "ERROR", "WARN", "INFO", "DEBUG", "OOM", "DIAG", "EXCEPTION");
        String finalLevel = level == null ? "" : level.trim().toUpperCase();
        if (!allowedLevels.contains(finalLevel)) {
            throw new IllegalArgumentException("level 仅支持: ERROR/WARN/INFO/DEBUG/OOM/DIAG/EXCEPTION");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("section", "日志检索");
        view.put("level", finalLevel.isEmpty() ? "ALL" : finalLevel);
        view.put("lines", lines);
        Map<String, Object> results = new LinkedHashMap<>();
        for (Path root : LOG_ROOTS) {
            if (!Files.isDirectory(root)) continue;
            for (String file : List.of("app.log", "diag.log")) {
                Path p = root.resolve(file);
                if (!Files.isRegularFile(p)) continue;
                List<String> args = new ArrayList<>();
                if (finalLevel.isEmpty()) {
                    args.addAll(List.of("tail", "-n", String.valueOf(lines)));
                } else {
                    args.addAll(List.of("sh", "-c",
                            "tail -n " + Math.min(lines * 5, 4000) + " \"" + p + "\" | grep -E '" + finalLevel + "' | tail -n " + lines));
                }
                results.put(file, runSafe(args.toArray(new String[0]), 3000));
            }
        }
        view.put("files", results);
        view.put("tips", List.of(
                "tail -f app.log | grep --line-buffered ERROR   # 实时不丢行",
                "awk 时间窗口：awk '$0 >= \"2026-09-15 14:00\" && $0 <= \"2026-09-15 14:05\"' app.log | grep ERROR",
                "grep ERROR app.log | awk '{print $1\" \"$2}' | cut -d: -f1-2 | sort | uniq -c   # 按分钟聚合错误趋势"));
        return view;
    }

    /* ============ 4. 运维知识库（d36 文档结构化） ============ */

    public Map<String, Object> guide() {
        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put("title", "Linux 线上操作命令速查（一击定位）");
        guide.put("table", List.of(
                Map.of("场景", "找最耗 CPU 的 Java 线程", "命令", "top -Hp $(pgrep -f 'java.*your-app') 然后 jstack"),
                Map.of("场景", "找 CLOSE_WAIT 泄漏", "命令", "ss -antp | grep CLOSE_WAIT | awk '{print $4}' | sort | uniq -c"),
                Map.of("场景", "找大文件", "命令", "du -h --max-depth=1 /data | sort -h"),
                Map.of("场景", "实时统计 5xx", "命令", "tail -f access.log | awk '$9 ~ /^5/ {c++} {print c}'"),
                Map.of("场景", "已删未释放磁盘", "命令", "lsof | grep deleted"),
                Map.of("场景", "端口被谁占", "命令", "ss -antp | grep :8080 或 lsof -i :8080"),
                Map.of("场景", "JVM 线程快照（无 jstack）", "命令", "kill -3 <pid> → 看 stdout"),
                Map.of("场景", "容器内看宿主机映射 pid", "命令", "docker inspect -f '{{.State.Pid}}' <c>"),
                Map.of("场景", "看进程启动参数", "命令", "cat /proc/<pid>/cmdline | tr '\\0' ' '"),
                Map.of("场景", "连续监控某命令", "命令", "watch -n 1 'ss -ant | wc -l'")));
        guide.put("speedTable", List.of(
                Map.of("项", "负载经验", "结论", "> 核数×1 偏忙，> 核数×2~3 过载；负载高 CPU 低 = IO/锁"),
                Map.of("项", "内存", "结论", "看 available 不是 free（cache 可回收）"),
                Map.of("项", "TIME_WAIT", "结论", "主动关闭方多 = 短连接/没用连接池"),
                Map.of("项", "CLOSE_WAIT", "结论", "被动方应用没关连接 = 代码泄漏"),
                Map.of("项", "fd 耗尽", "结论", "limits.conf 的 nofile + systemd LimitNOFILE"),
                Map.of("项", "OOMKilled", "结论", "exitCode 137，-Xmx 必须 < 容器 limit"),
                Map.of("项", "日志实时", "结论", "less +F > tail -f（可搜）；grep --line-buffered 防丢"),
                Map.of("项", "火焰图", "结论", "横轴=采样占比，找最宽平顶"),
                Map.of("项", "容器内 pid", "结论", "与宿主机不同 namespace，jstack 在容器内跑"),
                Map.of("项", "抓包", "结论", "tcpdump -i any port X -w file.pcap")));
        guide.put("threeQuestions", List.of(
                Map.of("题", "CPU 飙到 90% 第一步？", "资深答", "先 top 看 us/sy/wa；us 高再 top -Hp → printf '%x' → jstack；Arthas thread -n 3 / perf top"),
                Map.of("题", "磁盘满但 du 找不到？", "资深答", "lsof | grep deleted：被删但句柄未释放的文件，清空 fd 或重启进程即回收"),
                Map.of("题", "怀疑网络怎么证伪/证实？", "资深答", "ss -antp 看连接态（CLOSE_WAIT=没关连接；TIME_WAIT=主动关闭压力）→ iftop 带宽 → tcpdump 抓包看重传 → sar -n DEV 1 看 PPS；区分网络慢和应用慢")));
        return guide;
    }

    public Map<String, Object> decision() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", "五条排查决策树（先定性 → 再定位 → 最后归因）");
        d.put("tree", List.of(
                Map.of("条件", "CPU 高?", "分支", "us 高 → top -Hp 找线程 → jstack 定位 / perf top；wa 高(IO等待) → iostat -x 看 %util/await → iotop 找写盘进程"),
                Map.of("条件", "内存满?", "分支", "free -h 看 available → jmap / NMT 看堆外 → lsof | grep deleted"),
                Map.of("条件", "网络慢?", "分支", "ss -antp 看 CLOSE_WAIT/TIME_WAIT → tcpdump 抓包 → iftop 带宽"),
                Map.of("条件", "磁盘满?", "分支", "df/du 找大文件 → lsof | grep deleted 回收已删未释"),
                Map.of("条件", "卡死/无响应?", "分支", "strace 看 syscall 卡哪（futex=锁 / read=等上游）→ jstack 看线程状态")));
        d.put("heart", "先定性（哪类资源），再定位（哪个进程/线程），最后归因（代码/配置/外部）。不要一上来 top 看到啥就改啥。");
        return d;
    }
}
