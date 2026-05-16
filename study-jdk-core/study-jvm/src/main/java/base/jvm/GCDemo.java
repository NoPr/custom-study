package base.jvm;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * GC 算法与收集器演示
 *
 * 核心知识点：
 * 1. 对象存活判断：引用计数法 vs 可达性分析
 * 2. GC Roots 枚举
 * 3. 四种引用（强/软/弱/虚）+ 使用场景
 * 4. 垃圾收集算法（标记-清除/标记-复制/标记-整理）
 * 5. 经典收集器：Serial / Parallel / CMS / G1 / ZGC
 * 6. System.gc() 建议但不保证
 */
public class GCDemo {

    public static void main(String[] args) {
        objectSurvivalJudgment();
        gcRootsEnumeration();
        garbageCollectionAlgorithms();
        collectorComparison();
        strongReferenceDemo();
        softReferenceDemo();
        weakReferenceDemo();
        phantomReferenceDemo();
        systemGcDemo();
        referenceComparison();
    }

    /**
     * 对象存活判断
     *
     * 引用计数法（Reference Counting）：
     * - 每个对象维护一个引用计数器，引用 +1，释放 -1
     * - 为 0 时回收
     * - 致命缺陷：无法解决循环引用（A → B, B → A 永远不为 0）
     * - Python 使用此方案，但配合标记-清除处理循环引用
     *
     * 可达性分析（Reachability Analysis）：
     * - 从 GC Roots 出发，通过引用链遍历，不可达的对象标记为可回收
     * - Java / C# 采用此方案
     * - 需要进行两次标记：① 不可达 → ② finalize() 自救失败 → 回收
     *   （finalize() 已被 JDK 9 标记为 Deprecated，JDK 18+ 彻底移除）
     */
    static void objectSurvivalJudgment() {
        System.out.println("=== 对象存活判断 ===");
        System.out.println();
        System.out.println("┌─────────────────────┬────────────────────────┐");
        System.out.println("│   引用计数法           │   可达性分析               │");
        System.out.println("├─────────────────────┼────────────────────────┤");
        System.out.println("│ 计数器 +1/-1         │ GC Roots 遍历引用链       │");
        System.out.println("│ 为 0 即回收           │ 不可达 → 标记回收          │");
        System.out.println("│ ❌ 循环引用无法处理     │ ✅ 能处理循环引用          │");
        System.out.println("│ Python（为主）        │ Java、C#                │");
        System.out.println("└─────────────────────┴────────────────────────┘");
        System.out.println();
        System.out.println("Java 可达性分析回收流程：");
        System.out.println("  ① 从 GC Roots 出发，标记所有可达对象");
        System.out.println("  ② 对不可达对象进行第一次标记与筛选");
        System.out.println("  ③ 筛选条件：是否需要执行 finalize()");
        System.out.println("  ④ 若重写了 finalize() 且未执行过 → 放入 F-Queue");
        System.out.println("  ⑤ 第二次标记：finalize() 自救失败 → 彻底回收");
        System.out.println("  （JDK 9+ finalize() 已废弃，不推荐使用）");
        System.out.println();
    }

    /**
     * GC Roots 枚举
     *
     * GC Roots 对象包括：
     * 1. 虚拟机栈（栈帧中的局部变量表）引用的对象
     * 2. 方法区/元空间中静态属性引用的对象
     * 3. 方法区/元空间中常量引用的对象（字符串常量池）
     * 4. 本地方法栈中 JNI（Native 方法）引用的对象
     * 5. Java 虚拟机内部的引用（基本类型 Class 对象、常驻异常对象、系统类加载器）
     * 6. 所有被同步锁（synchronized）持有的对象
     * 7. JMXBean、JVMTI 回调、本地代码缓存等
     */
    static void gcRootsEnumeration() {
        System.out.println("=== GC Roots 枚举 ===");
        System.out.println();
        System.out.println("可以作为 GC Roots 的对象：");
        System.out.println("  1. 虚拟机栈引用的对象（局部变量表）");
        System.out.println("  2. 静态属性引用的对象（static 字段）");
        System.out.println("  3. 常量引用的对象（字符串常量池等）");
        System.out.println("  4. JNI 引用的对象（Native 方法）");
        System.out.println("  5. JVM 内部引用（Class 对象、常驻异常等）");
        System.out.println("  6. synchronized 持有的对象");
        System.out.println();
        System.out.println("示例：以下方法执行时，局部变量 obj 是 GC Root");
        System.out.println("  void method() {");
        System.out.println("      Object obj = new Object(); // obj 引用链 → 对象不可回收");
        System.out.println("  }");
        System.out.println("  // 方法结束，obj 出栈，对象变为不可达，可回收");
        System.out.println();
    }

    /**
     * 垃圾收集算法
     *
     * 1. 标记-清除（Mark-Sweep）：最基础，产生内存碎片
     * 2. 标记-复制（Mark-Copy）：新生代常用（Eden → Survivor），无碎片但浪费一半空间
     * 3. 标记-整理（Mark-Compact）：老年代常用，无碎片但 STW 时间长
     * 4. 分代收集（Generational Collection）：新生代用复制，老年代用标记-清除或标记-整理
     */
    static void garbageCollectionAlgorithms() {
        System.out.println("=== 垃圾收集算法 ===");
        System.out.println();
        System.out.println("1. 标记-清除（Mark-Sweep）");
        System.out.println("   标记存活对象 → 清除未标记对象");
        System.out.println("   缺点：内存碎片，大对象可能分配失败");
        System.out.println();
        System.out.println("2. 标记-复制（Mark-Copy）");
        System.out.println("   内存分为两块 → 存活对象复制到另一块 → 清理当前块");
        System.out.println("   优点：无碎片，速度快");
        System.out.println("   缺点：浪费一半空间（改进：Eden:S0:S1=8:1:1 仅浪费 10%）");
        System.out.println("   适用：新生代");
        System.out.println();
        System.out.println("3. 标记-整理（Mark-Compact）");
        System.out.println("   标记存活对象 → 向一端移动 → 清理边界外内存");
        System.out.println("   优点：无碎片");
        System.out.println("   缺点：移动对象需要 STW，耗时较长");
        System.out.println("   适用：老年代");
        System.out.println();
        System.out.println("4. 分代收集（Generational Collection）");
        System.out.println("   新生代：标记-复制（对象存活率低）");
        System.out.println("   老年代：标记-清除或标记-整理（对象存活率高）");
        System.out.println();
    }

    /**
     * 经典收集器对比
     *
     * Serial:     单线程，STW，Client 模式默认
     * Parallel:   多线程，STW，吞吐量优先（JDK 8 默认）
     * CMS:        并发标记清除，低延迟，碎片问题（JDK 14 移除）
     * G1:         区域化，可预测停顿（JDK 9+ 默认）
     * ZGC:        超低延迟（<1ms），大堆（TB 级），JDK 15+ 生产可用
     */
    static void collectorComparison() {
        System.out.println("=== 经典 GC 收集器 ===");
        System.out.println();
        System.out.println("┌──────────┬────────┬───────────┬──────────────────────────────┐");
        System.out.println("│ 收集器    │ 算法    │ 目标       │ 备注                          │");
        System.out.println("├──────────┼────────┼───────────┼──────────────────────────────┤");
        System.out.println("│ Serial   │ 复制    │ 单线程 STW │ Client 模式默认               │");
        System.out.println("│ Parallel │ 复制    │ 吞吐量优先 │ JDK 8 默认，-XX:+UseParallelGC │");
        System.out.println("│ CMS      │ 标记-清除│ 低延迟     │ JDK 14 已移除，碎片问题        │");
        System.out.println("│ G1       │ 区域化  │ 可预测停顿 │ JDK 9+ 默认，-XX:+UseG1GC      │");
        System.out.println("│ ZGC      │ 染色指针│ 超低延迟   │ <1ms，TB 级堆，JDK 15+ 生产可用 │");
        System.out.println("│ Shenandoah│ 并发   │ 低延迟     │ Red Hat 贡献，JDK 12+          │");
        System.out.println("└──────────┴────────┴───────────┴──────────────────────────────┘");
        System.out.println();
        System.out.println("选择建议：");
        System.out.println("  小堆、低配置  → Serial（-XX:+UseSerialGC）");
        System.out.println("  吞吐量优先    → Parallel（JDK 8 默认）");
        System.out.println("  响应时间优先  → G1（-XX:+UseG1GC）");
        System.out.println("  超大堆、低延迟 → ZGC（-XX:+UseZGC）");
        System.out.println();
    }

    /**
     * 强引用（Strong Reference）— 默认引用类型
     * 只要强引用存在，GC 永远不会回收该对象
     * 即使内存不足抛出 OOM，也不会回收强引用对象
     */
    static void strongReferenceDemo() {
        System.out.println("=== 强引用（Strong Reference）===");
        Object obj = new Object();
        System.out.println("  obj = new Object()  创建强引用");
        System.out.println("  只要 obj 引用存在，对象永不回收");
        obj = null;
        System.out.println("  obj = null  解除引用，对象变为可回收");
        System.out.println("  场景：99% 的 Java 对象都是强引用");
        System.out.println();
    }

    /**
     * 软引用（Soft Reference）
     * 内存充足时不回收，内存不足（即将 OOM）时回收
     * 使用场景：缓存（如图片缓存、页面缓存）
     * JVM 参数：-XX:SoftRefLRUPolicyMSPerMB=N 控制存活时间
     */
    static void softReferenceDemo() {
        System.out.println("=== 软引用（Soft Reference）===");
        System.out.println("  SoftReference<byte[]> softRef = new SoftReference<>(new byte[1024*1024]);");
        System.out.println("  内存充足时：softRef.get() 可以获取到对象");
        System.out.println("  内存不足时：GC 回收软引用对象，softRef.get() 返回 null");
        System.out.println("  场景：缓存（MyBatis、Guava Cache 内部使用）");
        System.out.println();

        Object strongRef = new byte[10 * 1024 * 1024];
        SoftReference<Object> softRef = new SoftReference<>(strongRef);
        System.out.println("  软引用对象创建成功，当前 softRef.get() != null: " + (softRef.get() != null));
        System.out.println();
    }

    /**
     * 弱引用（Weak Reference）
     * 无论内存是否充足，GC 线程发现弱引用对象后立即回收
     * 生命周期：只存活到下一次 GC
     * 使用场景：WeakHashMap、ThreadLocal 的 Entry
     */
    static void weakReferenceDemo() {
        System.out.println("=== 弱引用（Weak Reference）===");

        Object obj = new Object();
        WeakReference<Object> weakRef = new WeakReference<>(obj);
        System.out.println("  GC 前 weakRef.get(): " + weakRef.get());

        obj = null;
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("  obj=null + GC 后 weakRef.get(): " + weakRef.get());
        System.out.println("  结论：弱引用只要被 GC 发现就回收，不管内存是否充足");
        System.out.println("  场景：WeakHashMap（key 被回收，entry 自动移除）");
        System.out.println("  场景：ThreadLocal.ThreadLocalMap.Entry（防止内存泄漏）");
        System.out.println();
    }

    /**
     * 虚引用（Phantom Reference）— 最弱的引用
     * 无法通过 get() 获取对象（始终返回 null）
     * 必须配合 ReferenceQueue 使用
     * 对象被回收时，虚引用入队 → 应用程序可收到通知
     * 使用场景：堆外内存（DirectByteBuffer）回收管理
     */
    static void phantomReferenceDemo() {
        System.out.println("=== 虚引用（Phantom Reference）===");

        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Object obj = new Object();
        PhantomReference<Object> phantomRef = new PhantomReference<>(obj, queue);

        System.out.println("  phantomRef.get(): " + phantomRef.get() + "（永远返回 null！）");
        System.out.println("  即使对象还没被回收，get() 也返回 null");

        obj = null;
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean enqueued = (queue.poll() != null);
        System.out.println("  obj=null + GC 后 ReferenceQueue 中是否有虚引用: " + enqueued);
        System.out.println("  场景：DirectByteBuffer 堆外内存释放（Cleaner 继承 PhantomReference）");
        System.out.println("  场景：NIO 零拷贝后通知 OS 回收直接内存");
        System.out.println();
    }

    /**
     * System.gc() — 仅建议 JVM 执行 GC，不保证立即执行
     *
     * HotSpot 中 System.gc() 默认触发 Full GC（stop-the-world）。
     * 生产环境建议使用 -XX:+DisableExplicitGC 禁用显式 GC。
     *
     * System.gc() vs Runtime.getRuntime().gc()：
     * - System.gc() 内部调用 Runtime.getRuntime().gc()
     * - 两者等价，都是建议性调用
     */
    static void systemGcDemo() {
        System.out.println("=== System.gc() / Runtime.gc() ===");
        System.out.println("  System.gc() 是建议性调用，JVM 不保证立即执行");
        System.out.println("  HotSpot 默认触发 Full GC（STW），生产环境建议禁用");
        System.out.println("  禁用方式：-XX:+DisableExplicitGC");
        System.out.println("  生产代码中不要手动调用 System.gc()");
        System.out.println();

        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long after = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("  System.gc() 前后内存: %d MB → %d MB%n",
                before / 1024 / 1024, after / 1024 / 1024);
        System.out.println();
    }

    /**
     * 四种引用对比总结
     */
    static void referenceComparison() {
        System.out.println("=== 四种引用对比总结 ===");
        System.out.println();
        System.out.println("┌──────────┬──────────────┬──────────────┬──────────────────────┐");
        System.out.println("│ 引用类型  │ 回收时机       │ get() 行为    │ 典型场景              │");
        System.out.println("├──────────┼──────────────┼──────────────┼──────────────────────┤");
        System.out.println("│ 强引用    │ 永不回收       │ 正常返回       │ 普通对象（99%）        │");
        System.out.println("│ 软引用    │ 内存不足时回收  │ GC 前返回对象  │ 缓存                  │");
        System.out.println("│ 弱引用    │ 下次 GC 即回收  │ GC 后返回 null │ WeakHashMap/ThreadLocal│");
        System.out.println("│ 虚引用    │ 回收时入队通知  │ 始终返回 null  │ 堆外内存管理           │");
        System.out.println("└──────────┴──────────────┴──────────────┴──────────────────────┘");
        System.out.println();
    }
}