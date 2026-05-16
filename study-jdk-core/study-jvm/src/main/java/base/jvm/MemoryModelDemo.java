package base.jvm;

/**
 * JVM 内存模型演示
 *
 * 核心知识点：
 * 1. JVM 运行时数据区五大块：堆、方法区/元空间、虚拟机栈、程序计数器、本地方法栈
 * 2. 堆内存分代：新生代（Eden:S0:S1 = 8:1:1）+ 老年代
 * 3. JDK 8+ 方法区 → 元空间（Metaspace），使用本地内存
 * 4. 虚拟机栈：栈帧（局部变量表、操作数栈、动态链接、返回地址）
 * 5. StackOverflowError 演示（递归过深）
 * 6. Runtime.getRuntime() 内存信息
 */
public class MemoryModelDemo {

    public static void main(String[] args) {
        jvmRuntimeDataAreaOverview();
        heapMemoryDetail();
        methodAreaAndMetaspace();
        virtualMachineStackDetail();
        pcRegisterAndNativeMethodStack();
        runtimeMemoryInfo();
        stackOverflowDemo();
    }

    /**
     * JVM 运行时数据区全景图
     *
     * 线程私有：虚拟机栈、程序计数器、本地方法栈
     * 线程共享：堆、方法区/元空间
     */
    static void jvmRuntimeDataAreaOverview() {
        System.out.println("=== JVM 运行时数据区全景 ===");
        System.out.println("┌──────────────────────────────────────────────────┐");
        System.out.println("│                  JVM 运行时数据区                    │");
        System.out.println("├──────────┬──────────┬──────────┬─────────────────┤");
        System.out.println("│ 线程私有  │ 线程私有  │ 线程私有   │   线程共享        │");
        System.out.println("├──────────┼──────────┼──────────┼─────────────────┤");
        System.out.println("│ 程序计数器 │ 虚拟机栈  │ 本地方法栈 │  堆    │ 方法区    │");
        System.out.println("│ (PC)    │ (Stack)  │ (Native) │ (Heap) │(Method)  │");
        System.out.println("│ 当前指令  │ 栈帧     │ native   │ 对象/  │ 类信息/    │");
        System.out.println("│ 行号     │ StackFrame│ 方法栈   │ 数组   │ 常量/     │");
        System.out.println("│         │          │          │        │ 静态变量   │");
        System.out.println("└──────────┴──────────┴──────────┴────────┴─────────┘");
        System.out.println();
    }

    /**
     * 堆内存详细分代结构
     *
     * 新生代 = Eden + S0 + S1（默认比例 8:1:1）
     * - Eden: 新对象优先分配在此
     * - S0 / S1（Survivor From/To）: 每次 Minor GC 幸存对象复制到这
     *
     * 老年代：存放长期存活的对象（经历多次 Minor GC 仍存活）
     *
     * JDK 8 及以后默认使用 Parallel GC；
     * JDK 9+ 默认 GC 是 G1，分代结构由 G1 的 Region 替代。
     */
    static void heapMemoryDetail() {
        System.out.println("=== 堆内存分代结构（传统分代 GC 视角）===");
        System.out.println();
        System.out.println("  ┌──────────────────────────────────────┐");
        System.out.println("  │              堆内存 Heap              │");
        System.out.println("  ├────────────────────┬─────────────────┤");
        System.out.println("  │   新生代 Young      │   老年代 Old      │");
        System.out.println("  │   (占堆 1/3 默认)   │   (占堆 2/3 默认)  │");
        System.out.println("  ├────────┬─────┬─────┤─────────────────┤");
        System.out.println("  │ Eden   │ S0  │ S1  │   Tenured        │");
        System.out.println("  │ (8/10) │(1/10)│(1/10)│                  │");
        System.out.println("  │ 新对象  │ Survivor │ Survivor │  长期存活对象       │");
        System.out.println("  │ 分配     │  From  │  To   │                  │");
        System.out.println("  └────────┴─────┴─────┴─────────────────┘");
        System.out.println();
        System.out.println("对象流转路径：");
        System.out.println("  Eden → S0/S1（反复复制，每复制一次 GC 年龄 +1）");
        System.out.println("  S0/S1 → Old（年龄超过 -XX:MaxTenuringThreshold=15，进入老年代）");
        System.out.println();
        System.out.println("特殊情况直接进入老年代：");
        System.out.println("  - 大对象（超过 -XX:PretenureSizeThreshold）");
        System.out.println("  - Survivor 区放不下的对象（空间担保失败）");
        System.out.println();
    }

    /**
     * 方法区 → 元空间（Metaspace）演变
     *
     * JDK 7 及以前：方法区 = 永久代（PermGen），属于堆，-XX:MaxPermSize 设置
     * JDK 8+：方法区 = 元空间（Metaspace），使用本地内存（Native Memory）
     *
     * 元空间存储：类信息、方法信息、常量池、静态变量（移到了堆中的 Class 对象）
     *
     * 关键参数：
     * -XX:MetaspaceSize=N    触发 Full GC 的初始阈值
     * -XX:MaxMetaspaceSize=N 元空间最大大小（默认无限制）
     * -XX:MinMetaspaceFreeRatio=N  GC 后最小空闲比
     */
    static void methodAreaAndMetaspace() {
        System.out.println("=== 方法区 → 元空间（Metaspace）===");
        System.out.println();
        System.out.println("┌───────────────────────┬─────────────────────────┐");
        System.out.println("│      JDK 7 及以前      │      JDK 8+              │");
        System.out.println("├───────────────────────┼─────────────────────────┤");
        System.out.println("│ 永久代 (PermGen)       │ 元空间 (Metaspace)        │");
        System.out.println("│ 属于堆内存             │ 本地内存 (Native Memory)   │");
        System.out.println("│ -XX:MaxPermSize=256m  │ -XX:MaxMetaspaceSize=N   │");
        System.out.println("│ 有上限，容易 OOM       │ 默认无上限                │");
        System.out.println("└───────────────────────┴─────────────────────────┘");
        System.out.println();
        System.out.println("元空间存储内容：");
        System.out.println("  - 类的元数据（Klass 结构）");
        System.out.println("  - 方法信息（字节码、异常表）");
        System.out.println("  - 运行时常量池（符号引用 → 直接引用）");
        System.out.println("  - JIT 编译后的代码缓存（CodeCache，不属于 Metaspace）");
        System.out.println();
        System.out.println("注意：JDK 8+ 字符串常量池和静态变量已移至堆中。");
        System.out.println();
    }

    /**
     * 虚拟机栈 — 栈帧结构
     *
     * 每个线程启动时分配自己的虚拟机栈。
     * 每个方法调用 → 一个栈帧入栈。
     * 每个方法返回 → 一个栈帧出栈。
     *
     * 栈帧包含：
     * 1. 局部变量表：存放方法参数和局部变量（基本类型 + 对象引用）
     * 2. 操作数栈：字节码指令执行时的操作数中转站
     * 3. 动态链接：指向运行时常量池中该方法的符号引用
     * 4. 返回地址：方法返回后继续执行的字节码行号
     *
     * 两个异常：
     * - StackOverflowError: 栈深度超过虚拟机允许深度（递归过深）
     * - OutOfMemoryError: 栈扩展时无法申请到足够内存
     */
    static void virtualMachineStackDetail() {
        System.out.println("=== 虚拟机栈 & 栈帧 ===");
        System.out.println();
        System.out.println("  线程 1 栈              线程 2 栈");
        System.out.println("  ┌──────────┐          ┌──────────┐");
        System.out.println("  │ 栈帧 N    │ ← 栈顶    │ 栈帧 M    │");
        System.out.println("  │ ┌──────┐ │          │ ┌──────┐ │");
        System.out.println("  │ │局部变量│ │          │ │局部变量│ │");
        System.out.println("  │ │操作数栈│ │          │ │操作数栈│ │");
        System.out.println("  │ │动态链接│ │          │ │动态链接│ │");
        System.out.println("  │ │返回地址│ │          │ │返回地址│ │");
        System.out.println("  │ └──────┘ │          │ └──────┘ │");
        System.out.println("  ├──────────┤          ├──────────┤");
        System.out.println("  │ 栈帧 N-1  │          │    ...    │");
        System.out.println("  ├──────────┤          ├──────────┤");
        System.out.println("  │    ...    │          │ 栈帧 1    │");
        System.out.println("  ├──────────┤          ├──────────┤");
        System.out.println("  │ 栈帧 1    │          │   空闲     │");
        System.out.println("  └──────────┘          └──────────┘");
        System.out.println();
        System.out.println("栈大小参数：-Xss（默认 1M，Linux x64）");
        System.out.println();
    }

    /**
     * 程序计数器（PC Register）和本地方法栈
     *
     * 程序计数器：
     * - 当前线程执行的字节码行号指示器
     * - 唯一不会 OOM 的区域
     * - Native 方法时值为空（undefined）
     *
     * 本地方法栈：
     * - 为 Native 方法服务（HotSpot 中与虚拟机栈合二为一）
     * - 也会抛出 StackOverflowError / OOM
     */
    static void pcRegisterAndNativeMethodStack() {
        System.out.println("=== 程序计数器 & 本地方法栈 ===");
        System.out.println("程序计数器（Program Counter Register）：");
        System.out.println("  - 记录当前线程执行到的字节码行号");
        System.out.println("  - 分支、循环、跳转、异常处理、线程恢复都依赖它");
        System.out.println("  - 占用内存极小，是 JVM 规范中唯一不会 OOM 的区域");
        System.out.println();
        System.out.println("本地方法栈（Native Method Stack）：");
        System.out.println("  - 为 JVM 使用到的 Native 方法服务（如 Unsafe、Thread 底层）");
        System.out.println("  - HotSpot VM 将本地方法栈和虚拟机栈合并");
        System.out.println();
    }

    /**
     * Runtime.getRuntime() 获取 JVM 内存信息
     *
     * maxMemory() — JVM 能从操作系统获取的最大内存（-Xmx）
     * totalMemory() — JVM 当前已向操作系统申请的内存（≈ 已提交）
     * freeMemory() — totalMemory 中空闲的部分
     * usedMemory = total - free
     */
    static void runtimeMemoryInfo() {
        System.out.println("=== Runtime 内存信息 ===");

        Runtime runtime = Runtime.getRuntime();

        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        System.out.printf("最大可用内存 (-Xmx)        : %d MB%n", maxMemory / 1024 / 1024);
        System.out.printf("当前已申请内存 (total)       : %d MB%n", totalMemory / 1024 / 1024);
        System.out.printf("当前空闲内存 (free)         : %d MB%n", freeMemory / 1024 / 1024);
        System.out.printf("当前已使用内存 (used)        : %d MB%n", usedMemory / 1024 / 1024);
        System.out.printf("可用处理器数量               : %d%n", runtime.availableProcessors());
        System.out.println();

        System.out.println("计算公式：");
        System.out.println("  usedMemory = totalMemory - freeMemory");
        System.out.println("  JVM 可扩展空间 = maxMemory - totalMemory");
        System.out.println();
    }

    /**
     * StackOverflowError 演示
     * 通过无限递归（没有终止条件）使栈帧压满虚拟机栈
     *
     * 默认栈大小约 1M，每次递归一个栈帧约占几十字节 ~ 几百字节
     * 递归深度通常在 5000 ~ 20000 之间
     */
    static void stackOverflowDemo() {
        System.out.println("=== StackOverflowError 演示 ===");
        System.out.println("通过递归不断创建栈帧直到栈空间耗尽...");
        System.out.println();

        int depth = safeRecursion(0);
        System.out.println("安全递归深度（有终止条件）: " + depth + " 层");
        System.out.println();

        try {
            infiniteRecursion(0);
        } catch (StackOverflowError e) {
            System.out.println("捕获到 StackOverflowError，栈帧已耗尽！");
        }
        System.out.println();
    }

    static int safeRecursion(int depth) {
        if (depth >= 100) {
            return depth;
        }
        try {
            return safeRecursion(depth + 1);
        } catch (StackOverflowError e) {
            return depth;
        }
    }

    static void infiniteRecursion(int depth) {
        System.out.println("\r递归深度: " + depth);
        infiniteRecursion(depth + 1);
    }
}