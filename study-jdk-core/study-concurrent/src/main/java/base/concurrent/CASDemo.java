package base.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * CAS（Compare And Swap）原理 + ABA 问题演示
 *
 * 核心知识点：
 * 1. CAS 三操作数：内存值 V、期望值 A、新值 B
 *    若 V == A 则将 V 更新为 B，否则不更新
 * 2. CAS 底层依赖 CPU 指令（x86: cmpxchg，ARM: LDREX/STREX）
 * 3. Java 中通过 Unsafe.compareAndSwapInt 实现
 * 4. ABA 问题：值从 A→B→A，CAS 误认为没有变化
 *
 * CAS 是 JUC 包的基石：
 * AtomicInteger/AtomicReference → CAS
 * AQS（ReentrantLock/Semaphore）→ CAS 修改 state
 * ConcurrentHashMap → CAS 无锁化
 */
public class CASDemo {

    static volatile int unsafeCount = 0;
    static AtomicInteger atomicCount = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        unsafeIncrementDemo();
        atomicIncrementDemo();
        casSpinDemo();
        abaProblemDemo();
        abaSolutionDemo();
    }

    /**
     * 无锁变量 count++ 多线程不安全演示
     */
    static void unsafeIncrementDemo() throws InterruptedException {
        System.out.println("=== 无锁 count++ 多线程不安全 ===");

        int threadCount = 10;
        int perThread = 1000;
        unsafeCount = 0;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    unsafeCount++;
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        int expected = threadCount * perThread;
        System.out.println("  期望值: " + expected);
        System.out.println("  实际值: " + unsafeCount);
        System.out.println("  结论: 普通变量 count++ 非原子操作，结果偏小！");
        System.out.println();
    }

    /**
     * AtomicInteger 基于 CAS 实现安全累加
     *
     * 源码原理：
     * public final int incrementAndGet() {
     *     return U.getAndAddInt(this, VALUE, 1) + 1;
     * }
     * // Unsafe.getAndAddInt:
     * do {
     *     v = getIntVolatile(o, offset);  // 读取当前值
     * } while (!compareAndSetInt(o, offset, v, v + delta)); // CAS 循环
     * return v;
     */
    static void atomicIncrementDemo() throws InterruptedException {
        System.out.println("=== AtomicInteger CAS 安全累加 ===");

        int threadCount = 10;
        int perThread = 1000;
        atomicCount.set(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    atomicCount.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        int expected = threadCount * perThread;
        System.out.println("  期望值: " + expected);
        System.out.println("  实际值: " + atomicCount.get());
        System.out.println("  结论: AtomicInteger CAS 保证原子性，结果精确！");
        System.out.println();
    }

    /**
     * 手写 CAS 自旋锁演示
     * 自旋三部曲：读 → 比较并交换 → 失败则重试
     */
    static void casSpinDemo() {
        System.out.println("=== 手写 CAS 自旋演示 ===");

        AtomicInteger casValue = new AtomicInteger(0);

        int expectedValue = casValue.get();
        int newValue = expectedValue + 10;

        System.out.println("  当前值: " + expectedValue);
        System.out.println("  尝试 CAS: " + expectedValue + " → " + newValue);

        boolean success = casValue.compareAndSet(expectedValue, newValue);
        System.out.println("  CAS 结果: " + (success ? "成功" : "失败"));
        System.out.println("  新值: " + casValue.get());

        System.out.println("  模拟 CAS 自旋（失败 → 重试 → 成功）：");
        atomicCount.set(20);

        int attempt = 0;
        int targetValue;
        do {
            attempt++;
            targetValue = atomicCount.get();
            int updatedValue = targetValue + 5;
            System.out.println("    第 " + attempt + " 次尝试: 期望=" + targetValue
                    + " → 新值=" + updatedValue);
        } while (!atomicCount.compareAndSet(targetValue, targetValue + 5));

        System.out.println("  自旋成功！最终值: " + atomicCount.get() + "，尝试次数: " + attempt);
        System.out.println();
    }

    /**
     * ABA 问题演示：
     * 线程 A 读到值 A，准备 CAS
     * 线程 B 将值 A→B→A
     * 线程 A 执行 CAS：发现 V==A，以为没变 → CAS 成功！
     * 但中间已经被修改过两次，可能产生逻辑错误
     *
     * 经典场景：链表栈出栈
     * head → A → B → C
     * 线程 T1 准备将 head 从 A CAS 到 B（pop）
     * 线程 T2 先 pop A、pop B、再 push A
     * T1 执行 CAS 时 head 仍是 A，CAS 成功 → 但 B 已被释放/重用 → 悬垂指针！
     */
    static void abaProblemDemo() throws InterruptedException {
        System.out.println("=== ABA 问题演示 ===");

        AtomicInteger value = new AtomicInteger(100);
        System.out.println("  初始值: " + value.get());

        Thread threadA = new Thread(() -> {
            int expected = 100;
            System.out.println("  [线程A] 读到值: " + expected + "，准备 CAS 100 → 200");

            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            boolean result = value.compareAndSet(expected, 200);
            System.out.println("  [线程A] CAS(100 → 200) 结果: " + result
                    + "，当前值: " + value.get());
        }, "ABA-Thread-A");

        Thread threadB = new Thread(() -> {
            value.compareAndSet(100, 300);
            System.out.println("  [线程B] CAS(100 → 300)，当前值: " + value.get());

            value.compareAndSet(300, 100);
            System.out.println("  [线程B] CAS(300 → 100)，当前值: " + value.get());
            System.out.println("  [线程B] ABA 完成: 100 → 300 → 100");
        }, "ABA-Thread-B");

        threadA.start();
        TimeUnit.MILLISECONDS.sleep(50);
        threadB.start();

        threadA.join();
        threadB.join();

        System.out.println("  最终值: " + value.get());
        System.out.println("  问题: 线程A 的 CAS 成功，但它不知道值曾变为 300！");
        System.out.println();
    }

    /**
     * ABA 问题解决：AtomicStampedReference
     * 使用版本号（stamp）跟踪每次修改
     * CAS 时同时比较引用和版本号
     */
    static void abaSolutionDemo() throws InterruptedException {
        System.out.println("=== ABA 问题解决 — AtomicStampedReference ===");

        AtomicStampedReference<Integer> stampedRef =
                new AtomicStampedReference<>(100, 0);
        int[] stampHolder = new int[1];

        int initialValue = stampedRef.get(stampHolder);
        int initialStamp = stampHolder[0];
        System.out.println("  初始值: " + initialValue + ", 版本号: " + initialStamp);

        Thread threadA = new Thread(() -> {
            int expectedRef = 100;
            int expectedStamp = 0;
            System.out.println("  [线程A] 读到 (值=" + expectedRef
                    + ", 版本=" + expectedStamp + ")，准备 CAS → 200");

            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            boolean result = stampedRef.compareAndSet(
                    expectedRef, 200, expectedStamp, expectedStamp + 1);
            System.out.println("  [线程A] CAS 结果: " + result
                    + "（期望版本 " + expectedStamp + " ≠ 当前版本 " + stampedRef.getStamp() + "）");
            System.out.println("  [线程A] 当前值: " + stampedRef.getReference()
                    + ", 版本号: " + stampedRef.getStamp());
        }, "ABA-Solution-Thread-A");

        Thread threadB = new Thread(() -> {
            int[] stamp = new int[1];
            int currentVal = stampedRef.get(stamp);
            int currentStamp = stamp[0];
            stampedRef.compareAndSet(currentVal, 300, currentStamp, currentStamp + 1);
            System.out.println("  [线程B] CAS(100 → 300)，版本: "
                    + currentStamp + " → " + (currentStamp + 1));

            currentVal = stampedRef.get(stamp);
            currentStamp = stamp[0];
            stampedRef.compareAndSet(currentVal, 100, currentStamp, currentStamp + 1);
            System.out.println("  [线程B] CAS(300 → 100)，版本: "
                    + currentStamp + " → " + (currentStamp + 1));
            System.out.println("  [线程B] ABA 完成: (100,0) → (300,1) → (100,2)");
        }, "ABA-Solution-Thread-B");

        threadA.start();
        TimeUnit.MILLISECONDS.sleep(50);
        threadB.start();

        threadA.join();
        threadB.join();

        System.out.println("  最终: (值=" + stampedRef.getReference()
                + ", 版本=" + stampedRef.getStamp() + ")");
        System.out.println("  结论: AtomicStampedReference 通过版本号检测到 ABA！");
        System.out.println();
    }
}