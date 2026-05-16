package base.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * volatile 两大特性 + 非原子性演示
 *
 * 核心知识点：
 * 1. 可见性：写 volatile 变量立即刷新到主内存，读从主内存读
 * 2. 禁止指令重排序：DCL 单例中 volatile 防止半初始化对象逸出
 * 3. 不保证原子性：count++ 多线程结果 < 期望值（复合操作非原子）
 *
 * JMM 内存模型（JSR-133）：
 * - 每个线程有自己的工作内存（CPU 缓存/寄存器）
 * - 共享变量在主内存中
 * - volatile 写：工作内存 → 主内存（Store-Load 屏障）
 * - volatile 读：主内存 → 工作内存（Load-Load 屏障）
 */
public class VolatileDemo {

    public static void main(String[] args) throws InterruptedException {
        visibilityWithoutVolatile();
        visibilityWithVolatile();
        nonAtomicIncrement();
        dclWithoutVolatileProblem();
    }

    /**
     * 无 volatile 可见性不可见问题：
     * reader 线程将 flag 缓存到 CPU 寄存器/缓存行，
     * writer 线程修改 flag 后，reader 看不到，死循环。
     */
    static volatile boolean running = false;

    static void visibilityWithVolatile() throws InterruptedException {
        System.out.println("=== volatile 可见性 — 保证线程间可见 ===");

        running = true;
        CountDownLatch readerStarted = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            while (running) {
            }
            System.out.println("  reader 退出循环 (看到了 writer 的修改)");
        }, "volatile-reader");

        reader.start();
        readerStarted.await();

        TimeUnit.MILLISECONDS.sleep(100);

        new Thread(() -> {
            running = false;
            System.out.println("  writer 已设置 running = false");
        }, "volatile-writer").start();

        reader.join(3000);
        if (reader.isAlive()) {
            System.out.println("  异常：reader 未退出（理论上 volatile 不会发生）");
        }
        System.out.println();
    }

    /**
     * 无 volatile 可见性不可见演示：
     * 普通变量可能被 JIT 优化提升到寄存器或优化为死循环，
     * writer 线程修改后 reader 看不到。
     */
    static boolean runningNoVolatile = true;

    static void visibilityWithoutVolatile() throws InterruptedException {
        System.out.println("=== 无 volatile — 可见性问题演示 ===");

        runningNoVolatile = true;
        CountDownLatch readerStarted = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            while (runningNoVolatile) {
                int x = 0;
            }
            System.out.println("  reader 退出循环");
        }, "no-volatile-reader");

        reader.start();
        readerStarted.await();

        TimeUnit.MILLISECONDS.sleep(100);

        new Thread(() -> {
            runningNoVolatile = false;
            System.out.println("  writer 已设置 runningNoVolatile = false");
        }, "no-volatile-writer").start();

        reader.join(3000);
        if (reader.isAlive()) {
            System.out.println("  reader 仍然死循环 — 没有 volatile，可见性不保证！");
            reader.interrupt();
        } else {
            System.out.println("  本次可见性生效（JIT 未优化，但不保证总是生效）");
        }
        System.out.println();
    }

    /**
     * volatile 不保证原子性演示：
     * count++ 是三个操作：读 → 加 1 → 写
     * volatile 只保证每次读/写的可见性，不保证复合操作的原子性
     */
    static volatile int count = 0;

    static void nonAtomicIncrement() throws InterruptedException {
        System.out.println("=== volatile 不保证原子性 — count++ 演示 ===");

        int threadCount = 10;
        int perThread = 1000;
        count = 0;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    count++;
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        int expected = threadCount * perThread;
        System.out.println("  线程数: " + threadCount + ", 每线程累加: " + perThread);
        System.out.println("  期望值: " + expected);
        System.out.println("  实际值: " + count);
        System.out.println("  丢失次数: " + (expected - count));
        System.out.println("  结论: volatile count++ 非原子，多线程结果偏小！");
        System.out.println("  解决: 使用 AtomicInteger / synchronized / Lock");
        System.out.println();
    }

    /**
     * DCL（Double-Checked Locking）单例中 volatile 的作用
     *
     * new Singleton() 三步（非原子）：
     * ① memory = allocate()    — 分配内存空间
     * ② ctorInstance(memory)   — 执行构造方法，初始化对象
     * ③ instance = memory      — 将 reference 指向内存地址
     *
     * JIT 可能将 ②③ 重排序为 ①③②：
     * ① allocate → ③ 指向地址 → ② 初始化
     * 线程 A 执行到 ③ 时，线程 B 看到 instance != null → 拿到未初始化对象
     *
     * volatile 禁止此重排序：② 必须在 ③ 之前完成（StoreStore 屏障）
     */
    static void dclWithoutVolatileProblem() {
        System.out.println("=== DCL 单例中 volatile 的作用 ===");
        System.out.println("new Singleton() 三步：");
        System.out.println("  ① memory = allocate()     — 分配内存");
        System.out.println("  ② ctorInstance(memory)    — 初始化对象");
        System.out.println("  ③ instance = memory       — 引用指向内存");
        System.out.println();
        System.out.println("JIT 可能重排序为 ①③②：");
        System.out.println("  在 ③ 之后、② 之前，其他线程拿到未初始化对象 → BUG！");
        System.out.println();
        System.out.println("volatile 通过内存屏障解决：");
        System.out.println("  StoreStore 屏障：② 构造完成 → barrier → ③ 赋值");
        System.out.println("  保证 instance 引用存在时，对象一定已构造完成");
        System.out.println();

        DCLSingleton instance1 = DCLSingleton.getInstance();
        DCLSingleton instance2 = DCLSingleton.getInstance();
        System.out.println("  验证 DCL 单例: instance1 == instance2 → " + (instance1 == instance2));
        System.out.println();
    }
}

/**
 * 正确的 DCL volatile 单例
 */
class DCLSingleton {
    private static volatile DCLSingleton instance;

    private DCLSingleton() {}

    public static DCLSingleton getInstance() {
        if (instance == null) {
            synchronized (DCLSingleton.class) {
                if (instance == null) {
                    instance = new DCLSingleton();
                }
            }
        }
        return instance;
    }
}