package base.jvm;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 调优实战演示
 *
 * 核心知识点：
 * 1. JVM 关键参数分类（堆/栈/元空间/GC）
 * 2. 常用 GC 日志参数
 * 3. 调优流程：明确目标 → 选择收集器 → 调堆大小 → 调分代比例
 * 4. 常见问题诊断（OOM / CPU 100% / 死锁 / 频繁 Full GC）
 * 5. 常用诊断工具（jps / jstat / jmap / jstack / jinfo / jcmd / jhsdb）
 */
public class JVMTuningDemo {

    public static void main(String[] args) {
        jvmParameterReference();
        gcLogParameters();
        tuningFlow();
        commonProblems();
        diagnosticTools();
        memoryLeakSimulation();
    }

    /**
     * JVM 关键参数速查表
     */
    static void jvmParameterReference() {
        System.out.println("=== JVM 关键参数速查 ===");
        System.out.println();
        System.out.println("堆内存参数：");
        System.out.println("  -Xms<N>            初始堆大小（如 -Xms2g）");
        System.out.println("  -Xmx<N>            最大堆大小（如 -Xmx4g）");
        System.out.println("  -Xmn<N>            新生代大小（一般 1/4 ~ 1/3 堆）");
        System.out.println("  -XX:NewRatio=N     老年代:新生代 比例（默认 2，即老年代占 2/3）");
        System.out.println("  -XX:SurvivorRatio=N Eden:S0 比例（默认 8，即 Eden:S0:S1=8:1:1）");
        System.out.println();
        System.out.println("栈参数：");
        System.out.println("  -Xss<N>            线程栈大小（默认 1M）");
        System.out.println();
        System.out.println("元空间参数（JDK 8+）：");
        System.out.println("  -XX:MetaspaceSize=N       元空间初始大小");
        System.out.println("  -XX:MaxMetaspaceSize=N    元空间最大大小");
        System.out.println("  -XX:MinMetaspaceFreeRatio=N  GC 后最小空闲比");
        System.out.println();
        System.out.println("GC 选择器参数：");
        System.out.println("  -XX:+UseSerialGC          Serial 收集器");
        System.out.println("  -XX:+UseParallelGC        Parallel 收集器（JDK 8 默认）");
        System.out.println("  -XX:+UseConcMarkSweepGC   CMS 收集器（JDK 14 移除）");
        System.out.println("  -XX:+UseG1GC              G1 收集器（JDK 9+ 默认）");
        System.out.println("  -XX:+UseZGC               ZGC 收集器（JDK 15+ 生产可用）");
        System.out.println();
        System.out.println("生产环境推荐基线（4G 堆、Web 应用）：");
        System.out.println("  -Xms4g -Xmx4g -Xss256k");
        System.out.println("  -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m");
        System.out.println("  -XX:+UseG1GC -XX:MaxGCPauseMillis=200");
        System.out.println("  -XX:+HeapDumpOnOutOfMemoryError");
        System.out.println("  -XX:HeapDumpPath=/path/to/dump");
        System.out.println();
    }

    /**
     * GC 日志参数（JDK 9+ 统一日志框架）
     *
     * JDK 8:  -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log
     * JDK 9+: -Xlog:gc*=info:file=gc.log:time,level,tags
     */
    static void gcLogParameters() {
        System.out.println("=== GC 日志参数 ===");
        System.out.println();
        System.out.println("JDK 8 参数（旧格式）：");
        System.out.println("  -XX:+PrintGC            打印 GC 简要信息");
        System.out.println("  -XX:+PrintGCDetails     打印 GC 详细信息");
        System.out.println("  -XX:+PrintGCDateStamps  打印 GC 发生时间");
        System.out.println("  -Xloggc:gc.log          指定 GC 日志文件路径");
        System.out.println("  -XX:+PrintHeapAtGC      每次 GC 前后打印堆信息");
        System.out.println();
        System.out.println("JDK 9+ 统一日志参数：");
        System.out.println("  -Xlog:gc*=info:file=gc.log:time,level,tags");
        System.out.println("  level: off / trace / debug / info / warning / error");
        System.out.println("  tags:  gc / gc+heap / gc+metaspace / gc+ergo 等");
        System.out.println();
        System.out.println("常用 JDK 9+ 组合：");
        System.out.println("  -Xlog:gc*=info      控制台输出 GC 摘要");
        System.out.println("  -Xlog:gc+heap=debug 详细堆变化");
        System.out.println("  -Xlog:gc*=info:file=gc.log:time,uptime,level 文件输出");
        System.out.println();
    }

    /**
     * JVM 调优流程
     *
     * 1. 明确目标：吞吐量 / 延迟 / 内存占用
     * 2. 选择收集器：根据目标选 GC
     * 3. 调堆大小：平衡 Minor GC 和 Full GC 频率
     * 4. 调分代比例：根据对象生命周期调整
     * 5. 观察 → 调整 → 再观察（迭代优化）
     */
    static void tuningFlow() {
        System.out.println("=== JVM 调优流程 ===");
        System.out.println();
        System.out.println("步骤 1：明确优化目标");
        System.out.println("  吞吐量优先 → Parallel GC（-XX:+UseParallelGC）");
        System.out.println("  低延迟优先 → G1 / ZGC");
        System.out.println("  内存优先   → 减少堆大小 + Serial GC");
        System.out.println();
        System.out.println("步骤 2：确定初始堆大小");
        System.out.println("  -Xms 和 -Xmx 设置相同值，避免堆扩容/收缩开销");
        System.out.println("  建议：不超过物理内存的 75%");
        System.out.println();
        System.out.println("步骤 3：调整新生代大小");
        System.out.println("  新生代越大 → Minor GC 频率越低，但单次时间越长");
        System.out.println("  新生代越小 → Minor GC 频率越高，但单次时间越短");
        System.out.println("  经验值：新生代占堆 1/4 ~ 1/3（-Xmn 或 -XX:NewRatio）");
        System.out.println();
        System.out.println("步骤 4：监控 & 迭代");
        System.out.println("  jstat -gc <pid> 1000 100  ← 每秒采集一次 GC 数据");
        System.out.println("  GCViewer / GCeasy  ← GC 日志可视化分析");
        System.out.println("  观察 Young GC 频率（正常：几秒~几十秒一次）");
        System.out.println("  观察 Full GC 频率（正常：极少甚至没有）");
        System.out.println();
    }

    /**
     * 常见问题诊断速查
     */
    static void commonProblems() {
        System.out.println("=== 常见 JVM 问题诊断速查 ===");
        System.out.println();
        System.out.println("1. OOM（OutOfMemoryError）");
        System.out.println("   java heap space     → 堆内存不足，增大 -Xmx 或排查内存泄漏");
        System.out.println("   GC overhead limit   → GC 耗时超过 98% 但回收 <2% 堆");
        System.out.println("   Metaspace           → 元空间不足，增大 -XX:MaxMetaspaceSize");
        System.out.println("   unable to create native thread → 线程数超限，减小 -Xss 或调整 ulimit");
        System.out.println("   解决：-XX:+HeapDumpOnOutOfMemoryError → 分析 dump 文件");
        System.out.println();
        System.out.println("2. CPU 100%");
        System.out.println("   ① top -Hp <pid> 找到高 CPU 线程");
        System.out.println("   ② printf '%x\\n' <tid> 转 16 进制");
        System.out.println("   ③ jstack <pid> | grep <hex_tid> 定位代码");
        System.out.println();
        System.out.println("3. 频繁 Full GC");
        System.out.println("   可能原因：大对象分配过快 / 老年代空间不足 / System.gc() 显式调用");
        System.out.println("   排查：jstat -gcutil <pid> 1s 观察 FGC 列");
        System.out.println("   解决：增大老年代 / 调整 Survivor 比例 / -XX:+DisableExplicitGC");
        System.out.println();
        System.out.println("4. 死锁");
        System.out.println("   jstack <pid> → 检查 Found one Java-level deadlock");
        System.out.println("   或 jcmd <pid> Thread.print");
        System.out.println();
    }

    /**
     * 常用诊断工具速查
     */
    static void diagnosticTools() {
        System.out.println("=== 常用 JVM 诊断工具 ===");
        System.out.println();
        System.out.println("┌────────────┬──────────────────────────────────────────────┐");
        System.out.println("│ 工具        │ 用途                                          │");
        System.out.println("├────────────┼──────────────────────────────────────────────┤");
        System.out.println("│ jps        │ 列出 Java 进程 PID（-l 显示全类名）            │");
        System.out.println("│ jstat      │ 监控 JVM 统计信息（-gc / -gcutil / -class）    │");
        System.out.println("│ jmap       │ 生成堆 dump / 查看堆信息（-heap / -histo）     │");
        System.out.println("│ jstack     │ 线程堆栈快照 / 死锁检测（-l 显示锁信息）        │");
        System.out.println("│ jinfo      │ 查看/修改 JVM 运行时参数                       │");
        System.out.println("│ jcmd       │ JDK 7+ 全能命令（jmap + jstack + jinfo 功能）  │");
        System.out.println("│ jhsdb      │ JDK 9+ 替代 jmap/jstack/jinfo 的后端工具       │");
        System.out.println("│ jconsole   │ 图形化监控（内存/线程/类/MBean）                │");
        System.out.println("│ jvisualvm  │ 多功能可视化工具（插件生态丰富）                │");
        System.out.println("│ MAT        │ Eclipse Memory Analyzer，堆 dump 分析利器      │");
        System.out.println("│ Arthas     │ 阿里开源，在线诊断（watch/trace/thread/...）    │");
        System.out.println("└────────────┴──────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("最常用命令组合：");
        System.out.println("  jps -l                    → 找 PID");
        System.out.println("  jstat -gcutil <pid> 1s    → 实时 GC 监控");
        System.out.println("  jstack <pid> > thread.txt → 导出线程快照");
        System.out.println("  jmap -histo:live <pid>    → 存活对象统计");
        System.out.println("  jmap -dump:live,file=heap.hprof <pid> → 导出堆 dump");
        System.out.println();
    }

    /**
     * 内存泄漏模拟
     * 无限向 List 添加对象，触发 OOM
     */
    static void memoryLeakSimulation() {
        System.out.println("=== 内存泄漏模拟（演示代码，默认注掉）===");
        System.out.println("// 取消注释以下代码可观察 OOM 行为：");
        System.out.println("// List<byte[]> list = new ArrayList<>();");
        System.out.println("// while (true) {");
        System.out.println("//     list.add(new byte[1024 * 1024]); // 每次添加 1MB");
        System.out.println("//     Thread.sleep(10);");
        System.out.println("// }");
        System.out.println();
        System.out.println("诊断命令：");
        System.out.println("  jcmd <pid> GC.heap_dump heap.hprof");
        System.out.println("  jmap -histo:live <pid> | head -20");
        System.out.println("  MAT / JProfiler 分析 heap.hprof");
        System.out.println();
    }
}