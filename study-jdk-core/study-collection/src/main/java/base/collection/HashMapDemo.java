package base.collection;

import java.util.HashMap;
import java.util.Map;

/**
 * HashMap 基础用法与内部结构演示
 *
 * 核心知识点：
 * 1. HashMap 底层 = 数组 + 链表 + 红黑树（JDK 8+）
 * 2. 默认初始容量 16，负载因子 0.75，扩容阈值 = capacity * loadFactor
 * 3. put 流程：hash → 索引 → 链表/红黑树 → 插入 → 判断扩容
 * 4. 链表转红黑树条件：链表长度 >= 8 且 数组长度 >= 64
 * 5. 红黑树退化为链表条件：节点数 <= 6
 */
public class HashMapDemo {

    public static void main(String[] args) {
        basicUsage();
        hashAndIndex();
        collisionDemo();
        nullKeyDemo();
    }

    static void basicUsage() {
        System.out.println("=== 基础用法 ===");
        Map<String, Integer> map = new HashMap<>();
        map.put("one", 1);
        map.put("two", 2);
        map.put("three", 3);

        System.out.println("size: " + map.size());
        System.out.println("get one: " + map.get("one"));
        System.out.println("containsKey two: " + map.containsKey("two"));
        System.out.println("containsValue 3: " + map.containsValue(3));

        // key 重复时覆盖旧值，返回旧值
        Integer oldValue = map.put("one", 11);
        System.out.println("覆盖 key=one 的旧值: " + oldValue);
        System.out.println("覆盖后 get one: " + map.get("one"));

        // 遍历方式
        System.out.println("\n遍历 entrySet:");
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            System.out.println("  " + entry.getKey() + " => " + entry.getValue());
        }

        System.out.println("\n遍历 keySet:");
        for (String key : map.keySet()) {
            System.out.println("  " + key + " => " + map.get(key));
        }
        System.out.println();
    }

    /**
     * hash() 方法原理：
     * (h = key.hashCode()) ^ (h >>> 16)
     * 高 16 位与低 16 位异或，让高位参与索引计算，减少碰撞
     */
    static void hashAndIndex() {
        System.out.println("=== hash 扰动与索引计算 ===");
        String key = "HashMap";
        int hashCode = key.hashCode();
        int hash = hashCode ^ (hashCode >>> 16);
        // 索引公式：(n - 1) & hash，n 为数组长度（2 的幂）
        int capacity = 16;
        int index = (capacity - 1) & hash;

        System.out.println("key: \"" + key + "\"");
        System.out.println("hashCode:  " + hashCode);
        System.out.println("hash (扰动后): " + hash);
        System.out.println("索引 (n=16): " + index);

        // 验证：扩容后新索引
        capacity = 32;
        int newIndex = (capacity - 1) & hash;
        System.out.println("索引 (n=32): " + newIndex);
        System.out.println("新索引 == 旧索引 or 旧索引+16? " +
                (newIndex == index || newIndex == index + 16));
        System.out.println();
    }

    /**
     * 哈希碰撞演示：不同 key 落到同一个桶
     */
    static void collisionDemo() {
        System.out.println("=== 哈希碰撞演示 ===");
        Map<CollisionKey, String> map = new HashMap<>();
        map.put(new CollisionKey("A"), "valueA");
        map.put(new CollisionKey("B"), "valueB");
        System.out.println("两个 hashCode 都为 1 的 key，碰撞后 size: " + map.size());
        System.out.println("碰撞时通过 equals 区分，get A: " + map.get(new CollisionKey("A")));
        System.out.println("碰撞时通过 equals 区分，get B: " + map.get(new CollisionKey("B")));
        System.out.println();
    }

    static class CollisionKey {
        private String name;

        CollisionKey(String name) {
            this.name = name;
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
            if (!(obj instanceof CollisionKey)) {
                return false;
            }
            CollisionKey other = (CollisionKey) obj;
            return name.equals(other.name);
        }
    }

    /**
     * HashMap 允许 null key 和 null value
     * null key 的 hash 固定为 0，始终放在 table[0] 位置
     */
    static void nullKeyDemo() {
        System.out.println("=== null key/value 演示 ===");
        Map<String, String> map = new HashMap<>();
        map.put(null, "nullValue");
        map.put("key", null);
        System.out.println("get(null): " + map.get(null));
        System.out.println("get(key): " + map.get("key"));
        System.out.println("containsKey(null): " + map.containsKey(null));
        System.out.println("containsValue(null): " + map.containsValue(null));
        System.out.println("null key 的 hash: " + hashOfNull());
    }

    static int hashOfNull() {
        System.out.print("(h = null.hashCode()) = NPE? → key==null 时直接 return 0, ");
        return 0;
    }
}