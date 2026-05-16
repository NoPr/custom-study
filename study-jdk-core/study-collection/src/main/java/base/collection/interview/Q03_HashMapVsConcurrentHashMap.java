package base.collection.interview;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面试题：HashMap vs ConcurrentHashMap 全方位对比
 *
 * 核心对比维度：
 *
 * | 维度           | HashMap          | ConcurrentHashMap (JDK 8)      |
 * |--------------|------------------|--------------------------------|
 * | 线程安全       | 否                | 是                             |
 * | 锁粒度         | 无锁               | CAS + synchronized 桶头节点     |
 * | null key/value | 允许               | 不允许（会抛 NPE）              |
 * | 数据结构       | 数组+链表+红黑树    | 数组+链表+红黑树                |
 * | 扩容机制       | 单线程 resize       | 多线程协同扩容（transferIndex） |
 * | 迭代器         | fail-fast          | weakly consistent（不抛 CME）   |
 * | size 计算      | int size 成员变量   | 分段计数 + baseCount（减少竞争） |
 *
 * JDK 7 ConcurrentHashMap 采用分段锁 Segment（继承 ReentrantLock），
 * 默认 16 个段，并发度 16。JDK 8 放弃分段锁，改为 CAS + synchronized，
 * 锁粒度更细（桶级别），并发度更高。
 */
public class Q03_HashMapVsConcurrentHashMap {

    public static void main(String[] args) throws Exception {
        nullKeyValue();
        threadSafetyPerformance();
        concurrentExpansion();
        failFastVsWeaklyConsistent();
    }

    /**
     * HashMap 允许 null key/value
     * ConcurrentHashMap 不允许（put(null) 抛 NPE）
     */
    static void nullKeyValue() {
        System.out.println("=== null key/value 支持 ===");

        Map<String, String> hashMap = new HashMap<>();
        hashMap.put(null, "v1");
        hashMap.put("k1", null);
        System.out.println("  HashMap  put(null)=ok,  put(k,null)=ok");
        System.out.println("  HashMap  get(null)=" + hashMap.get(null));
        System.out.println("  HashMap  containsKey(null)=" + hashMap.containsKey(null));

        Map<String, String> chm = new ConcurrentHashMap<>();
        try {
            chm.put(null, "v");
        } catch (NullPointerException e) {
            System.out.println("  ConcurrentHashMap put(null) → NPE");
        }
        try {
            chm.put("k", null);
        } catch (NullPointerException e) {
            System.out.println("  ConcurrentHashMap put(k, null) → NPE");
        }

        System.out.println("  原因: 并发环境下 null 有歧义，无法区分「不存在」和「值为 null」");
        System.out.println();
    }

    /**
     * 多线程 put 性能对比
     */
    static void threadSafetyPerformance() throws Exception {
        System.out.println("=== 线程安全 & 性能对比 ===");

        int threadCount = 8;
        int perThread = 100_000;

        long hashMapTime = multiPut(new HashMap<>(), threadCount, perThread, "HashMap");
        long syncMapTime = multiPut(Collections.synchronizedMap(new HashMap<>()), threadCount, perThread, "synchronizedMap");
        long chmTime = multiPut(new ConcurrentHashMap<>(), threadCount, perThread, "ConcurrentHashMap");

        System.out.println("  HashMap (no lock):              " + hashMapTime + "ms  (数据不安全！)");
        System.out.println("  synchronizedMap (全表锁):        " + syncMapTime + "ms");
        System.out.println("  ConcurrentHashMap (桶级锁):      " + chmTime + "ms");

        System.out.printf("  CHM 比 syncMap 快: %.1fx%n", (double) syncMapTime / chmTime);
        System.out.println();
    }

    /**
     * 多线程扩容：ConcurrentHashMap 支持多线程协同扩容
     * 每个线程通过 transferIndex 领取一段桶区间来迁移数据
     * HashMap 只能单线程扩容，数据可能在迁移过程中丢失
     */
    static void concurrentExpansion() throws Exception {
        System.out.println("=== 扩容机制对比 ===");
        System.out.println("  HashMap:   单线程 resize，All-or-Nothing，迁移过程中数据可能丢失");
        System.out.println("  CHM (JDK 8): 多线程协同扩容，transferIndex 分段迁移");
        System.out.println("    线程通过 CAS 竞争 transferIndex，每段 stride 个桶");
        System.out.println("    sizeCtl 用负值记录扩容线程数 + 1");
        System.out.println("    ForwardingNode 标记已迁移桶，读请求直接转发到新表");
        System.out.println();

        // 演示：初始化一个较小的 ConcurrentHashMap 触发扩容
        Map<Integer, String> chm = new ConcurrentHashMap<>(2);
        System.out.println("  初始容量 2 的 CHM，连续 add 触发扩容...");
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100_000; i++) {
            chm.put(i, "v" + i);
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("  连续 put 100k 元素（含多次扩容）: " + elapsed + "ms, size=" + chm.size());
        System.out.println();
    }

    /**
     * fail-fast vs weakly consistent
     * HashMap 迭代期间修改 → ConcurrentModificationException
     * ConcurrentHashMap 迭代期间修改 → 不抛异常，可能看到也可能看不到新数据
     */
    static void failFastVsWeaklyConsistent() {
        System.out.println("=== fail-fast vs weakly consistent 迭代器 ===");

        Map<String, String> hashMap = new HashMap<>();
        hashMap.put("a", "1");
        hashMap.put("b", "2");

        System.out.print("  HashMap 迭代期间 remove → ");
        try {
            for (String key : hashMap.keySet()) {
                if ("a".equals(key)) {
                    hashMap.remove(key);
                }
            }
            System.out.println("没有抛异常（remove 最后一个元素有时不触发）");
        } catch (ConcurrentModificationException e) {
            System.out.println("ConcurrentModificationException（fail-fast）");
        }

        Map<String, String> chm = new ConcurrentHashMap<>();
        chm.put("a", "1");
        chm.put("b", "2");
        chm.put("c", "3");

        System.out.print("  ConcurrentHashMap 迭代期间 remove → ");
        try {
            for (String key : chm.keySet()) {
                if ("a".equals(key)) {
                    chm.remove(key);
                }
            }
            System.out.println("不抛异常（weakly consistent）");
        } catch (ConcurrentModificationException e) {
            System.out.println("ConcurrentModificationException");
        }

        System.out.println("  原因: CHM 迭代器遍历的是某个时间点的快照，允许并发修改");
        System.out.println();
    }

    static long multiPut(Map<Integer, String> map, int threads, int perThread, String label)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger total = new AtomicInteger(0);

        long start = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    int key = total.getAndIncrement();
                    map.put(key, "v" + key);
                }
                latch.countDown();
            });
        }
        latch.await();
        executor.shutdown();
        long elapsed = System.currentTimeMillis() - start;

        int expected = threads * perThread;
        int actual = map.size();
        if (actual != expected) {
            System.out.printf("  %s: 数据丢失! 期望=%d 实际=%d 丢失=%d%n",
                    label, expected, actual, expected - actual);
        }
        return elapsed;
    }
}