package base.jvm.interview;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试题：OOM 诊断与排查
 *
 * 高频考点：
 * 1. 常见 OOM 类型及原因
 * 2. OOM 排查流程（获取 dump → MAT 分析 → 定位代码）
 * 3. 内存泄漏 vs 内存溢出
 * 4. 常见内存泄漏场景（ThreadLocal / 集合 / 连接未关闭）
 * 5. 堆外内存 OOM（DirectByteBuffer）
 */
public class Q03_OOM_Diagnose {

    public static void main(String[] args) {
        q1_oomTypes();
        q2_diagnoseFlow();
        q3_leakVsOverflow();
        q4_commonLeakScenarios();
        q5_directMemoryOOM();
        q6_preventionStrategies();
    }

    /**
     * Q1：常见的 OOM 类型有哪些？
     *
     * 1. java.lang.OutOfMemoryError: Java heap space
     *    堆内存不足，最常见
     *
     * 2. java.lang.OutOfMemoryError: GC overhead limit exceeded
     *    GC 耗时 > 98% 总时间，但回收 < 2% 堆 → JVM 放弃治疗
     *
     * 3. java.lang.OutOfMemoryError: Metaspace（JDK 8+）/ PermGen space（JDK 7-）
     *    方法区/元空间已满
     *
     * 4. java.lang.OutOfMemoryError: unable to create new native thread
     *    无法创建新线程（线程数超 OS 限制或内存不足分配栈空间）
     *
     * 5. java.lang.OutOfMemoryError: Direct buffer memory
     *    堆外内存（DirectByteBuffer）不足
     */
    static void q1_oomTypes() {
        System.out.println("=== Q1：OOM 类型速查 ===");
        System.out.println();
        System.out.println("┌─────────────────────────────────────┬──────────────────────────────────┐");
        System.out.println("│ OOM 类型                            │ 原因 & 解决                       │");
        System.out.println("├─────────────────────────────────────┼──────────────────────────────────┤");
        System.out.println("│ Java heap space                     │ 堆满 → 增大 -Xmx / 排查内存泄漏   │");
        System.out.println("│ GC overhead limit exceeded          │ GC 无效 → 同 heap space 处理      │");
        System.out.println("│ Metaspace                           │ 方法区满 → 增大 MaxMetaspaceSize  │");
        System.out.println("│ unable to create native thread      │ 线程数超限 → 减小 -Xss / 调整 ulimit│");
        System.out.println("│ Direct buffer memory                │ 堆外内存满 → 增大 -XX:MaxDirectMemorySize│");
        System.out.println("│ Requested array size exceeds VM lim │ 数组过大 > Integer.MAX_VALUE-2     │");
        System.out.println("└─────────────────────────────────────┴──────────────────────────────────┘");
        System.out.println();
    }

    /**
     * Q2：OOM 排查流程是怎样的？
     *
     * 标准排查四步走：
     *
     * ① 保留现场：配置 JVM 参数自动 dump
     *    -XX:+HeapDumpOnOutOfMemoryError
     *    -XX:HeapDumpPath=/path/to/dump.hprof
     *
     * ② 获取 dump：
     *    jmap -dump:format=b,file=heap.hprof <pid>
     *    jcmd <pid> GC.heap_dump heap.hprof
     *
     * ③ 分析 dump（MAT / JProfiler / VisualVM）：
     *    - Leak Suspects Report → 嫌疑对象
     *    - Dominator Tree → 最大内存占用对象
     *    - Histogram → 按类统计对象数量和大小
     *    - GC Roots 路径 → 谁引用了这些对象
     *
     * ④ 定位代码 & 修复
     */
    static void q2_diagnoseFlow() {
        System.out.println("=== Q2：OOM 排查流程 ===");
        System.out.println();
        System.out.println("第一步 — 保留现场（提前配置）：");
        System.out.println("  -XX:+HeapDumpOnOutOfMemoryError");
        System.out.println("  -XX:HeapDumpPath=/data/logs/dump/");
        System.out.println("  -XX:+ExitOnOutOfMemoryError（可选，OOM 后自动退出）");
        System.out.println();
        System.out.println("第二步 — 获取 dump 文件：");
        System.out.println("  jmap -dump:live,format=b,file=heap.hprof <pid>");
        System.out.println("  jcmd <pid> GC.heap_dump /path/to/dump.hprof");
        System.out.println("  注意：live 参数会先触发一次 Full GC");
        System.out.println();
        System.out.println("第三步 — MAT 分析 dump：");
        System.out.println("  ① 打开 hprof 文件 → Leak Suspects Report");
        System.out.println("  ② 查看 Dominator Tree（支配树） → 哪些对象占了最多内存");
        System.out.println("  ③ Histogram → 按类统计 → 找到数量/大小异常的类");
        System.out.println("  ④ Path to GC Roots → 追踪引用链 → 定位泄漏代码");
        System.out.println();
        System.out.println("第四步 — 修复：");
        System.out.println("  ① 找到大对象持有者 → 追溯 GC Root 引用链");
        System.out.println("  ② 确认是否需要保持引用 → 不需要则置 null / 用弱引用");
        System.out.println("  ③ 检查 Connection / Stream 是否正确关闭（try-with-resources）");
        System.out.println("  ④ 检查 ThreadLocal 是否 remove()");
        System.out.println();
    }

    /**
     * Q3：内存泄漏 vs 内存溢出
     *
     * 内存泄漏（Memory Leak）：
     * - 已不再使用的对象仍然被 GC Root 引用，无法回收
     * - 日积月累 → GC 越来越频繁 → 最终 OOM
     *
     * 内存溢出（Out of Memory）：
     * - JVM 堆/元空间/直接内存不够用
     * - 可能是泄漏导致，也可能是堆参数设置过小
     */
    static void q3_leakVsOverflow() {
        System.out.println("=== Q3：内存泄漏 vs 内存溢出 ===");
        System.out.println();
        System.out.println("内存泄漏（Memory Leak）：");
        System.out.println("  特征：无用对象被持有引用 → 无法回收 → 持续积累");
        System.out.println("  现象：");
        System.out.println("   ");

        int maxIterations = 5;
        List<byte[]> holder = new ArrayList<>();
        System.out.println("  模拟内存泄漏：");
        for (int i = 0; i < maxIterations; i++) {
            holder.add(new byte[10 * 1024 * 1024]);
            Runtime runtime = Runtime.getRuntime();
            long used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            System.out.printf("    第 %d 次添加 10MB, 当前已用: %d MB%n", i + 1, used);
        }
        System.out.println("  如果不清除 holder，10MB x 5 = 50MB 将无法回收 → 内存泄漏");
        System.out.println("  holder.clear() 后 → GC 可以回收");
        holder.clear();
        System.out.println();
        System.out.println("内存溢出（Out of Memory）：");
        System.out.println("  本质：JVM 空间不足以容纳新对象");
        System.out.println("  原因：内存泄漏 / 堆设置过小 / 瞬时大对象 / Metaspace 满了");
        System.out.println();
    }

    /**
     * Q4：常见内存泄漏场景
     *
     * 场景 1：ThreadLocal 未 remove()
     * - ThreadLocalMap 的 Entry 是弱引用 key，但 value 是强引用
     * - key 被回收后，value 永远不会被回收 → 内存泄漏
     * - 解决：finally 中调用 ThreadLocal.remove()
     *
     * 场景 2：静态集合持有对象
     * - static Map/List 添加对象 → 永不释放
     * - 解决：用完后 remove()，或使用 WeakHashMap
     *
     * 场景 3：连接/流未关闭
     * - Connection / Statement / ResultSet / InputStream / OutputStream
     * - 解决：try-with-resources（JDK 7+）
     *
     * 场景 4：内部类持有外部类引用
     * - 非静态内部类默认持有外部类引用
     * - 如果内部类对象生命周期长于外部类 → 外部类无法回收
     * - 解决：用静态内部类 + WeakReference
     *
     * 场景 5：监听器/回调未注销
     * - addListener 后未 removeListener
     * - 解决：生命周期结束时注销
     */
    static void q4_commonLeakScenarios() {
        System.out.println("=== Q4：常见内存泄漏场景 ===");
        System.out.println();
        System.out.println("场景 1 — ThreadLocal 未 remove()：");
        System.out.println("  ThreadLocal<User> local = new ThreadLocal<>();");
        System.out.println("  local.set(new User());");
        System.out.println("  // 线程池线程复用 → ThreadLocalMap 的 value 永不回收！");
        System.out.println("  解决：try { ... } finally { local.remove(); }");
        System.out.println();
        System.out.println("场景 2 — 静态集合持有对象：");
        System.out.println("  static List<Object> cache = new ArrayList<>();");
        System.out.println("  cache.add(obj);  // obj 永远不会被回收");
        System.out.println("  解决：使用 WeakHashMap / 缓存淘汰策略 / 定期清理");
        System.out.println();
        System.out.println("场景 3 — 资源未关闭：");
        System.out.println("  Connection conn = dataSource.getConnection();");
        System.out.println("  // 忘记 close() → 连接泄漏 → 堆 + 网络资源泄漏");
        System.out.println("  解决：try (Connection conn = ds.getConnection()) { ... }");
        System.out.println();
        System.out.println("场景 4 — 内部类持有外部类引用：");
        System.out.println("  class Outer {");
        System.out.println("    class Inner { }  // 非静态内部类持有 Outer.this");
        System.out.println("  }");
        System.out.println("  解决：声明为 static class Inner");
        System.out.println();
        System.out.println("场景 5 — 监听器未注销：");
        System.out.println("  eventSource.addListener(listener);");
        System.out.println("  // 未 removeListener → 导致监听器和被监听对象都无法回收");
        System.out.println("  解决：生命周期结束时 removeListener");
        System.out.println();
    }

    /**
     * Q5：堆外内存 OOM（Direct buffer memory）
     *
     * DirectByteBuffer：
     * - 通过 Unsafe.allocateMemory() 分配堆外内存
     * - 不归 JVM GC 管理，由 Cleaner（虚引用）回收
     * - 默认大小 ≈ -Xmx
     * - 参数：-XX:MaxDirectMemorySize=N
     *
     * 常见场景：
     * - NIO ByteBuffer.allocateDirect()
     * - Netty 的 PooledByteBufAllocator
     * - 需要手动释放或依赖 GC 触发 Cleaner
     */
    static void q5_directMemoryOOM() {
        System.out.println("=== Q5：堆外内存 OOM ===");
        System.out.println();
        System.out.println("堆外内存（Direct Memory）特点：");
        System.out.println("  - 通过 Unsafe.allocateMemory() 分配");
        System.out.println("  - 不由 JVM GC 直接管理");
        System.out.println("  - 回收依赖 Cleaner（虚引用）+ System.gc() 或手动释放");
        System.out.println("  - 默认上限 ≈ -Xmx（受 -XX:MaxDirectMemorySize 控制）");
        System.out.println();
        System.out.println("排查命令：");
        System.out.println("  jcmd <pid> VM.native_memory summary");
        System.out.println("  （需添加启动参数 -XX:NativeMemoryTracking=summary）");
        System.out.println();
        System.out.println("常见原因：");
        System.out.println("  ① Netty/Dubbo 未释放 ByteBuf（ReferenceCountUtil.release）");
        System.out.println("  ② NIO DirectByteBuffer 分配频繁但 GC 未及时触发 Cleaner");
        System.out.println("  ③ Unsafe.allocateMemory 手动分配未配对 free");
        System.out.println();
        System.out.println("解决：");
        System.out.println("  - 增大 -XX:MaxDirectMemorySize");
        System.out.println("  - Netty: 检查 ByteBuf 释放、使用池化分配器");
        System.out.println("  - 手动调用 System.gc() 触发 Cleaner（生产慎用）");
        System.out.println();
    }

    /**
     * Q6：预防 OOM 的最佳实践
     */
    static void q6_preventionStrategies() {
        System.out.println("=== Q6：预防 OOM 最佳实践 ===");
        System.out.println();
        System.out.println("编码层面：");
        System.out.println("  ① 使用 try-with-resources 自动关闭资源");
        System.out.println("  ② ThreadLocal 使用完务必 remove()");
        System.out.println("  ③ 集合/缓存设置容量上限 + 淘汰策略（LRU / TTL）");
        System.out.println("  ④ 避免在循环中创建大对象（String += → StringBuilder）");
        System.out.println("  ⑤ 大对象复用（对象池），减少 GC 压力");
        System.out.println("  ⑥ 使用弱引用/软引用存储缓存");
        System.out.println();
        System.out.println("JVM 参数层面：");
        System.out.println("  ① -XX:+HeapDumpOnOutOfMemoryError  自动 dump");
        System.out.println("  ② -XX:HeapDumpPath=/path/           指定 dump 路径");
        System.out.println("  ③ -verbose:gc / -Xlog:gc*           启用 GC 日志");
        System.out.println("  ④ -XX:+PrintGCDetails（JDK 8）      打印 GC 详情");
        System.out.println("  ⑤ -XX:+DisableExplicitGC            禁用显式 GC");
        System.out.println();
        System.out.println("监控层面：");
        System.out.println("  ① JVM 内存使用率告警（Prometheus + Grafana）");
        System.out.println("  ② GC 频率和停顿时间监控");
        System.out.println("  ③ 线程数监控（防止 thread OOM）");
        System.out.println("  ④ Metaspace 使用率监控");
        System.out.println("  ⑤ 堆外内存使用监控（JMX BufferPoolMXBean）");
        System.out.println();
    }
}