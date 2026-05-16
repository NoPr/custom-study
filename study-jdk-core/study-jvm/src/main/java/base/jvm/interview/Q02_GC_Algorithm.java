package base.jvm.interview;

/**
 * 面试题：GC 算法与收集器
 *
 * 高频考点：
 * 1. 对象存活判断 — 引用计数 vs 可达性分析
 * 2. GC Roots 有哪些
 * 3. 垃圾收集算法：标记-清除 / 标记-复制 / 标记-整理
 * 4. 经典收集器对比：Serial / Parallel / CMS / G1 / ZGC
 * 5. G1 原理 & 与传统分代收集器的区别
 * 6. Full GC 触发条件
 */
public class Q02_GC_Algorithm {

    public static void main(String[] args) {
        q1_objectSurvivalJudgment();
        q2_gcRoots();
        q3_collectionAlgorithms();
        q4_collectorComparison();
        q5_g1Principle();
        q6_fullGCTriggers();
    }

    /**
     * Q1：如何判断对象是否存活（可回收）？
     *
     * 引用计数法：
     * - 每个对象维护引用计数器
     * - 引用 +1，释放 -1，归零回收
     * - 无法处理循环引用（致命缺陷）
     * - Python 使用但配合标记-清除处理循环引用
     *
     * 可达性分析（Java 采用）：
     * - 以 GC Roots 为起点，通过引用链遍历
     * - 不可达的对象标记为可回收
     * - Java 中对象回收至少经过两次标记
     */
    static void q1_objectSurvivalJudgment() {
        System.out.println("=== Q1：对象存活判断 ===");
        System.out.println();
        System.out.println("┌─────────────────────┬────────────────────────┐");
        System.out.println("│   引用计数法           │   可达性分析               │");
        System.out.println("├─────────────────────┼────────────────────────┤");
        System.out.println("│ 每个对象维护引用计数器   │ 从 GC Roots 遍历引用链      │");
        System.out.println("│ 为 0 → 回收           │ 不可达 → 可回收             │");
        System.out.println("│ ❌ 循环引用不可解       │ ✅ 循环引用不是问题         │");
        System.out.println("│ Python（辅以标记清除）  │ Java、C#                  │");
        System.out.println("└─────────────────────┴────────────────────────┘");
        System.out.println();
        System.out.println("Java 可达性分析回收两步标记：");
        System.out.println("  ① 从 GC Roots 出发，标记所有可达对象");
        System.out.println("  ② 不可达对象 → 筛选是否需执行 finalize()");
        System.out.println("    - 未覆盖 finalize() 或已执行过 → 直接回收");
        System.out.println("    - 覆盖且未执行 → 放入 F-Queue → 执行 → 再次标记");
        System.out.println("    - finalize() 中重新建立引用 → 复活（仅一次机会）");
        System.out.println("  （JDK 9+ finalize() 已废弃，JDK 18+ 移除）");
        System.out.println();
    }

    /**
     * Q2：GC Roots 有哪些？
     *
     * 面试常考：至少说出 4 种
     * 1. 虚拟机栈（栈帧局部变量表）中引用的对象 ← 最常见
     * 2. 静态属性引用的对象
     * 3. 常量引用的对象（字符串常量池等）
     * 4. JNI（Native 方法）引用的对象
     * 5. synchronized 持有的对象
     * 6. JVM 内部引用（基本类型的 Class 对象、常驻异常等）
     */
    static void q2_gcRoots() {
        System.out.println("=== Q2：GC Roots 有哪些 ===");
        System.out.println();
        System.out.println("可作为 GC Roots 的对象：");
        System.out.println("  1. 虚拟机栈中引用的对象（局部变量、方法参数）");
        System.out.println("  2. 方法区/元空间中 static 属性引用的对象");
        System.out.println("  3. 方法区/元空间中常量引用的对象（字符串常量池）");
        System.out.println("  4. 本地方法栈中 JNI 引用的对象");
        System.out.println("  5. JVM 内部引用（Class 对象、异常对象）");
        System.out.println("  6. synchronized 持有的对象");
        System.out.println();
        System.out.println("示例：");
        System.out.println("  void method() {");
        System.out.println("    Object a = new Object(); // a 是 GC Root");
        System.out.println("    Object b = new Object(); // b 是 GC Root");
        System.out.println("    a = null;  // a 不再是 GC Root，原对象不可达");
        System.out.println("    b = new Object(); // b 指向新对象，旧对象不可达");
        System.out.println("  }");
        System.out.println("  // 方法结束 → 所有局部变量出栈 → 全部可回收");
        System.out.println();
    }

    /**
     * Q3：垃圾收集算法有哪些？各自优缺点？
     *
     * 标记-清除：最基础，产生碎片
     * 标记-复制：新生代，无碎片但浪费空间
     * 标记-整理：老年代，无碎片但 STW 长
     * 分代收集：新生代复制 + 老年代标记-整理
     */
    static void q3_collectionAlgorithms() {
        System.out.println("=== Q3：垃圾收集算法对比 ===");
        System.out.println();
        System.out.println("┌──────────┬──────────┬──────────┬──────────────────────┐");
        System.out.println("│ 算法      │ 效率      │ 碎片      │ 适用区域              │");
        System.out.println("├──────────┼──────────┼──────────┼──────────────────────┤");
        System.out.println("│ 标记-清除  │ 中等      │ ❌ 有     │ 老年代（CMS 基础）    │");
        System.out.println("│ 标记-复制  │ 高       │ ✅ 无     │ 新生代               │");
        System.out.println("│ 标记-整理  │ 较低      │ ✅ 无     │ 老年代（Parallel Old）│");
        System.out.println("└──────────┴──────────┴──────────┴──────────────────────┘");
        System.out.println();

        System.out.println("标记-清除（Mark-Sweep）：");
        System.out.println("  流程：标记存活 → 清除未标记");
        System.out.println("  缺点：内存碎片 → 大对象可能分配失败 → 提前触发 Full GC");
        System.out.println();
        System.out.println("标记-复制（Mark-Copy）— 新生代首选：");
        System.out.println("  流程：Eden + S0 存活对象 → 复制到 S1 → 清空 Eden + S0");
        System.out.println("  优点：无碎片，只需移动指针（bump-the-pointer）");
        System.out.println("  缺点：浪费 Survivor 空间（但 Eden:S0:S1=8:1:1 只浪费 10%）");
        System.out.println("  为什么新生代用复制？— 新生代 98% 对象朝生夕灭，复制成本极低");
        System.out.println();
        System.out.println("标记-整理（Mark-Compact）— 老年代之选：");
        System.out.println("  流程：标记存活 → 对象向一端移动 → 清理边界外内存");
        System.out.println("  优点：无碎片");
        System.out.println("  缺点：移动对象 + 更新引用 = 较长的 Stop-The-World");
        System.out.println();
    }

    /**
     * Q4：经典 GC 收集器对比
     *
     * 面试重点：至少能说出 3-4 种收集器的名称、特点、适用场景
     */
    static void q4_collectorComparison() {
        System.out.println("=== Q4：经典 GC 收集器对比 ===");
        System.out.println();
        System.out.println("┌──────────┬─────────┬───────────┬─────────────────────────────┐");
        System.out.println("│ 收集器    │ 代       │ 目标       │ 备注                         │");
        System.out.println("├──────────┼─────────┼───────────┼─────────────────────────────┤");
        System.out.println("│ Serial   │ 新生代   │ 单线程 STW │ Client 模式 / -Xmx<100M 默认  │");
        System.out.println("│ ParNew   │ 新生代   │ 多线程     │ CMS 的年轻代搭档（JDK 14 移除）│");
        System.out.println("│ Parallel │ 新生代   │ 吞吐量优先 │ JDK 8 默认，自适应调节        │");
        System.out.println("│ CMS      │ 老年代   │ 低延迟     │ JDK 14 移除，碎片+浮动垃圾     │");
        System.out.println("│ G1       │ 全堆     │ 可预测停顿 │ JDK 9+ 默认，Region 化       │");
        System.out.println("│ ZGC      │ 全堆     │ 超低延迟   │ <1ms，TB 级堆，染色指针       │");
        System.out.println("└──────────┴─────────┴───────────┴─────────────────────────────┘");
        System.out.println();
        System.out.println("选型矩阵：");
        System.out.println("  小堆(<2G)、单核  → Serial");
        System.out.println("  吞吐量优先        → Parallel");
        System.out.println("  低延迟(100~200ms) → G1");
        System.out.println("  超低延迟(<10ms)   → ZGC");
        System.out.println();
        System.out.println("CMS 被移除的三大原因：");
        System.out.println("  ① 标记-清除 → 内存碎片 → 提前 Full GC → Serial Old 单线程 STW");
        System.out.println("  ② 并发失败（Concurrent Mode Failure）→ Full GC（Serial Old）");
        System.out.println("  ③ 浮动垃圾：并发清除期间新产生的垃圾，只能下次 GC 回收");
        System.out.println();
    }

    /**
     * Q5：G1 收集器原理 & 与传统收集器的区别
     *
     * G1（Garbage First）核心思想：
     * - 不再物理分代（Eden/Survivor/Old 连续内存），而是逻辑分代
     * - 堆划分为多个大小相等的 Region（默认 2048 个，1~32MB 每个）
     * - 每个 Region 可动态扮演 Eden/Survivor/Old/Humongous 角色
     * - 优先回收垃圾最多的 Region（Garbage First）
     * - 可预测停顿时间模型 -XX:MaxGCPauseMillis（默认 200ms）
     *
     * 区别要点：
     * - 传统：新生代 = 连续大块内存
     * - G1：新生代 = 多个离散的 Eden Region + Survivor Region
     * - 传统：老年代 Full GC 扫描整个老年代
     * - G1：Mixed GC 可选部分老年代 Region 回收
     */
    static void q5_g1Principle() {
        System.out.println("=== Q5：G1 原理 ===");
        System.out.println();
        System.out.println("传统分代收集器 vs G1：");
        System.out.println("  传统：物理分代，Eden/Survivor/Old 是固定连续内存块");
        System.out.println("  G1  ：逻辑分代，堆由等大 Region 组成，角色动态分配");
        System.out.println();
        System.out.println("G1 Region 四种角色：");
        System.out.println("  Eden Region        — 新对象分配");
        System.out.println("  Survivor Region    — 存放从 Eden 复制来的存活对象");
        System.out.println("  Old Region         — 长期存活对象");
        System.out.println("  Humongous Region   — 大对象（超过单个 Region 50%）");
        System.out.println();
        System.out.println("G1 GC 周期：");
        System.out.println("  ① Young GC       — 回收所有 Eden Region → Survivor/Old");
        System.out.println("  ② 并发标记        — 与用户线程并发，确定存活对象");
        System.out.println("  ③ Mixed GC       — 回收部分 Old Region（基于回收价值排序）");
        System.out.println("  ④ Full GC        — Mixed GC 无法跟上分配速度时触发（G1 最差情况）");
        System.out.println();
        System.out.println("Remembered Set（RSet）：");
        System.out.println("  - 每个 Region 维护一个 RSet，记录外部到本 Region 的引用");
        System.out.println("  - 避免全堆扫描（避免遍历所有 GC Roots）");
        System.out.println("  - 缺点：RSet 占用额外内存（约堆的 5%）");
        System.out.println();
        System.out.println("G1 关键参数：");
        System.out.println("  -XX:MaxGCPauseMillis=200     期望最大停顿时间");
        System.out.println("  -XX:G1HeapRegionSize=N       Region 大小（1~32MB，2 的幂）");
        System.out.println("  -XX:InitiatingHeapOccupancyPercent=45  触发并发标记的堆占用比");
        System.out.println();
    }

    /**
     * Q6：Full GC 的触发条件有哪些？
     *
     * Full GC（Stop-The-World，回收整个堆包括方法区）触发条件：
     *
     * 1. 老年代空间不足（最常见）
     *    - 大对象直接进入老年代但空间不够
     *    - Young GC → 对象晋升老年代但空间不够
     *
     * 2. 元空间（Metaspace）不足（JDK 8+）
     *    - 类加载过多（动态代理、JSP、反射等）
     *
     * 3. System.gc() 显式调用
     *    - 生产环境用 -XX:+DisableExplicitGC 禁用
     *
     * 4. 空间担保失败
     *    - Minor GC 前检查老年代最大可用连续空间 < 新生代所有对象总和
     *
     * 5. CMS/G1 并发失败
     *    - CMS: Concurrent Mode Failure → 退化为 Serial Old
     *    - G1:  Evacuation Failure → Full GC
     *
     * 6. 堆 dump 请求（jmap -dump:live）
     */
    static void q6_fullGCTriggers() {
        System.out.println("=== Q6：Full GC 触发条件 ===");
        System.out.println();
        System.out.println("Full GC 六大触发条件：");
        System.out.println();
        System.out.println("1. 老年代空间不足");
        System.out.println("   - 对象晋升老年代时空间不够");
        System.out.println("   - 大对象（> -XX:PretenureSizeThreshold）直接进入老年代");
        System.out.println("   解决：增大堆 / 调整分代比例 / 优化代码减少对象创建");
        System.out.println();
        System.out.println("2. Metaspace 不足（JDK 8+）");
        System.out.println("   - 动态类加载导致元空间满（CGLIB、动态代理、大量 JSP）");
        System.out.println("   解决：-XX:MaxMetaspaceSize=512m / 排查类加载泄漏");
        System.out.println();
        System.out.println("3. System.gc() 显式调用");
        System.out.println("   - 开发建议 / 生产禁用（-XX:+DisableExplicitGC）");
        System.out.println();
        System.out.println("4. 空间担保失败（Handle Promotion Failure）");
        System.out.println("   - Minor GC 前判断：老年代剩余空间 < 新生代平均晋升大小");
        System.out.println("   - JDK 6 Update 24 后默认检查，失败则提前 Full GC");
        System.out.println();
        System.out.println("5. Concurrent Mode Failure（CMS）/ Evacuation Failure（G1）");
        System.out.println("   - CMS 并发标记时老年代被填满 → Serial Old → 长 STW");
        System.out.println("   - G1 Mixed GC 无法及时回收 → Full GC（单线程，极慢）");
        System.out.println();
        System.out.println("6. jmap -dump:live 或 JMX HotSpotDiagnostic.dumpHeap");
        System.out.println("   - 强制 Full GC 后再 dump（live 参数触发）");
        System.out.println();
    }
}