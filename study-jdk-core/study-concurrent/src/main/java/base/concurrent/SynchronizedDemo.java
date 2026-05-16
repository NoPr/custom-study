package base.concurrent;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * synchronized 锁升级 + wait/notify 生产者消费者
 *
 * 核心知识点：
 * 1. 锁升级路径（JDK 15+ 默认开启偏向锁延迟）：
 *    无锁 → 偏向锁 → 轻量级锁（自旋） → 重量级锁（OS 互斥量）
 * 2. synchronized 四种用法：锁对象 / 锁方法 / 锁静态方法 / 锁 Class
 * 3. wait/notify 生产者消费者模型
 *
 * 锁升级触发条件：
 * - 偏向锁：同一线程反复获取锁，CAS 记录线程 ID
 * - 轻量级锁：有竞争但无阻塞，自旋等待（自适应自旋）
 * - 重量级锁：自旋失败或竞争激烈，膨胀为 OS mutex，线程阻塞
 *
 * 锁降级：无！只能升级不能降级。
 * 偏向锁可能被批量撤销（epoch）或批量重偏向。
 */
public class SynchronizedDemo {

    private static int sharedCounter = 0;
    private final Object lockA = new Object();
    private final Object lockB = new Object();

    public static void main(String[] args) throws InterruptedException {
        lockUpgradePath();
        synchronizedOnObject();
        synchronizedOnMethod();
        synchronizedOnStaticMethod();
        synchronizedOnClass();
        new SynchronizedDemo().deadlockDemo();
        producerConsumerDemo();
    }

    /**
     * synchronized 锁升级路径说明
     *
     * Mark Word 结构（64 位 JVM）：
     * 无锁态：     [ unused:25 | hash:31 | unused:1 | age:4 | biased_lock:0 | 01 ]
     * 偏向锁态：   [ thread:54 | epoch:2 | unused:1 | age:4 | biased_lock:1 | 01 ]
     * 轻量级锁态： [ ptr_to_lock_record:62 | 00 ]
     * 重量级锁态： [ ptr_to_monitor:62 | 10 ]
     * GC 标记：    [ forward_ptr:62 | 11 ]
     */
    static void lockUpgradePath() {
        System.out.println("=== synchronized 锁升级路径 ===");
        System.out.println("升级方向（不可逆）：");
        System.out.println("  无锁 → 偏向锁 → 轻量级锁 → 重量级锁");
        System.out.println();
        System.out.println("  无锁态 (001)：初始状态");
        System.out.println("  偏向锁 (101)：同一线程反复获取，CAS 记录线程 ID");
        System.out.println("  轻量级锁 (00)：有竞争，CAS 自旋获取");
        System.out.println("  重量级锁 (10)：自旋失败，膨胀为 ObjectMonitor，线程阻塞");
        System.out.println();
        System.out.println("JDK 15+ 默认延迟启动偏向锁 (BiasedLockingStartupDelay=4000ms)");
        System.out.println();
    }

    /**
     * synchronized 锁对象：synchronized (lock) { ... }
     * 不同锁对象互不影响，相同锁对象互斥
     */
    static void synchronizedOnObject() throws InterruptedException {
        System.out.println("=== synchronized 锁对象演示 ===");

        Object sameLock = new Object();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> {
            synchronized (sameLock) {
                System.out.println("  线程1 获取 sameLock，sleep 500ms");
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("  线程1 释放 sameLock");
            }
        }, "sync-obj-1");

        Thread t2 = new Thread(() -> {
            synchronized (sameLock) {
                System.out.println("  线程2 获取 sameLock（等待线程1释放后）");
            }
        }, "sync-obj-2");

        t1.start();
        TimeUnit.MILLISECONDS.sleep(50);
        t2.start();

        t1.join();
        t2.join();

        System.out.println("  耗时: " + (System.currentTimeMillis() - start) + "ms");
        System.out.println();
    }

    /**
     * synchronized 锁非静态方法：等价于 synchronized(this)
     */
    static void synchronizedOnMethod() throws InterruptedException {
        System.out.println("=== synchronized 锁方法（等价于 synchronized(this)）===");

        SyncMethodDemo demo = new SyncMethodDemo();

        Thread t1 = new Thread(() -> demo.method1(), "method-t1");
        Thread t2 = new Thread(() -> demo.method2(), "method-t2");

        t1.start();
        TimeUnit.MILLISECONDS.sleep(50);
        t2.start();

        t1.join();
        t2.join();

        System.out.println("  结论: method1 和 method2 都锁 this，互斥！");
        System.out.println();
    }

    /**
     * synchronized 锁静态方法：等价于 synchronized(SyncStaticDemo.class)
     * 锁的是 Class 对象，区别于锁实例对象
     */
    static void synchronizedOnStaticMethod() throws InterruptedException {
        System.out.println("=== synchronized 锁静态方法（等价于 synchronized(Class.class)）===");

        SyncStaticDemo obj1 = new SyncStaticDemo();
        SyncStaticDemo obj2 = new SyncStaticDemo();

        Thread t1 = new Thread(() -> SyncStaticDemo.staticMethod(), "static-t1");
        Thread t2 = new Thread(() -> SyncStaticDemo.staticMethod(), "static-t2");

        t1.start();
        TimeUnit.MILLISECONDS.sleep(50);
        t2.start();

        t1.join();
        t2.join();

        System.out.println("  结论: 静态方法锁 Class 对象，不同实例也互斥！");
        System.out.println();
    }

    /**
     * synchronized 锁 Class 对象
     * 一个类的所有对象共享同一个 Class 对象
     */
    static void synchronizedOnClass() throws InterruptedException {
        System.out.println("=== synchronized 锁 Class 对象 ===");

        class LockClassTask implements Runnable {
            @Override
            public void run() {
                synchronized (LockClassTask.class) {
                    System.out.println("  " + Thread.currentThread().getName()
                            + " 获取到 LockClassTask.class 锁");
                    try {
                        TimeUnit.MILLISECONDS.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        new Thread(new LockClassTask(), "class-lock-1").start();
        TimeUnit.MILLISECONDS.sleep(50);
        new Thread(new LockClassTask(), "class-lock-2").start();

        TimeUnit.MILLISECONDS.sleep(500);
        System.out.println();
    }

    /**
     * 死锁演示
     * 线程1 持有 lockA 等待 lockB
     * 线程2 持有 lockB 等待 lockA
     */
    void deadlockDemo() {
        System.out.println("=== 死锁演示（两线程互相等待对方释放锁）===");

        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                System.out.println("  线程1 持有 lockA");
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lockB) {
                    System.out.println("  线程1 持有 lockA + lockB");
                }
            }
        }, "deadlock-1");

        Thread t2 = new Thread(() -> {
            synchronized (lockB) {
                System.out.println("  线程2 持有 lockB");
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lockA) {
                    System.out.println("  线程2 持有 lockB + lockA");
                }
            }
        }, "deadlock-2");

        System.out.println("  场景: 线程1 持有 A 等 B，线程2 持有 B 等 A");
        System.out.println("  避免: 统一加锁顺序 / tryLock 超时 / 死锁检测");
        System.out.println("  注意: 下方代码会死锁，不实际运行。");
        System.out.println();
    }

    /**
     * wait/notify 生产者消费者模型
     *
     * 规则：
     * - wait/notify/notifyAll 必须在 synchronized 块内调用（持有对象锁）
     * - wait 释放锁让出 CPU，被 notify 后重新竞争锁
     * - notify 随机唤醒一个等待线程，notifyAll 唤醒所有
     * - 虚假唤醒（spurious wakeup）：wait 可能被无 notify 唤醒，必须 while 检查条件
     */
    static void producerConsumerDemo() throws InterruptedException {
        System.out.println("=== wait/notify 生产者消费者模型 ===");

        Buffer buffer = new Buffer(3);

        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                try {
                    buffer.produce("消息-" + i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "生产者");

        Thread consumer = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                try {
                    buffer.consume();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "消费者");

        producer.start();
        consumer.start();

        producer.join();
        consumer.join();

        System.out.println();
    }
}

class SyncMethodDemo {
    synchronized void method1() {
        System.out.println("  method1 获取锁，sleep 300ms");
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized void method2() {
        System.out.println("  method2 获取锁");
    }
}

class SyncStaticDemo {
    public synchronized static void staticMethod() {
        System.out.println("  " + Thread.currentThread().getName()
                + " 获取 Class 锁，sleep 300ms");
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

/**
 * 生产者消费者缓冲区（基于 wait/notify）
 */
class Buffer {
    private final Queue<String> queue = new LinkedList<>();
    private final int capacity;

    Buffer(int capacity) {
        this.capacity = capacity;
    }

    void produce(String message) throws InterruptedException {
        synchronized (this) {
            while (queue.size() == capacity) {
                System.out.println("  [生产者] 队列满，wait...");
                wait();
            }
            queue.offer(message);
            System.out.println("  [生产者] 生产: " + message + "，队列大小: " + queue.size());
            notifyAll();
        }
    }

    void consume() throws InterruptedException {
        synchronized (this) {
            while (queue.isEmpty()) {
                System.out.println("  [消费者] 队列空，wait...");
                wait();
            }
            String message = queue.poll();
            System.out.println("  [消费者] 消费: " + message + "，队列大小: " + queue.size());
            notifyAll();
        }
    }
}