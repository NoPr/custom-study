package base.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AQS（AbstractQueuedSynchronizer）原理 + 公平锁 vs 非公平锁演示
 *
 * 核心知识点：
 * AQS 三要素：
 * 1. state 变量（volatile int）— 同步状态，CAS 修改
 * 2. CLH 变体队列（双向链表）— 存储阻塞线程
 * 3. 模板方法模式 — tryAcquire/tryRelease 由子类实现
 *
 * AQS 内部结构：
 * - head 指向队头（获取到锁的线程对应的节点）
 * - tail 指向队尾（最新入队的线程对应的节点）
 * - Node：prev/next/thread/waitStatus(0/CANCELLED/SIGNAL/CONDITION/PROPAGATE)
 *
 * 独占模式流程（ReentrantLock.lock()）：
 * ① tryAcquire(arg) 尝试获取锁（CAS state 0→1）
 *    获取成功 → 设置 exclusiveOwnerThread = 当前线程 → 返回
 * ② 获取失败 → addWaiter(Node.EXCLUSIVE) 创建节点入队
 * ③ acquireQueued() 自旋：前驱是 head 则 tryAcquire，否则 park
 * ④ 前驱释放锁后 unparkSuccessor 唤醒后继节点
 */
public class AQSDemo {

    public static void main(String[] args) throws InterruptedException {
        aqsArchitectureExplain();
        fairLockDemo();
        unfairLockDemo();
        reentrantDemo();
        lockInterruptiblyDemo();
    }

    static void aqsArchitectureExplain() {
        System.out.println("=== AQS 核心架构 ===");
        System.out.println("三要素：");
        System.out.println("  1. volatile int state — 锁状态（0 未锁，1 已锁，>1 重入）");
        System.out.println("  2. CLH 变体队列 — Node{prev, next, thread, waitStatus} 双向链表");
        System.out.println("  3. CAS — 修改 state，入队出队操作");
        System.out.println();
        System.out.println("独占模式 acquire 流程：");
        System.out.println("  tryAcquire(arg) → 成功则返回");
        System.out.println("  → addWaiter(EXCLUSIVE) 入队");
        System.out.println("  → acquireQueued() 自旋等待前驱唤醒");
        System.out.println();
        System.out.println("独占模式 release 流程：");
        System.out.println("  tryRelease(arg) → 成功且 head.waitStatus≠0");
        System.out.println("  → unparkSuccessor(head) 唤醒后继节点");
        System.out.println();
    }

    /**
     * 公平锁：新线程先检查队列中是否有等待线程，有则乖乖排队
     *
     * 公平锁 tryAcquire 源码逻辑：
     * if (state == 0) {
     *     if (!hasQueuedPredecessors() && compareAndSetState(0, arg)) {
     *         setExclusiveOwnerThread(current);
     *         return true;
     *     }
     * }
     * hasQueuedPredecessors() — 队列中有等待线程 → 不插队
     */
    static void fairLockDemo() throws InterruptedException {
        System.out.println("=== 公平锁（Fair Lock）— 先来后到 ===");

        ReentrantLock fairLock = new ReentrantLock(true);
        int threadCount = 3;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 1; i <= threadCount; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    fairLock.lock();
                    try {
                        System.out.println("  线程-" + id + " 获取锁");
                        TimeUnit.MILLISECONDS.sleep(100);
                    } finally {
                        fairLock.unlock();
                    }
                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "fair-" + i).start();
        }

        TimeUnit.MILLISECONDS.sleep(50);
        startLatch.countDown();
        doneLatch.await();

        System.out.println("  结论: 公平锁按 FIFO 顺序获取锁");
        System.out.println();
    }

    /**
     * 非公平锁（默认）：新线程直接尝试 CAS 抢锁，不管队列中有没有等待线程
     *
     * 非公平锁 lock() 源码逻辑：
     * if (compareAndSetState(0, 1)) {
     *     setExclusiveOwnerThread(current);
     *     return;
     * }
     * acquire(1);  // CAS 失败再走 AQS 队列
     *
     * 非公平的体现在于：
     * - 调用 lock() 时直接 CAS 抢锁（先插队一次）
     * - tryAcquire 中调用 nonfairTryAcquire，不检查 hasQueuedPredecessors
     */
    static void unfairLockDemo() throws InterruptedException {
        System.out.println("=== 非公平锁（Nonfair Lock，默认）— 可以插队 ===");

        ReentrantLock unfairLock = new ReentrantLock(false);
        int threadCount = 3;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 1; i <= threadCount; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    unfairLock.lock();
                    try {
                        System.out.println("  线程-" + id + " 获取锁");
                        TimeUnit.MILLISECONDS.sleep(100);
                    } finally {
                        unfairLock.unlock();
                    }
                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "unfair-" + i).start();
        }

        TimeUnit.MILLISECONDS.sleep(50);
        startLatch.countDown();
        doneLatch.await();

        System.out.println("  结论: 非公平锁可能不按 FIFO 顺序获取（插队）");
        System.out.println();
    }

    /**
     * 可重入性演示：同一线程可多次获取同一把锁
     * state 从 0→1→2→...→n，每次 unlock state-1，state=0 时完全释放
     */
    static void reentrantDemo() {
        System.out.println("=== ReentrantLock 可重入性演示 ===");

        ReentrantLock lock = new ReentrantLock();

        lock.lock();
        System.out.println("  第 1 次获取锁，holdCount=" + lock.getHoldCount());

        lock.lock();
        System.out.println("  第 2 次获取锁，holdCount=" + lock.getHoldCount());

        lock.lock();
        System.out.println("  第 3 次获取锁，holdCount=" + lock.getHoldCount());

        lock.unlock();
        System.out.println("  unlock ×1，holdCount=" + lock.getHoldCount());

        lock.unlock();
        System.out.println("  unlock ×2，holdCount=" + lock.getHoldCount());

        lock.unlock();
        System.out.println("  unlock ×3，holdCount=" + lock.getHoldCount());

        System.out.println("  结论: 同一线程可重复加锁，需同样次数 unlock 才释放");
        System.out.println();
    }

    /**
     * lockInterruptibly：等待锁时响应中断
     */
    static void lockInterruptiblyDemo() throws InterruptedException {
        System.out.println("=== lockInterruptibly — 等待锁时可响应中断 ===");

        ReentrantLock lock = new ReentrantLock();
        lock.lock();

        Thread blocked = new Thread(() -> {
            try {
                System.out.println("  阻塞线程 尝试 lockInterruptibly()...");
                lock.lockInterruptibly();
                System.out.println("  阻塞线程 获取到锁");
            } catch (InterruptedException e) {
                System.out.println("  阻塞线程 被中断，退出等锁！");
            }
        }, "blocked-thread");

        blocked.start();
        TimeUnit.MILLISECONDS.sleep(200);

        blocked.interrupt();
        System.out.println("  主线程 调用了 interrupt()");

        blocked.join(1000);
        lock.unlock();

        System.out.println("  结论: lockInterruptibly 允许在等锁时响应中断");
        System.out.println();
    }
}