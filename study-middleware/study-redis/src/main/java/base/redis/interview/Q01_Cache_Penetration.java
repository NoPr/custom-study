package base.redis.interview;

import java.util.*;

/**
 * 面试题: 缓存穿透两大方案 -- 空值缓存 vs 布隆过滤器
 * 空值缓存: 对 DB 不存在的 key 缓存 NULL (TTL 短), 简单但有内存风险
 * 布隆过滤器: 多哈希 + BitSet, 一定不存在则拒绝, 可能存在则放行 (有误判率)
 * 误判率公式: (1-e^(-k*n/m))^k, 通过调整 m(位图大小) 和 k(哈希次数) 控制
 */
public class Q01_Cache_Penetration {

    public static void main(String[] args) {
        System.out.println("========== 面试题: 缓存穿透 ==========\n");

        solutionNullCache();
        solutionBloomFilter();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void solutionNullCache() {
        System.out.println("--- 方案1: 缓存空值 ---");

        Map<String, String> cache = new HashMap<>();
        Map<String, String> db = Map.of(
                "user:1", "Alice",
                "user:2", "Bob",
                "user:3", "Charlie"
        );

        String[] queries = {"user:1", "user:-1", "user:-1", "user:2", "user:-1", "user:999"};

        for (String key : queries) {
            if (cache.containsKey(key)) {
                String value = cache.get(key);
                System.out.printf("  key=%-10s -> 缓存命中: %s%n", key,
                        value == null ? "NULL (防穿透)" : value);
                continue;
            }

            String dbValue = db.get(key);
            if (dbValue != null) {
                cache.put(key, dbValue);
                System.out.printf("  key=%-10s -> DB查到: %s, 写入缓存(TTL=3600s)%n", key, dbValue);
            } else {
                cache.put(key, null);
                System.out.printf("  key=%-10s -> DB不存在, 缓存NULL(TTL=60s)%n", key);
            }
        }

        System.out.println("  优点: 简单直接, 立即可用");
        System.out.println("  缺点: 恶意构造不同不存在的key仍会占满内存");
        System.out.println("  改进: null值TTL要短(30-60s), 或配合布隆过滤器\n");
    }

    static void solutionBloomFilter() {
        System.out.println("--- 方案2: 布隆过滤器 ---");

        BloomFilter bf = new BloomFilter(1024, 3);
        List<String> existingKeys = Arrays.asList(
                "user:1", "user:2", "user:3", "product:100","product:200", "order:5000"
        );
        for (String key : existingKeys) {
            bf.add(key);
        }
        System.out.println("布隆过滤器初始化: 添加 " + existingKeys.size() + " 个存在的 key");

        String[] testKeys = {"user:1", "user:-1", "product:100", "user:999", "order:5001"};
        int falsePositiveCount = 0;

        for (String key : testKeys) {
            boolean mightExist = bf.mightContain(key);
            boolean actuallyExists = existingKeys.contains(key);

            String result;
            if (!mightExist) {
                result = "不存在 -> 直接拒绝(正确)";
            } else if (actuallyExists) {
                result = "可能存在 -> 查缓存/DB -> 命中(正确)";
            } else {
                falsePositiveCount++;
                result = "可能存在 -> 查缓存/DB -> 未命中(误判!)";
            }
            System.out.printf("  %-14s bloom=%s actual=%s -> %s%n",
                    key, mightExist, actuallyExists, result);
        }

        double fpRate = (double) falsePositiveCount / testKeys.length * 100;
        System.out.printf("  误判率: %.1f%%%n", fpRate);
        System.out.println("  布隆过滤器特点: 不存在一定不存在, 存在可能误判");
        System.out.println("  误判率公式: (1-e^(-k*n/m))^k, 可调节 m/k 控制\n");
    }

    /**
     * 布隆过滤器 -- 使用 double-hashing 派生 k 个哈希值,
     * hash1 = hashCode(), hash2 = hash1 >>> 16, hashes[i] = hash1 + i * hash2
     * 相比独立 k 个哈希函数, 性能更好且分布均匀性可接受
     */
    static class BloomFilter {
        private final BitSet bitSet;
        private final int size;
        private final int hashCount;

        BloomFilter(int size, int hashCount) {
            this.size = size;
            this.hashCount = hashCount;
            this.bitSet = new BitSet(size);
        }

        void add(String value) {
            int[] hashes = multiHash(value);
            for (int h : hashes) {
                bitSet.set(Math.abs(h % size));
            }
        }

        boolean mightContain(String value) {
            int[] hashes = multiHash(value);
            for (int h : hashes) {
                if (!bitSet.get(Math.abs(h % size))) return false;
            }
            return true;
        }

        private int[] multiHash(String value) {
            int[] hashes = new int[hashCount];
            int hash1 = value.hashCode();
            int hash2 = hash1 >>> 16;
            for (int i = 0; i < hashCount; i++) {
                hashes[i] = hash1 + i * hash2;
            }
            return hashes;
        }
    }
}