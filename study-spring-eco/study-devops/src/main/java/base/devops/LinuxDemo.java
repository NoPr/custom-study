package base.devops;

import java.util.*;

/**
 * Linux 故障排查核心概念大合集: top + free + iostat + netstat/ss + jstack + 磁盘排查
 *
 * <p>top: 实时进程监控, 关键字段:
 * ni (nice): 用户态低优先级进程 CPU 占比 (nice 值 > 0 的进程)
 * si (softirq): 软中断 CPU 占比, 网络收发密集时升高
 * load average: 1/5/15 分钟平均负载, 值为 CPU 核数表示满载, 持续超过需排查
 *
 * <p>free: 内存使用概览, 关键字段:
 * buffer: 内核缓冲区, 用于块设备 (磁盘) 读写缓存
 * cache: 页缓存, 用于文件系统元数据和文件内容缓存
 * available: 实际可用内存 ≈ free + buffer + cache 中可回收部分
 *
 * <p>iostat: 磁盘 IO 统计, 关键字段:
 * await: 单个 IO 请求平均等待时间 (排队 + 服务), 含 seek+旋转延迟
 * svctm: 单个 IO 平均服务时间 (已废弃, 不可靠)
 * util: 设备繁忙百分比, 接近 100% 表示磁盘瓶颈
 *
 * <p>netstat/ss: 网络连接统计, TIME_WAIT 过多需调整内核参数 (tcp_tw_reuse)
 *
 * <p>jstack: Java 线程堆栈, 死锁检测 (jstack -l pid | grep "deadlock")
 *
 * <p>磁盘排查: du -sh /* | sort -rh 定位大目录, lsof 查找删除但仍占用的文件
 */
public class LinuxDemo {

    /** top 命令核心字段详解 */
    static void printTopFields() {
        System.out.println("=== top 命令关键字段详解 ===");
        System.out.println("top 输出示例分析:");
        System.out.println();
        System.out.println("  load average: 1.25, 0.98, 0.75");
        System.out.println("  含义: 1/5/15 分钟平均负载");
        System.out.println("  判断: CPU 核数=4, 1.25<4 正常; 若持续 > 核数*70% 需关注");
        System.out.println();
        System.out.println("  %Cpu(s):  2.5 us,  1.0 sy,  0.0 ni, 95.0 id,  0.5 wa,  0.0 hi,  1.0 si,  0.0 st");
        String fmt = "| %-10s | %-10s | %-42s |%n";
        System.out.printf(fmt, "字段", "缩写", "含义");
        System.out.println("|------------|------------|--------------------------------------------|");
        System.out.printf(fmt, "us", "user", "用户态进程 CPU 占比 (应用代码)");
        System.out.printf(fmt, "sy", "system", "内核态 CPU 占比 (系统调用)");
        System.out.printf(fmt, "ni", "nice", "低优先级用户进程 CPU 占比");
        System.out.printf(fmt, "id", "idle", "CPU 空闲占比");
        System.out.printf(fmt, "wa", "iowait", "CPU 等待 IO 完成占比, 高→磁盘瓶颈");
        System.out.printf(fmt, "hi", "hardirq", "硬中断 CPU 占比");
        System.out.printf(fmt, "si", "softirq", "软中断 CPU 占比, 高→网络瓶颈");
        System.out.printf(fmt, "st", "steal", "虚拟机被宿主机窃取的 CPU, 高→超卖");
    }

    /** free 命令内存分析 */
    static void printFreeMemory() {
        System.out.println("\n=== free 命令内存分析 ===");

        class MemoryInfo {
            String label;
            long total;
            long used;
            long free;
            long shared;
            long buffCache;
            long available;

            MemoryInfo(String label, long total, long used, long free, long shared, long buffCache, long available) {
                this.label = label;
                this.total = total;
                this.used = used;
                this.free = free;
                this.shared = shared;
                this.buffCache = buffCache;
                this.available = available;
            }
        }

        MemoryInfo mem = new MemoryInfo("Mem", 16384, 8200, 1200, 256, 6984, 7800);

        String fmt = "| %-8s | %-10s | %-10s | %-10s | %-10s | %-12s | %-12s |%n";
        System.out.printf(fmt, "类型", "total(MB)", "used(MB)", "free(MB)", "shared(MB)", "buff/cache", "available");
        System.out.println("|----------|------------|------------|------------|------------|--------------|--------------|");
        System.out.printf(fmt, mem.label, mem.total, mem.used, mem.free, mem.shared, mem.buffCache, mem.available);

        System.out.println("\n内存计算公式:");
        System.out.println("  used = total - free - buff/cache");
        System.out.println("  " + mem.used + " = " + mem.total + " - " + mem.free + " - " + mem.buffCache);
        System.out.println("  available ≈ free + 可回收的 buff/cache = " + mem.available + " (实际可用)");
        System.out.println();
        System.out.println("关键结论:");
        System.out.println("  1. 不要只看 free, 看 available 更准确");
        System.out.println("  2. buffer: 内核缓冲区 (块设备), cache: 页缓存 (文件系统)");
        System.out.println("  3. buff/cache 在内存紧张时可以被内核回收, 不影响应用");
        System.out.println("  4. available < 10% 时需关注, 可能触发 OOM Killer");
    }

    /** iostat 磁盘 IO 分析 */
    static void printIostatFields() {
        System.out.println("\n=== iostat 磁盘 IO 分析 ===");
        System.out.println("iostat -x 1 关键输出字段:");
        String fmt = "| %-12s | %-38s | %-22s |%n";
        System.out.printf(fmt, "字段", "含义", "阈值");
        System.out.println("|--------------|----------------------------------------|------------------------|");
        System.out.printf(fmt, "r/s, w/s", "每秒读/写请求数", "—");
        System.out.printf(fmt, "rkB/s, wkB/s", "每秒读/写 KB 数", "—");
        System.out.printf(fmt, "await", "单个 IO 平均等待时间 (排队+服务)", "> 10ms 需关注, > 50ms 严重");
        System.out.printf(fmt, "r_await", "单个读 IO 平均等待时间", "同 await");
        System.out.printf(fmt, "w_await", "单个写 IO 平均等待时间", "同 await");
        System.out.printf(fmt, "svctm", "单个 IO 服务时间 (已废弃)", "指标不可靠, 忽略");
        System.out.printf(fmt, "%util", "设备繁忙百分比", "接近 100% 表示磁盘瓶颈");
        System.out.printf(fmt, "avgqu-sz", "平均队列长度", "持续 > 1 表示有排队");
        System.out.printf(fmt, "aqu-sz", "当前队列长度", "—");
        System.out.println();
        System.out.println("排查思路:");
        System.out.println("  util 高 + await 高 → 磁盘性能瓶颈, 考虑 SSD/RAID/分布式存储");
        System.out.println("  util 低 + await 高 → 可能是单次 IO 太大或磁盘故障");
        System.out.println("  w/s 远大于 r/s → 写密集型, 考虑异步写入或 WAL");
    }

    /** netstat/ss TIME_WAIT 排查 */
    static void simulateTimeWaitAnalysis() {
        System.out.println("\n=== netstat/ss TIME_WAIT 排查 ===");
        System.out.println("TIME_WAIT 产生原因:");
        System.out.println("  TCP 四次挥手中, 主动关闭方在发送最后一个 ACK 后进入 TIME_WAIT");
        System.out.println("  状态持续 2MSL (Linux 默认 60s), 确保被动关闭方收到 ACK");
        System.out.println();

        int[] portRange = {32768, 60999};
        int availablePorts = portRange[1] - portRange[0] + 1;
        int timeWaitCount = 25000;

        System.out.println("排查命令:");
        System.out.println("  ss -s                                    ← 查看 TCP 连接统计");
        System.out.println("  ss -tan state time-wait | wc -l          ← 统计 TIME_WAIT 数量");
        System.out.println("  netstat -an | grep TIME_WAIT | wc -l     ← 同上 (旧命令)");
        System.out.println();
        System.out.println("当前场景模拟:");
        System.out.println("  本地端口范围: " + portRange[0] + "-" + portRange[1] + " (共 " + availablePorts + " 个)");
        System.out.println("  当前 TIME_WAIT 连接数: " + timeWaitCount);
        System.out.println("  端口占用率: " + (timeWaitCount * 100 / availablePorts) + "%");

        if (timeWaitCount > availablePorts * 0.7) {
            System.out.println("  严重! 即将耗尽端口, 新连接将失败 (Cannot assign requested address)");
            System.out.println();
            System.out.println("  解决方案:");
            System.out.println("    1. 启用 tcp_tw_reuse: net.ipv4.tcp_tw_reuse=1 (客户端)");
            System.out.println("    2. 扩大端口范围: net.ipv4.ip_local_port_range=1024 65535");
            System.out.println("    3. 缩短 TIME_WAIT: net.ipv4.tcp_fin_timeout=30");
            System.out.println("    4. 应用层: 使用长连接/连接池 代替短连接");
        } else {
            System.out.println("  仍在安全范围内");
        }
    }

    /** jstack 死锁检测模拟 */
    static void simulateDeadlockDetection() {
        System.out.println("\n=== jstack 死锁检测模拟 ===");

        class ThreadInfo {
            String name;
            String state;
            String holdingLock;
            String waitingLock;

            ThreadInfo(String name, String state, String holdingLock, String waitingLock) {
                this.name = name;
                this.state = state;
                this.holdingLock = holdingLock;
                this.waitingLock = waitingLock;
            }
        }

        List<ThreadInfo> threads = List.of(
                new ThreadInfo("thread-1", "BLOCKED", "0x00000007d6a5d1a0 (LockA)", "0x00000007d6a5d1b8 (LockB)"),
                new ThreadInfo("thread-2", "BLOCKED", "0x00000007d6a5d1b8 (LockB)", "0x00000007d6a5d1a0 (LockA)")
        );

        System.out.println("检测命令:");
        System.out.println("  jps -l                       ← 列出所有 Java 进程");
        System.out.println("  jstack -l <pid>              ← 打印线程堆栈 (含锁信息)");
        System.out.println("  jstack -l <pid> | grep -A 10 \"deadlock\" ← 过滤死锁信息");
        System.out.println();

        System.out.println("死锁检测结果:");
        System.out.println("  Found one Java-level deadlock:");
        System.out.println("  =============================");
        for (ThreadInfo t : threads) {
            System.out.println("  \"" + t.name + "\":");
            System.out.println("    waiting to lock " + t.waitingLock + ",");
            System.out.println("    which is held by " + (t.name.equals("thread-1") ? "\"thread-2\"" : "\"thread-1\""));
        }
        System.out.println();
        System.out.println("  Java stack information for the threads listed above:");
        System.out.println("  ===================================================");
        System.out.println("  \"thread-1\":");
        System.out.println("    at DeadlockExample.methodA(DeadlockExample.java:15)");
        System.out.println("    - waiting to lock <0x...LockB> (a java.lang.Object)");
        System.out.println("    - locked <0x...LockA> (a java.lang.Object)");
        System.out.println("  \"thread-2\":");
        System.out.println("    at DeadlockExample.methodB(DeadlockExample.java:25)");
        System.out.println("    - waiting to lock <0x...LockA> (a java.lang.Object)");
        System.out.println("    - locked <0x...LockB> (a java.lang.Object)");
        System.out.println();
        System.out.println("  Found 1 deadlock.");
    }

    /** 磁盘满了排查流程 */
    static void simulateDiskFullTroubleshooting() {
        System.out.println("\n=== 磁盘满了排查流程 ===");

        class DirectoryUsage {
            String path;
            long sizeGB;
            String note;

            DirectoryUsage(String path, long sizeGB, String note) {
                this.path = path;
                this.sizeGB = sizeGB;
                this.note = note;
            }
        }

        List<DirectoryUsage> dirs = List.of(
                new DirectoryUsage("/var/log", 42, "日志文件未轮转"),
                new DirectoryUsage("/data/app", 25, "应用数据"),
                new DirectoryUsage("/tmp", 18, "临时文件堆积"),
                new DirectoryUsage("/home", 12, "用户数据"),
                new DirectoryUsage("/var/lib/docker", 35, "Docker 镜像/容器/卷")
        );
        long totalUsed = 132;
        long totalDisk = 200;
        double usedPercent = (double) totalUsed * 100 / totalDisk;

        System.out.println("排查步骤:");
        System.out.println("  1. df -h                        ← 查看磁盘使用概况");
        System.out.println("     容量 " + totalDisk + "G, 已用 " + totalUsed + "G, 使用率 " + String.format("%.0f", usedPercent) + "%");
        if (usedPercent > 80) {
            System.out.println("     磁盘使用率超过 80%, 需要清理!");
        }
        System.out.println();
        System.out.println("  2. du -sh /* | sort -rh | head -10  ← 定位大目录");
        System.out.println("     各目录占用:");
        String fmt = "     | %-24s | %6sG | %-24s |%n";
        System.out.printf(fmt, "路径", "大小", "说明");
        System.out.println("     |--------------------------|--------|--------------------------|");
        for (DirectoryUsage d : dirs) {
            System.out.printf(fmt, d.path, d.sizeGB, d.note);
        }
        System.out.println();
        System.out.println("  3. 特殊情况: 文件已删除但进程仍占用 (lsof)");
        System.out.println("     lsof | grep deleted          ← 查找已被删除但未释放的文件");
        System.out.println("     lsof -p <pid> | grep deleted ← 检查特定进程");
        System.out.println("     解决: 重启进程 或 > /proc/<pid>/fd/<fd> 清空文件描述符");
        System.out.println();
        System.out.println("  4. Docker 磁盘清理:");
        System.out.println("     docker system df              ← 查看 Docker 磁盘占用");
        System.out.println("     docker system prune -a        ← 清理未使用的镜像/容器/卷/网络");
        System.out.println("     docker volume prune           ← 清理未使用的卷");
        System.out.println();
        System.out.println("  5. 日志清理方案:");
        System.out.println("     journalctl --vacuum-size=500M  ← 清理 systemd 日志");
        System.out.println("     logrotate 配置: /etc/logrotate.d/*");
        System.out.println("     find /var/log -name '*.log.*' -mtime +30 -delete ← 删除30天前日志");
    }

    /** 常用排查命令速查 */
    static void printCommonCommands() {
        System.out.println("\n=== Linux 故障排查常用命令速查 ===");
        System.out.println("===== CPU =====");
        System.out.println("  top / htop              ← 实时进程监控");
        System.out.println("  mpstat -P ALL 1         ← 每个 CPU 核心统计");
        System.out.println("  pidstat -p <pid> 1      ← 进程 CPU 统计");
        System.out.println("  perf top                ← 实时性能采样");
        System.out.println();
        System.out.println("===== 内存 =====");
        System.out.println("  free -h                 ← 内存概览");
        System.out.println("  vmstat 1                ← 虚拟内存统计");
        System.out.println("  smem -p                 ← 进程内存明细");
        System.out.println("  cat /proc/<pid>/status  ← 进程内存详情");
        System.out.println();
        System.out.println("===== 磁盘 IO =====");
        System.out.println("  iostat -x 1             ← 磁盘 IO 统计");
        System.out.println("  iotop                   ← 进程 IO 监控");
        System.out.println("  df -h / du -sh          ← 磁盘容量排查");
        System.out.println();
        System.out.println("===== 网络 =====");
        System.out.println("  ss -tlnp                ← 监听端口");
        System.out.println("  ss -s                   ← 连接统计");
        System.out.println("  ss -tan state time-wait | wc -l ← TIME_WAIT 统计");
        System.out.println("  iftop / nethogs          ← 实时流量监控");
        System.out.println("  ping / traceroute / mtr  ← 网络连通性");
        System.out.println("  tcpdump -i eth0 port 80 ← 抓包");
    }

    public static void main(String[] args) {
        printTopFields();
        printFreeMemory();
        printIostatFields();
        simulateTimeWaitAnalysis();
        simulateDeadlockDetection();
        simulateDiskFullTroubleshooting();
        printCommonCommands();
    }
}