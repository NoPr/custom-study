package base.redis;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存三大问题手写级演示: 缓存穿透、缓存击穿、缓存雪崩
 * 穿透: 查不存在的 key -> 空值缓存 / 布隆过滤器
 * 击穿: 热点 key 过期 -> 互斥锁 DCL 重建 / 逻辑过期
 * 雪崩: 大量 key 同时过期 -> 随机 TTL / 多级缓存 / 熔断降级
 */
public class CacheProblemDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== 缓存三大问题演示 ==========\n");

        cachePenetrationDemo();
        cacheBreakdownDemo();
        cacheAvalancheDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void cachePenetrationDemo() {
        System.out.println("--- 缓存穿透: 查询不存在的数据 ---");

        Map<String, String> cache = new ConcurrentHashMap<>();
        Map<String, String> db = Map.of("user:1", "zhangsan", "user:2", "lisi");

        System.out.println("\n[方案1] 空值缓存:");
        String[] queries = {"user:1", "user:99", "user:99", "user:2", "user:99"};
        for (String key : queries) {
            String cached = cache.get(key);
            if (cached != null) {
                System.out.printf("  查询 %s -> 缓存命中: %s%n", key,
                        "NULL_CACHE".equals(cached) ? "空值(防穿透)" : cached);
                continue;
            }
            String dbVal = db.get(key);
            if (dbVal != null) {
                cache.put(key, dbVal);
                System.out.printf("  查询 %s -> 查DB: %s -> 写入缓存%n", key, dbVal);
            } else {
                cache.put(key, "NULL_CACHE");
                System.out.printf("  查询 %s -> DB中不存在 -> 缓存空值(TTL=60s)%n", key);
            }
        }

        System.out.println("\n[方案2] 布隆过滤器 (简易版):");
        SimpleBloomFilter bloom = new SimpleBloomFilter(256, 3);
        bloom.add("user:1");
        bloom.add("user:2");
        bloom.add("user:3");

        String[] checkKeys = {"user:1", "user:99", "user:2", "user:999"};
        for (String key : checkKeys) {
            boolean mayExist = bloom.mightContain(key);
            if (!mayExist) {
                System.out.printf("  查询 %s -> 布隆判定不存在, 直接返回%n", key);
            } else {
                String cached = db.get(key);
                System.out.printf("  查询 %s -> 布隆判定可能存在, 查缓存/DB: %s%n", key,
                        cached != null ? cached : "不存在(误判)");
            }
        }
        System.out.println();
    }

    static void cacheBreakdownDemo() throws Exception {
        System.out.println("--- 缓存击穿: 热点 key 过期时大量请求打向 DB ---");

        Map<String, String> cache = new ConcurrentHashMap<>();
        cache.put("hot:product:1", "iPhone 15");
        ReentrantLockHolder holder = new ReentrantLockHolder();

        System.out.println("模拟: hot:product:1 过期, 10个并发请求...");
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            final int id = i;
            futures.add(executor.submit(() -> getWithMutex("hot:product:1", cache, holder, id)));
        }

        for (Future<String> f : futures) {
            System.out.println("  " + f.get());
        }
        executor.shutdown();

        System.out.println("互斥锁方案: 只让一个请求查 DB 重建缓存, 其余等待或返回旧值\n");
    }

    static void cacheAvalancheDemo() {
        System.out.println("--- 缓存雪崩: 大量 key 同时过期/Redis 宕机 ---");

        System.out.println("\n[场景] 10个缓存 key 设相同 TTL=3600s");
        String[] productIds = {"p:1", "p:2", "p:3", "p:4", "p:5"};
        Random rand = new Random();

        System.out.println("\n[方案] 随机过期时间:");
        for (String pid : productIds) {
            int baseTtl = 3600;
            int randomDelta = rand.nextInt(600);
            int actualTtl = baseTtl + randomDelta;
            System.out.printf("  %s -> TTL=%ds (基础%d + 随机%d)%n", pid, actualTtl, baseTtl, randomDelta);
        }

        System.out.println("\n[方案] 多级缓存降级:");
        System.out.println("  L1: 本地Caffeine缓存 (一级)");
        System.out.println("  L2: Redis缓存 (二级)");
        System.out.println("  L3: 数据库 (三级)");
        System.out.println("  Redis宕机时 -> 降级到 L1 本地缓存 -> 仍不可用则限流熔断\n");

        System.out.println("[方案] 服务降级 + 限流:");
        System.out.println("  Sentinel 限流: QPS超过阈值返回默认值/空列表");
        System.out.println("  Hystrix/Resilience4j 熔断: 错误率>50% 时快速失败\n");
    }

    static String getWithMutex(String key, Map<String, String> cache,
                                ReentrantLockHolder holder, int requestId) {
        String cached = cache.get(key);
        if (cached != null) {
            return String.format("请求%d: 缓存命中 -> %s", requestId, cached);
        }

        String lockKey = "lock:" + key;
        ReentrantLock lock = holder.getLock(lockKey);

        if (lock.tryLock()) {
            try {
                cached = cache.get(key);
                if (cached != null) {
                    return String.format("请求%d: DCL检查, 缓存已重建 -> %s", requestId, cached);
                }
                Thread.sleep(50);
                String dbValue = "iPhone 15 (from DB)";
                cache.put(key, dbValue);
                return String.format("请求%d: 获取锁, 查DB重建缓存 -> %s", requestId, dbValue);
            } catch (InterruptedException e) {
                return String.format("请求%d: 被中断", requestId);
            } finally {
                lock.unlock();
                holder.removeLock(lockKey);
            }
        } else {
            try {
                Thread.sleep(20);
                cached = cache.get(key);
                if (cached != null) {
                    return String.format("请求%d: 等待后缓存命中 -> %s", requestId, cached);
                }
                return String.format("请求%d: 未获取锁, 返回降级数据", requestId);
            } catch (InterruptedException e) {
                return String.format("请求%d: 等待中断", requestId);
            }
        }
    }

    /**
     * 简版布隆过滤器 -- 多哈希 + BitSet 实现
     * 核心: 不存在一定不存在, 存在可能误判 (不可消除的假阳性)
     */
    static class SimpleBloomFilter {
        private final BitSet bitSet;
        private final int size;
        private final int hashCount;

        SimpleBloomFilter(int size, int hashCount) {
            this.size = size;
            this.hashCount = hashCount;
            this.bitSet = new BitSet(size);
        }

        void add(String value) {
            for (int i = 0; i < hashCount; i++) {
                int hash = hash(value, i);
                bitSet.set(Math.abs(hash % size));
            }
        }

        boolean mightContain(String value) {
            for (int i = 0; i < hashCount; i++) {
                int hash = hash(value, i);
                if (!bitSet.get(Math.abs(hash % size))) return false;
            }
            return true;
        }

        private int hash(String value, int seed) {
            int h = seed;
            for (int i = 0; i < value.length(); i++) {
                h = h * 31 + value.charAt(i);
            }
            return h;
        }
    }

    /** 互斥锁持有者 -- 按 key 获取锁, 保证同一 key 只有一个线程重建缓存 */
    static class ReentrantLockHolder {
        private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        ReentrantLock getLock(String key) {
            return locks.computeIfAbsent(key, k -> new ReentrantLock());
        }

        void removeLock(String key) {
            locks.remove(key);
        }
    }
}