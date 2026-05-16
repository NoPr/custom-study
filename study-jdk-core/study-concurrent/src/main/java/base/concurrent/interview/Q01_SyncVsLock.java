package base.concurrent.interview;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 面试题：synchronized vs Lock（ReentrantLock）区别
 *
 * 高频考点：
 * 1. 层面：synchronized 是 JVM 关键字，Lock 是 JUC 接口
 * 2. 锁释放：synchronized 自动释放（代码块结束/异常），Lock 必须 finally unlock
 * 3. 灵活性：Lock 支持 tryLock 超时、lockInterruptibly 可中断、多条件 Condition
 * 4. 公平性：synchronized 非公平，Lock 可选公平/非公平
 * 5. 性能：JDK 6+ synchronized 锁升级后性能接近，低竞争场景 synchronized 更简洁
 * 6. 实现：synchronized → ObjectMonitor(C++)，Lock → AQS(Java) + CAS
 */
public class Q01_SyncVsLock {

    public static void main(String[] args) throws InterruptedException {
        featureComparison();
        autoReleaseDemo();
        tryLockTimeoutDemo();
        multiConditionDemo();
        performanceNote();
    }

    static void featureComparison() {
        System.out.println("=== synchronized vs Lock 特性对比 ===");
        System.out.println("┌──────────────────┬────────────────────┬────────────────────┐");
        System.out.println("│ 特性             │ synchronized       │ Lock               │");
        System.out.println("├──────────────────┼────────────────────┼────────────────────┤");
        System.out.println("│ 层面             │ JVM 关键字（C++）  │ JUC 接口（Java）    │");
        System.out.println("│ 锁释放           │ 自动（块结束/异常）│ 手动 finally unlock │");
        System.out.println("│ 中断响应         │ 不支持             │ lockInterruptibly   │");
        System.out.println("│ 超时获取         │ 不支持             │ tryLock(time, unit) │");
        System.out.println("│ 公平锁           │ 非公平             │ 公平/非公平可选      │");
        System.out.println("│ 多条件等待       │ 一个条件（wait）   │ 多个 Condition      │");
        System.out.println("│ 底层实现         │ ObjectMonitor      │ AQS + CAS + CLH    │");
        System.out.println("│ 性能（JDK 6+）   │ 接近（锁升级优化） │ 略高开销（但灵活）  │");
        System.out.println("└──────────────────┴────────────────────┴────────────────────┘");
        System.out.println();
    }

    /**
     * synchronized 自动释放锁
     */
    static void autoReleaseDemo() {
        System.out.println("=== synchronized 自动释放锁 ===");

        Object lock = new Object();

        try {
            synchronized (lock) {
                System.out.println("  synchronized 块内，持有锁");
                throw new RuntimeException("模拟异常");
            }
        } catch (RuntimeException e) {
            System.out.println("  捕获异常: " + e.getMessage());
            System.out.println("  锁已自动释放！");
        }
        System.out.println();
    }

    /**
     * tryLock 超时获取 — synchronized 做不到
     */
    static void tryLockTimeoutDemo() throws InterruptedException {
        System.out.println("=== tryLock 超时获取（synchronized 做不到）===");

        Lock lock = new ReentrantLock();

        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("  线程1 持有锁，sleep 1s...");
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
                System.out.println("  线程1 释放锁");
            }
        }, "holder");

        holder.start();
        TimeUnit.MILLISECONDS.sleep(100);

        boolean acquired = lock.tryLock(500, TimeUnit.MILLISECONDS);
        if (acquired) {
            System.out.println("  线程2 tryLock 成功！");
            lock.unlock();
        } else {
            System.out.println("  线程2 tryLock 超时，放弃等锁");
        }

        holder.join();
        System.out.println("  结论: tryLock 可设超时，避免无限等待");
        System.out.println();
    }

    /**
     * 多条件等待 — Lock 的 Condition 允许多个等待队列
     * synchronized 只有一个 wait set
     */
    static void multiConditionDemo() throws InterruptedException {
        System.out.println("=== Condition 多条件等待（synchronized 只有一个 wait set）===");

        ReentrantLock lock = new ReentrantLock();
        var notEmpty = lock.newCondition();
        var notFull = lock.newCondition();
        int[] buffer = {0};
        int[] count = {0};

        Thread producer = new Thread(() -> {
            lock.lock();
            try {
                while (count[0] >= 1) {
                    System.out.println("  [生产者] 缓冲区满，等待 notFull...");
                    notFull.await();
                }
                buffer[0] = 42;
                count[0]++;
                System.out.println("  [生产者] 生产: " + buffer[0]);
                notEmpty.signal();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            lock.lock();
            try {
                while (count[0] == 0) {
                    System.out.println("  [消费者] 缓冲区空，等待 notEmpty...");
                    notEmpty.await();
                }
                int value = buffer[0];
                count[0]--;
                System.out.println("  [消费者] 消费: " + value);
                notFull.signal();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "consumer");

        consumer.start();
        TimeUnit.MILLISECONDS.sleep(100);
        producer.start();

        producer.join();
        consumer.join();

        System.out.println("  结论: Condition 支持多个条件队列，实现精确唤醒");
        System.out.println();
    }

    static void performanceNote() {
        System.out.println("=== 性能 vs 选择建议 ===");
        System.out.println("JDK 6 以后 synchronized 引入了偏向锁/轻量级锁/自旋锁等优化。");
        System.out.println("低竞争场景两者性能接近，高竞争也都退化为重量级锁。");
        System.out.println();
        System.out.println("选择建议：");
        System.out.println("  用 synchronized — 简单场景，不需要超时/中断/多条件");
        System.out.println("  用 Lock        — 需要 tryLock 超时、lockInterruptibly、多 Condition");
        System.out.println();
    }
}