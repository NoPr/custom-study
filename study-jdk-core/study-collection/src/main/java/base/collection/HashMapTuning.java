package base.collection;

import java.util.HashMap;
import java.util.Map;

/**
 * HashMap 调优实战
 *
 * 核心调优点：
 * 1. 合理设置初始容量：initialCapacity = expectedSize / 0.75 + 1
 *    避免扩容带来的 rehash 开销
 * 2. 自定义 key 时正确实现 hashCode() 和 equals()
 *    - hashCode 必须均匀分布，避免大量碰撞退化为链表/红黑树
 *    - equals 必须正确，否则 get 不到值或覆盖值
 * 3. 线程安全：多线程场景用 ConcurrentHashMap 或 Collections.synchronizedMap
 * 4. 遍历性能：entrySet > keySet+get（keySet 遍历每次都要 get，多一次查找）
 * 5. JDK 8+ 的红黑树优化：碰撞严重时自动从链表升级为红黑树（O(n) → O(log n)）
 */
public class HashMapTuning {

    public static void main(String[] args) {
        int dataCount = 1_000_000;

        System.out.println("=== HashMap 调优实战 ===\n");
        System.out.println("测试数据量: " + String.format("%,d", dataCount) + "\n");

        capacityTuning(dataCount);
        traversalPerformance();
        badHashCodeDemo();
    }

    /**
     * 容量调优：预设容量 vs 默认容量
     *
     * 默认构造：初始容量 16，插入 100 万条数据会触发多次扩容（16→32→64→...→1,048,576）
     * 每次扩容都涉及 rehash，重新分配所有元素
     *
     * 优化公式：initialCapacity = expectedSize / loadFactor + 1
     * = 1,000,000 / 0.75 + 1 ≈ 1,333,334
     * tableSizeFor 向上取 2 次幂 = 2,097,152（但实际用不了那么多）
     *
     * 更精确的计算：先反推需要的数组大小，保证 expectedSize 后不扩容
     * threshold = capacity * 0.75 >= expectedSize → capacity >= expectedSize / 0.75
     */
    static void capacityTuning(int count) {
        System.out.println("【容量调优】");

        long start = System.currentTimeMillis();
        Map<Integer, String> defaultMap = new HashMap<>();
        for (int i = 0; i < count; i++) {
            defaultMap.put(i, "value");
        }
        long defaultTime = System.currentTimeMillis() - start;
        System.out.println("  默认容量耗时: " + defaultTime + "ms");

        start = System.currentTimeMillis();
        int capacity = (int) (count / 0.75f) + 1;
        Map<Integer, String> optimizedMap = new HashMap<>(capacity);
        for (int i = 0; i < count; i++) {
            optimizedMap.put(i, "value");
        }
        long optimizedTime = System.currentTimeMillis() - start;
        System.out.println("  优化容量耗时: " + optimizedTime + "ms");
        System.out.println("  初始容量值: " + capacity);
        System.out.println("  实际数组大小 (tableSizeFor): " + HashMapSourceAnalysis.tableSizeFor(capacity));
        System.out.println("  性能提升: " + (defaultTime - optimizedTime) + "ms");
        System.out.println();

        // 验证数据完整性
        assert defaultMap.size() == count : "default 数据量不对";
        assert optimizedMap.size() == count : "optimized 数据量不对";
    }

    /**
     * 遍历性能对比：entrySet vs keySet+get
     *
     * entrySet 一次遍历拿到 key 和 value
     * keySet 遍历后还需要调用 get(key) 再次查表
     */
    static void traversalPerformance() {
        System.out.println("【遍历性能】");

        int count = 100_000;
        Map<Integer, String> map = new HashMap<>((int) (count / 0.75f) + 1);
        for (int i = 0; i < count; i++) {
            map.put(i, "value" + i);
        }

        // entrySet 遍历
        long start = System.nanoTime();
        for (Map.Entry<Integer, String> entry : map.entrySet()) {
            @SuppressWarnings("unused")
            String v = entry.getValue();
        }
        long entrySetTime = System.nanoTime() - start;

        // keySet + get 遍历
        start = System.nanoTime();
        for (Integer key : map.keySet()) {
            @SuppressWarnings("unused")
            String v = map.get(key);
        }
        long keySetTime = System.nanoTime() - start;

        System.out.println("  entrySet 遍历耗时: " + entrySetTime / 1_000_000.0 + "ms");
        System.out.println("  keySet+get 遍历耗时: " + keySetTime / 1_000_000.0 + "ms");
        System.out.println("  entrySet 快: " + String.format("%.1f", (double) keySetTime / entrySetTime) + "x");
        System.out.println();
    }

    /**
     * 错误的 hashCode 实现 → 退化为链表/红黑树，性能暴跌
     * 正确的 hashCode 实现 → 均匀分布，O(1) 查找
     */
    static void badHashCodeDemo() {
        System.out.println("【hashCode 质量对性能的影响】");

        int count = 10_000;

        // 场景 1：hashCode 全部相同 → 退化为单链表 O(n)
        Map<BadKey, String> badMap = new HashMap<>();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            badMap.put(new BadKey(i), "v" + i);
        }
        long badPutTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            badMap.get(new BadKey(i));
        }
        long badGetTime = System.nanoTime() - start;

        // 场景 2：使用 Integer 自带的 hashCode（均匀分布）
        Map<Integer, String> goodMap = new HashMap<>();
        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            goodMap.put(i, "v" + i);
        }
        long goodPutTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            goodMap.get(i);
        }
        long goodGetTime = System.nanoTime() - start;

        System.out.println("  坏 hashCode put: " + badPutTime / 1_000_000.0 + "ms  vs  好 hashCode put: " + goodPutTime / 1_000_000.0 + "ms");
        System.out.println("  坏 hashCode get: " + badGetTime / 1_000_000.0 + "ms  vs  好 hashCode get: " + goodGetTime / 1_000_000.0 + "ms");
        System.out.println("  put 慢 " + String.format("%.1f", (double) badPutTime / goodPutTime) + "x, get 慢 " + String.format("%.1f", (double) badGetTime / goodGetTime) + "x");
        System.out.println();
    }

    static class BadKey {
        private int id;

        BadKey(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BadKey)) {
                return false;
            }
            return id == ((BadKey) obj).id;
        }
    }
}