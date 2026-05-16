package base.design_patterns.interview;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面试题：DCL 单例为什么必须加 volatile？
 *
 * 结论：
 * DCL（Double-Checked Locking）中 volatile 有两个作用：
 * 1. 禁止指令重排序 — instance = new Singleton() 非原子操作，可能重排序
 * 2. 保证可见性 — 一个线程创建实例后，其他线程立即可见
 *
 * 如果没有 volatile：
 * 线程 A 执行到 ②③ 重排序，先将引用指向未初始化的内存，
 * 线程 B 看到 instance != null 直接返回，拿到了一个未初始化完成的对象。
 */
public class Q01_Singleton_DCL {

    public static void main(String[] args) throws InterruptedException {
        explainNewObjectSteps();
        explainVolatileRule();
        demonstrateReorderingProblem();
    }

    /**
     * new Singleton() 的三步操作（JVM 层面）：
     *
     * memory = allocate();    // ① 分配内存空间
     * ctorInstance(memory);   // ② 执行构造方法，初始化对象
     * instance = memory;      // ③ 将引用指向内存地址
     *
     * JIT 编译器可能将 ②③ 重排序为 ①③②：
     * memory = allocate();    // ①
     * instance = memory;      // ③ 先指向地址（此时对象未初始化！）
     * ctorInstance(memory);   // ② 再初始化
     *
     * 如果在 ③ 之后、② 之前有其他线程调用 getInstance()：
     * - 外层 if (instance == null) 为 false → 直接返回
     * - 但 instance 指向的内存中对象还未初始化完成 → 错误！
     *
     * volatile 禁止这种重排序（内存屏障），保证初始化完成后才对外可见。
     */
    static void explainNewObjectSteps() {
        System.out.println("=== new 对象的三步操作 ===");
        System.out.println("① memory = allocate()        // 分配内存");
        System.out.println("② ctorInstance(memory)       // 构造对象");
        System.out.println("③ instance = memory          // 指向内存");
        System.out.println();
        System.out.println("JIT 可能重排序：① → ③ → ②");
        System.out.println("在 ③ 之后 ② 之前，其他线程可能拿到未初始化对象");
        System.out.println("volatile 通过内存屏障禁止此重排序");
        System.out.println();
    }

    /**
     * volatile 的两个核心语义：
     *
     * 1. 可见性：
     *    写 volatile 变量时，JMM 会把该线程本地内存中的共享变量刷新到主内存。
     *    读 volatile 变量时，JMM 会把该线程本地内存置为无效，从主内存中重新读取。
     *
     * 2. 禁止指令重排序：
     *    volatile 写之前的所有操作不允许重排序到 volatile 写之后。（StoreStore 屏障）
     *    volatile 读之后的所有操作不允许重排序到 volatile 读之前。（LoadLoad 屏障）
     *
     * DCL 中 volatile 的关键作用：
     * - volatile 写指令 StoreStore 屏障：保证 instance 赋值前，构造方法已经执行完毕
     *   即 ② ctorInstance 必须在 ③ instance = memory 之前完成
     * - volatile 读指令 LoadLoad 屏障：保证读取 instance 后，后续读取操作不被重排到前面
     */
    static void explainVolatileRule() {
        System.out.println("=== volatile 的两个核心语义 ===");
        System.out.println("1. 可见性（visibility）");
        System.out.println("   - 写 volatile → 立即刷新到主内存");
        System.out.println("   - 读 volatile → 从主内存重新读取");
        System.out.println("2. 禁止指令重排序（ordering）");
        System.out.println("   - StoreStore 屏障：写 volatile 前的操作不能排到后面");
        System.out.println("   - StoreLoad 屏障：写 volatile 后的读取不能排到前面");
        System.out.println();
        System.out.println("DCL 中 volatile 保证：");
        System.out.println("  ② 构造对象 → StoreStore 屏障 → ③ 引用赋值");
        System.out.println("  确保 instance 引用存在时，对象一定已构造完成");
        System.out.println();
    }

    /**
     * 模拟：如果 DCL 没有 volatile，可能的重排序问题
     *
     * 使用一个简化的模拟来展示问题场景。
     * 注意：实际 JVM 的重排序不是 100% 触发，这是概率性 bug。
     */
    static void demonstrateReorderingProblem() throws InterruptedException {
        System.out.println("=== 模拟 DCL 无 volatile 的重排序问题 ===");
        System.out.println("说明：JIT 的重排序是概率性的，不是每次都能复现。");
        System.out.println("以下代码用少量线程演示，但实际故障需要 10000+ 次并发。");

        int threadCount = 1000;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger uninitializedReads = new AtomicInteger(0);

        // 没有 volatile 的坏味 DCL
        NoVolatileDCL.reset();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                Object obj = NoVolatileDCL.getInstance();
                if (obj == null) {
                    uninitializedReads.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        System.out.println("  线程数: " + threadCount);
        System.out.println("  读到 null 的次数: " + uninitializedReads.get());
        if (uninitializedReads.get() > 0) {
            System.out.println("  结论: 存在重排序问题，线程读到了未初始化的对象！");
        } else {
            System.out.println("  本次未复现（重排序是概率性的，实际项目中偶发），但原理不变。");
        }
        System.out.println();
    }
}

/**
 * 没有 volatile 修饰的 DCL 单例
 * instance 变量不添加 volatile，JIT 编译器可能重排序
 */
class NoVolatileDCL {
    private static NoVolatileDCL instance;

    private NoVolatileDCL() {}

    static void reset() {
        instance = null;
    }

    static NoVolatileDCL getInstance() {
        if (instance == null) {
            synchronized (NoVolatileDCL.class) {
                if (instance == null) {
                    instance = new NoVolatileDCL();
                }
            }
        }
        return instance;
    }
}