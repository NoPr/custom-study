package base.collection.interview;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面试题：HashMap 多线程扩容死循环 / 数据丢失
 *
 * 结论：
 * JDK 7 HashMap 多线程 put 触发扩容时，因头插法 + resize 并发重组链表，
 * 可能形成循环链表，CPU 100%。
 * JDK 8 改为尾插法，不会死循环，但仍可能数据丢失。
 * 多线程场景必须用 ConcurrentHashMap。
 */
public class Q01_HashMapDeadLoop {

    public static void main(String[] args) throws InterruptedException {
        jdk7HeadInsertExplain();
        jdk8TailInsertExplain();
        concurrentPutDataLossDemo();
        concurrentHashMapSafeDemo();
    }

    /**
     * JDK 7 头插法：新节点插入链表头部
     * 扩容时 transfer 代码逻辑（简化）：
     *
     * void transfer(Entry[] newTable) {
     *     for (Entry e : table) {
     *         while (e != null) {
     *             Entry next = e.next;
     *             int i = indexFor(e.hash, newTable.length);
     *             e.next = newTable[i];
     *             newTable[i] = e;
     *             e = next;
     *         }
     *     }
     * }
     *
     *  线程 A 刚拿到 e 和 next (e=A, next=B)，时间片耗尽。
     *  线程 B 完成整个 resize，导致 A→B 的顺序反转成 B→A。
     *  线程 A 恢复执行，继续用旧引用操作，把 A→B→A 形成环。
     *
     *  触发条件：size > threshold (capacity * 0.75)
     *  默认：16 * 0.75 = 12，第 13 个元素插入时扩容
     */
    static void jdk7HeadInsertExplain() {
        System.out.println("=== JDK 7 头插法死循环原理 ===");
        System.out.println("JDK 7 resize 时使用头插法（new node inserted at head of bucket）。");
        System.out.println("多线程并发 resize 时，链表节点可能形成循环引用 (A→B→A)。");
        System.out.println("get() 遍历链表时陷入死循环，CPU 100%。");
        System.out.println();
    }

    /**
     * JDK 8 尾插法修复：新节点插入链表尾部
     * 不会形成循环链表，因为链表顺序在扩容后保持不变。
     * 但是 put 操作本身不是原子的，仍然可能数据覆盖。
     */
    static void jdk8TailInsertExplain() {
        System.out.println("=== JDK 8 尾插法修复 ===");
        System.out.println("JDK 8 resize 时使用尾插法，保持链表原有顺序。");
        System.out.println("扩容后每个桶分成 lo（低位）和 hi（高位）两条链，不会循环。");
        System.out.println("但 put 方法 synchronized 粒度不够，多线程仍可能丢失数据。");
        System.out.println();
    }

    /**
     * 演示：JDK 8 HashMap 多线程 put 数据丢失
     * 期望 size = 线程数 * 每线程 put 数，实际 size 可能偏小
     */
    static void concurrentPutDataLossDemo() throws InterruptedException {
        System.out.println("=== JDK 8 HashMap 多线程数据丢失演示 ===");

        int threadCount = 4;
        int perThread = 1000;
        Map<Integer, String> map = new HashMap<>();

        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    int key = counter.getAndIncrement();
                    map.put(key, "v" + key);
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        int expected = threadCount * perThread;
        int actual = map.size();
        System.out.println("  期望 size: " + expected);
        System.out.println("  实际 size: " + actual);
        if (actual < expected) {
            System.out.println("  丢失数据: " + (expected - actual) + " 条");
        }
        System.out.println("  结论: HashMap 多线程 put 会丢失数据！");
        System.out.println();
    }

    /**
     * 演示：ConcurrentHashMap 多线程安全
     */
    static void concurrentHashMapSafeDemo() throws InterruptedException {
        System.out.println("=== ConcurrentHashMap 多线程安全演示 ===");

        int threadCount = 4;
        int perThread = 1000;
        Map<Integer, String> map = new ConcurrentHashMap<>();

        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    int key = counter.getAndIncrement();
                    map.put(key, "v" + key);
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        int expected = threadCount * perThread;
        int actual = map.size();
        System.out.println("  期望 size: " + expected);
        System.out.println("  实际 size: " + actual);
        System.out.println("  数据完整性: " + (actual == expected));
        System.out.println("  结论: ConcurrentHashMap 保证多线程 put 数据不丢失！");
        System.out.println();
    }
}