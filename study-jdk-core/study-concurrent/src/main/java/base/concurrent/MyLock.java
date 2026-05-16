package base.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * 基于 AQS 手写 ReentrantLock
 *
 * 核心实现：
 * 1. 继承 AbstractQueuedSynchronizer
 * 2. 实现 tryAcquire / tryRelease
 * 3. 通过内部 Sync 类桥接，暴露 lock/unlock 接口
 *
 * AQS 使用的模板方法模式：
 * acquire(arg) → 内部调用 tryAcquire(arg)（子类实现）
 * release(arg) → 内部调用 tryRelease(arg)（子类实现）
 *
 * 本实现包含：
 * - 非公平锁：lock 时先 CAS 抢一次锁
 * - 可重入：同一线程 +1 state，释放时 -1
 * - 可中断获取锁：lockInterruptibly
 */
public class MyLock implements Lock {

    private final Sync sync = new Sync();

    /**
     * 自定义 AQS 同步器：非公平锁 + 可重入
     */
    private static class Sync extends AbstractQueuedSynchronizer {

        @Override
        protected boolean tryAcquire(int arg) {
            Thread current = Thread.currentThread();
            int state = getState();

            if (state == 0) {
                if (compareAndSetState(0, arg)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            } else if (current == getExclusiveOwnerThread()) {
                int nextState = state + arg;
                if (nextState < 0) {
                    throw new Error("Maximum lock count exceeded");
                }
                setState(nextState);
                return true;
            }
            return false;
        }

        @Override
        protected boolean tryRelease(int arg) {
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                throw new IllegalMonitorStateException();
            }
            int state = getState() - arg;
            boolean free = false;
            if (state == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(state);
            return free;
        }

        @Override
        protected boolean isHeldExclusively() {
            return getState() > 0;
        }

        Condition newCondition() {
            return new ConditionObject();
        }
    }

    @Override
    public void lock() {
        sync.acquire(1);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
        return sync.tryAcquire(1);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(time));
    }

    @Override
    public void unlock() {
        sync.release(1);
    }

    @Override
    public Condition newCondition() {
        return sync.newCondition();
    }

    /**
     * 演示：手写 MyLock 的基本使用
     */
    public static void main(String[] args) throws InterruptedException {
        basicLockDemo();
        reentrantDemo();
        multiThreadDemo();
    }

    static void basicLockDemo() {
        System.out.println("=== MyLock 基本使用 ===");

        MyLock lock = new MyLock();
        int[] counter = {0};

        lock.lock();
        try {
            counter[0]++;
            System.out.println("  counter = " + counter[0]);
        } finally {
            lock.unlock();
        }
        System.out.println();
    }

    static void reentrantDemo() {
        System.out.println("=== MyLock 可重入演示 ===");

        MyLock lock = new MyLock();

        lock.lock();
        System.out.println("  第 1 次获取锁");

        lock.lock();
        System.out.println("  第 2 次获取锁（重入）");

        lock.lock();
        System.out.println("  第 3 次获取锁（重入）");

        lock.unlock();
        System.out.println("  unlock ×1");

        lock.unlock();
        System.out.println("  unlock ×2");

        lock.unlock();
        System.out.println("  unlock ×3 — 锁完全释放");

        System.out.println();
    }

    static void multiThreadDemo() throws InterruptedException {
        System.out.println("=== MyLock 多线程安全演示 ===");

        MyLock lock = new MyLock();
        int[] sharedCounter = {0};
        int threadCount = 10;
        int perThread = 1000;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    lock.lock();
                    try {
                        sharedCounter[0]++;
                    } finally {
                        lock.unlock();
                    }
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        int expected = threadCount * perThread;
        System.out.println("  期望值: " + expected);
        System.out.println("  实际值: " + sharedCounter[0]);
        System.out.println("  结果: " + (expected == sharedCounter[0] ? "正确" : "错误"));
        System.out.println();
    }
}