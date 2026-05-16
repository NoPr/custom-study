package base.distributed;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式锁方案演示 —— 3 种主流实现 + RedLock 算法模拟。
 *
 * <ul>
 *   <li><b>Redis 分布式锁</b>：SET NX PX + Lua 脚本释放 + 看门狗(WatchDog)续期</li>
 *   <li><b>ZooKeeper 分布式锁</b>：临时顺序节点 + Watch 机制</li>
 *   <li><b>MySQL 分布式锁</b>：排它锁(for update) + 版本号乐观锁</li>
 *   <li><b>RedLock 算法</b>：多 Redis 实例过半成功</li>
 * </ul>
 */
@SuppressWarnings("all")
public class LockDemo {

    /* =========================================================================
     * 1. Redis 分布式锁 —— SET NX + Lua 释放 + 看门狗
     * ========================================================================= */

    /**
     * 模拟 Redis 存储。
     * key = lockName, value = lockValue(线程标识)+expireTimestamp
     */
    static class MockRedis {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final Map<String, Long> expireAt = new ConcurrentHashMap<>();

        /**
         * SET NX PX —— 原子性加锁。
         * @return true=加锁成功
         */
        boolean setNxPx(String key, String value, long pxMillis) {
            String result = store.putIfAbsent(key, value);
            if (result == null) {
                expireAt.put(key, System.currentTimeMillis() + pxMillis);
                return true;
            }
            // 如果已过期，允许抢占
            if (System.currentTimeMillis() > expireAt.getOrDefault(key, 0L)) {
                store.put(key, value);
                expireAt.put(key, System.currentTimeMillis() + pxMillis);
                return true;
            }
            return false;
        }

        /**
         * Lua 脚本释放：判断 value 相同才删除，防止误删他人锁。
         */
        boolean releaseByLua(String key, String expectedValue) {
            String current = store.get(key);
            if (expectedValue.equals(current)) {
                store.remove(key);
                expireAt.remove(key);
                return true;
            }
            return false;
        }

        String get(String key) { return store.get(key); }
    }

    /** Redisson 看门狗(WatchDog)机制模拟 */
    static class WatchDog {
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final MockRedis redis;
        private final Map<String, Runnable> renewalTasks = new ConcurrentHashMap<>();

        WatchDog(MockRedis redis) { this.redis = redis; }

        /** 启动续期：每 intervalMs 续期一次 */
        void startRenewal(String lockKey, long renewIntervalMs) {
            Runnable task = () -> {
                String val = redis.get(lockKey);
                if (val != null) {
                    System.out.printf("[WatchDog] 续期 %s%n", lockKey);
                    // 模拟续期：更新过期时间
                    redis.store.put(lockKey, val);
                }
            };
            renewalTasks.put(lockKey, task);
            scheduler.scheduleAtFixedRate(task, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
        }

        void stopRenewal(String lockKey) {
            renewalTasks.remove(lockKey);
        }

        void shutdown() { scheduler.shutdown(); }
    }

    /* =========================================================================
     * 2. ZooKeeper 分布式锁 —— 临时顺序节点 + Watch
     * ========================================================================= */

    /**
     * ZK 锁原理：客户端在 /lock 下创建临时顺序节点，序号最小的获得锁；
     * 其余客户端 Watch 前一个节点，前节点删除时唤醒。
     */
    static class ZkLockSimulator {
        private final TreeMap<String, String> nodes = new TreeMap<>(); // seq → clientId
        private final Map<String, String> waiters = new LinkedHashMap<>(); // clientId → 等待的前节点
        private final AtomicInteger seqCounter = new AtomicInteger(0);

        /** 客户端尝试加锁，返回锁路径 */
        synchronized String tryLock(String clientId) {
            String seq = String.format("%010d", seqCounter.incrementAndGet());
            String nodePath = "/lock/" + seq;
            nodes.put(seq, clientId);
            System.out.printf("[ZK] %s 创建临时顺序节点 %s%n", clientId, nodePath);

            // 检查是否是最小节点
            String firstKey = nodes.firstKey();
            if (seq.equals(firstKey)) {
                System.out.printf("[ZK] %s 获得锁 (节点最小)%n", clientId);
                return nodePath;
            }
            // 不是最小节点，Watch 前一个节点
            String prevSeq = nodes.lowerKey(seq);
            String prevClient = nodes.get(prevSeq);
            waiters.put(clientId, prevSeq);
            System.out.printf("[ZK] %s Watch 前节点 %s%n", clientId, prevSeq);
            return null; // 未获得锁，等待
        }

        /** 客户端释放锁（删除节点），通知下一个 */
        synchronized void unlock(String clientId) {
            String mySeq = null;
            for (Map.Entry<String, String> e : nodes.entrySet()) {
                if (e.getValue().equals(clientId)) {
                    mySeq = e.getKey();
                    break;
                }
            }
            if (mySeq != null) {
                nodes.remove(mySeq);
                waiters.remove(clientId);
                System.out.printf("[ZK] %s 释放锁，删除节点 %s%n", clientId, mySeq);

                // 查找 Watch 我节点的客户端并唤醒
                String nextSeq = nodes.higherKey(mySeq);
                if (nextSeq != null) {
                    String nextClient = nodes.get(nextSeq);
                    waiters.remove(nextClient);
                    System.out.printf("[ZK] 唤醒 %s (新的最小节点)%n", nextClient);
                }
            }
        }
    }

    /* =========================================================================
     * 3. MySQL 分布式锁 —— 排它锁 + 乐观锁
     * ========================================================================= */

    /** 悲观锁：SELECT ... FOR UPDATE */
    static class MysqlPessimisticLock {
        private final Map<String, String> table = new ConcurrentHashMap<>();

        /** 模拟行锁：同一行同一时间只有一个线程能持有 */
        synchronized boolean lockByForUpdate(String resourceKey) {
            if (table.containsKey(resourceKey)) {
                System.out.printf("[MySQL-悲观] 资源 %s 已被锁定%n", resourceKey);
                return false;
            }
            table.put(resourceKey, "LOCKED");
            System.out.printf("[MySQL-悲观] %s FOR UPDATE 加锁成功%n", resourceKey);
            return true;
        }

        synchronized void unlock(String resourceKey) {
            table.remove(resourceKey);
            System.out.printf("[MySQL-悲观] %s 释放锁%n", resourceKey);
        }
    }

    /** 乐观锁：版本号(version) CAS */
    static class MysqlOptimisticLock {
        private final Map<String, AtomicInteger> versionTable = new ConcurrentHashMap<>();
        private final Map<String, Integer> dataTable = new ConcurrentHashMap<>();

        MysqlOptimisticLock() {
            versionTable.put("stock", new AtomicInteger(1));
            dataTable.put("stock", 100);
        }

        /**
         * UPDATE table SET stock = stock - delta, version = version + 1
         * WHERE id = ? AND version = expectedVersion
         */
        boolean deductStock(int delta) {
            int currentVersion = versionTable.get("stock").get();
            System.out.printf("[MySQL-乐观] 读取版本号 v%d, 库存=%d%n", currentVersion, dataTable.get("stock"));

            // 模拟 CAS
            boolean success = versionTable.get("stock").compareAndSet(currentVersion, currentVersion + 1);
            if (success) {
                dataTable.compute("stock", (k, v) -> v - delta);
                System.out.printf("[MySQL-乐观] CAS 成功，新版本 v%d, 库存=%d%n", currentVersion + 1, dataTable.get("stock"));
                return true;
            }
            System.out.printf("[MySQL-乐观] CAS 失败，版本号已变为 v%d，重试%n", versionTable.get("stock").get());
            return false;
        }
    }

    /* =========================================================================
     * 4. RedLock 算法（手写模拟）
     * ========================================================================= */

    /**
     * RedLock: 在 N 个独立 Redis 实例上尝试加锁，过半成功则获得锁。
     * 每个实例锁有效期 TTL，加锁总耗时 << TTL。
     */
    static class RedLockSimulator {
        private final List<MockRedis> instances;
        private final int quorum; // 法定人数：N/2 + 1

        RedLockSimulator(int n) {
            instances = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                instances.add(new MockRedis());
            }
            quorum = n / 2 + 1;
        }

        /** 尝试在多个 Redis 实例上加锁，过半成功返回 true */
        boolean tryLock(String key, String value, long ttlMillis) {
            int successCount = 0;
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < instances.size(); i++) {
                MockRedis redis = instances.get(i);
                if (redis.setNxPx(key, value, ttlMillis)) {
                    successCount++;
                    System.out.printf("[RedLock] 实例%d 加锁成功%n", i + 1);
                } else {
                    System.out.printf("[RedLock] 实例%d 加锁失败%n", i + 1);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            boolean quorumReached = successCount >= quorum;
            boolean withinTTL = elapsed < ttlMillis;

            System.out.printf("[RedLock] 成功数=%d/%d, 法定人数=%d, 耗时=%dms, TTL=%dms%n",
                successCount, instances.size(), quorum, elapsed, ttlMillis);

            if (!quorumReached || !withinTTL) {
                // 过半未达成或超时，释放已加锁的实例
                unlockAll(key, value);
                return false;
            }
            return true;
        }

        private void unlockAll(String key, String value) {
            for (MockRedis redis : instances) {
                redis.releaseByLua(key, value);
            }
            System.out.println("[RedLock] 释放所有实例锁");
        }
    }

    /* =========================================================================
     * main
     * ========================================================================= */

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=".repeat(60));
        System.out.println("1. Redis SET NX + Lua 释放 + WatchDog 看门狗");
        System.out.println("=".repeat(60));
        {
            MockRedis redis = new MockRedis();
            WatchDog watchDog = new WatchDog(redis);
            String lockKey = "order:123";
            String lockValue = UUID.randomUUID().toString();

            if (redis.setNxPx(lockKey, lockValue, 30_000)) {
                System.out.println("[Redis] 加锁成功");
                watchDog.startRenewal(lockKey, 10_000); // 每 10s 续期

                Thread.sleep(100); // 模拟业务

                redis.releaseByLua(lockKey, lockValue);
                watchDog.stopRenewal(lockKey);
                System.out.println("[Redis] Lua 脚本释放成功");
            }
            watchDog.shutdown();
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("2. ZooKeeper 临时顺序节点 + Watch");
        System.out.println("=".repeat(60));
        {
            ZkLockSimulator zk = new ZkLockSimulator();
            zk.tryLock("client-A"); // 获得锁
            zk.tryLock("client-B"); // 等待
            zk.tryLock("client-C"); // 等待

            zk.unlock("client-A");  // A 释放 → 唤醒 B
            zk.unlock("client-B");  // B 释放 → 唤醒 C
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("3. MySQL 悲观锁 + 乐观锁");
        System.out.println("=".repeat(60));
        {
            // 悲观锁
            MysqlPessimisticLock pessimisticLock = new MysqlPessimisticLock();
            pessimisticLock.lockByForUpdate("row:product:1");
            pessimisticLock.lockByForUpdate("row:product:1"); // 失败
            pessimisticLock.unlock("row:product:1");

            // 乐观锁
            MysqlOptimisticLock optimisticLock = new MysqlOptimisticLock();
            optimisticLock.deductStock(10); // 成功
            optimisticLock.deductStock(5);  // 可能失败（版本冲突时重试）
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("4. RedLock 算法模拟");
        System.out.println("=".repeat(60));
        {
            RedLockSimulator redLock = new RedLockSimulator(5); // 5 实例，quorum=3
            boolean locked = redLock.tryLock("resource:1", "client-X", 1000);
            System.out.println("[RedLock] 最终结果: " + (locked ? "加锁成功" : "加锁失败"));
        }
    }
}