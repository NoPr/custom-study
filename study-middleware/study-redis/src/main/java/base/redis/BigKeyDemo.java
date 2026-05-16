package base.redis;

import java.util.*;
import java.util.concurrent.*;

/**
 * Redis 大 Key 与热 Key 问题排查及解决方案
 * 大 Key: SCAN + MEMORY USAGE 发现, String 拆 Hash, 集合拆分子 key
 * 热 Key: 滑动窗口 QPS 检测, 本地缓存/L1+L2/读写分离/Proxy 降级
 */
public class BigKeyDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== 大 Key 与 热 Key 处理演示 ==========\n");

        bigKeyDiscoveryDemo();
        bigKeySplitDemo();
        hotKeyDetectionDemo();
        hotKeySolutionDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void bigKeyDiscoveryDemo() {
        System.out.println("--- 大 Key 发现: SCAN + MEMORY USAGE 模拟 ---");

        Map<String, Integer> keySizes = new LinkedHashMap<>();
        keySizes.put("user:list:recent", 50_000);
        keySizes.put("order:hash:detail", 8_000);
        keySizes.put("message:zset:timeline", 120_000);
        keySizes.put("session:string:abc", 200);
        keySizes.put("log:list:errors", 200_000);

        int bigKeyThreshold = 10_000;
        long totalMemory = 0;
        List<String> bigKeys = new ArrayList<>();

        System.out.println("SCAN 游标扫描 (模拟):");
        int cursor = 0;
        int scanCount = 0;
        for (Map.Entry<String, Integer> entry : keySizes.entrySet()) {
            scanCount++;
            cursor++;
            int elementCount = entry.getValue();
            long memoryUsage = elementCount * 72L;
            totalMemory += memoryUsage;
            System.out.printf("  SCAN cursor=%d: %-25s elements=%-7d MEMORY=%d bytes",
                    cursor, entry.getKey(), elementCount, memoryUsage);
            if (elementCount > bigKeyThreshold) {
                bigKeys.add(entry.getKey());
                System.out.print(" [BIG KEY!]");
            }
            System.out.println();
        }

        System.out.printf("\n发现 %d 个大 Key (阈值>%d 元素): %s%n",
                bigKeys.size(), bigKeyThreshold, bigKeys);
        System.out.printf("总内存估算: %.2f MB%n", totalMemory / 1024.0 / 1024.0);
        System.out.println("建议: --bigkeys 命令, redis-rdb-tools 分析 RDB\n");
    }

    static void bigKeySplitDemo() {
        System.out.println("--- Big Key 拆分: String 拆 Hash ---");

        String originalKey = "user:profile:10086";
        Map<String, String> originalValue = new LinkedHashMap<>();
        originalValue.put("name", "张三");
        originalValue.put("age", "28");
        originalValue.put("address", "北京市朝阳区xxx路xxx号");
        originalValue.put("phone", "13800138000");
        originalValue.put("email", "zhangsan@example.com");
        originalValue.put("bio", "这是个人简介...");
        originalValue.put("avatar", "https://cdn.example.com/avatars/10086.jpg");
        originalValue.put("settings", "{\"theme\":\"dark\",\"lang\":\"zh\"}");

        System.out.println("原始 String Key:");
        System.out.println("  Key: " + originalKey);
        System.out.println("  Value: " + originalValue);
        System.out.println("  问题: 序列化/反序列化整个对象成本高, 频繁修改浪费网络");

        System.out.println("\n拆分后 Hash 结构:");
        System.out.println("  Key: user:profile:10086 (Hash)");
        for (Map.Entry<String, String> e : originalValue.entrySet()) {
            System.out.printf("    field: %-12s value: %s%n", e.getKey(), e.getValue());
        }
        System.out.println("  优势: HGET 按需获取字段, HSET 只更新变更字段\n");
    }

    static void hotKeyDetectionDemo() {
        System.out.println("--- 热 Key 检测: 滑动窗口计数模拟 ---");

        int windowSizeSeconds = 10;
        int hotKeyThreshold = 5;
        Map<String, SlidingWindowCounter> counters = new ConcurrentHashMap<>();

        String[] accessLog = {
                "product:1001", "product:1001", "product:1001", "product:1002",
                "product:1001", "product:1001", "product:1003", "product:1001",
                "product:1001", "product:1002", "product:1001", "product:1001"
        };

        long baseTime = System.currentTimeMillis() / 1000;
        for (int i = 0; i < accessLog.length; i++) {
            long timestamp = baseTime + (i % windowSizeSeconds);
            String key = accessLog[i];
            counters.computeIfAbsent(key, k -> new SlidingWindowCounter(windowSizeSeconds))
                    .record(timestamp);
        }

        System.out.printf("滑动窗口: %ds, 热Key阈值: %d次, 时间范围: %d~%d%n",
                windowSizeSeconds, hotKeyThreshold, baseTime, baseTime + windowSizeSeconds);

        for (Map.Entry<String, SlidingWindowCounter> entry : counters.entrySet()) {
            long count = entry.getValue().countInWindow(baseTime + windowSizeSeconds - 1, windowSizeSeconds);
            String mark = count >= hotKeyThreshold ? " [热Key!]" : "";
            System.out.printf("  %s -> %d 次%s%n", entry.getKey(), count, mark);
        }
        System.out.println();
    }

    static void hotKeySolutionDemo() {
        System.out.println("--- 热 Key 解决方案 ---");

        System.out.println("[方案1] 本地缓存 (二级缓存):");
        System.out.println("  L1: Caffeine/Guava Cache (JVM 堆内)");
        System.out.println("  L2: Redis");
        System.out.println("  热点 key 缓存在应用本地, 减少 Redis 单节点压力");

        System.out.println("\n[方案2] 读写分离:");
        System.out.println("  热 Key 分散到多个 Slave 节点读取");
        System.out.println("  架构: Master(写) -> Slave1(读), Slave2(读), Slave3(读)");
        System.out.println("  客户端随机选择 Slave 读取热 Key");

        System.out.println("\n[方案3] Key 拆分:");
        System.out.println("  hot:product:1001 -> 拆为 N 份");
        System.out.println("  hot:product:1001:1, hot:product:1001:2, ..., hot:product:1001:N");
        System.out.println("  写入时同时写 N 份, 读取时随机取一份");

        System.out.println("\n[方案4] 热点发现 + 自动降级:");
        System.out.println("  客户端/Proxy 层实时统计 QPS");
        System.out.println("  检测到热 Key 自动: 本地缓存 / 限流 / 只读副本\n");
    }

    /**
     * 滑动窗口计数器 -- 使用 ConcurrentSkipListMap 按时间戳分桶,
     * 统计窗口内访问次数, 超过阈值判定为热 Key
     */
    static class SlidingWindowCounter {
        private final int windowSize;
        private final ConcurrentSkipListMap<Long, Integer> bucket = new ConcurrentSkipListMap<>();

        SlidingWindowCounter(int windowSize) { this.windowSize = windowSize; }

        void record(long timestamp) {
            bucket.merge(timestamp, 1, Integer::sum);
        }

        long countInWindow(long endTime, int windowSeconds) {
            long startTime = endTime - windowSeconds + 1;
            return bucket.subMap(startTime, true, endTime, true)
                    .values().stream().mapToLong(Integer::longValue).sum();
        }
    }
}