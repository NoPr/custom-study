package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】PageCache 读写缓存 —— 命中率·预读·刷盘策略
 * 【描述】模拟 Kafka PageCache 机制：OS 层页缓存对读写性能的影响。
 *         读操作：PageCache 命中直接返回（~μs 级），未命中触发磁盘 IO（~ms 级）。
 *         写操作：写入 PageCache 即返回（异步刷盘），flush 策略控制持久化时机。
 *         演示不同读写模式下的缓存命中率与延迟差异。
 * 【关键概念】PageCache、缓存命中率、预读(readahead)、脏页(Dirty Page)、
 *             pdflush、fsync、flush.messages/ms、log.flush.interval
 * 【关联类】@see com.nopr.mq.kafka.simulate.LowLatencyDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class PageCacheDemo {

    static final int PAGE_SIZE = 4096;
    static final long CACHE_HIT_NS = 500;
    static final long CACHE_MISS_MS = 5_000_000;
    static final long WRITE_CACHE_NS = 1_000;
    static final long FLUSH_DISK_MS = 8_000_000;

    record Page(long offset, byte[] data, long lastAccessTime, boolean dirty) {}

    static class PageCacheSimulator {
        private final int maxPages;
        private final Map<Long, Page> cache = new LinkedHashMap<>(16, 0.75f, true);
        private long hitCount = 0;
        private long missCount = 0;
        private int dirtyPageCount = 0;

        PageCacheSimulator(int maxPages) { this.maxPages = maxPages; }

        long read(long offset, int size) {
            long startPage = offset / PAGE_SIZE;
            long endPage = (offset + size - 1) / PAGE_SIZE;
            boolean allHit = true;

            for (long p = startPage; p <= endPage; p++) {
                Page page = cache.get(p);
                if (page == null) {
                    allHit = false;
                    byte[] data = new byte[PAGE_SIZE];
                    page = new Page(p, data, System.nanoTime(), false);
                    evictIfNeeded();
                    cache.put(p, page);
                } else {
                    page = new Page(p, page.data(), System.nanoTime(), page.dirty());
                }
            }

            if (allHit) {
                hitCount++;
                return CACHE_HIT_NS;
            } else {
                missCount++;
                return CACHE_MISS_MS;
            }
        }

        long write(long offset, int size) {
            long startPage = offset / PAGE_SIZE;
            long endPage = (offset + size - 1) / PAGE_SIZE;

            for (long p = startPage; p <= endPage; p++) {
                Page existing = cache.get(p);
                if (existing != null && !existing.dirty()) dirtyPageCount++;
                byte[] data = new byte[PAGE_SIZE];
                Page page = new Page(p, data, System.nanoTime(), true);
                evictIfNeeded();
                cache.put(p, page);
            }

            return WRITE_CACHE_NS;
        }

        long flush() {
            long flushed = 0;
            Iterator<Map.Entry<Long, Page>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Page> entry = it.next();
                if (entry.getValue().dirty()) {
                    it.remove();
                    flushed++;
                    dirtyPageCount--;
                }
            }
            return flushed * FLUSH_DISK_MS;
        }

        private void evictIfNeeded() {
            if (cache.size() >= maxPages) {
                Iterator<Map.Entry<Long, Page>> it = cache.entrySet().iterator();
                if (it.hasNext()) {
                    Page evicted = it.next().getValue();
                    it.remove();
                    if (evicted.dirty()) dirtyPageCount--;
                }
            }
        }

        double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0 : (double) hitCount / total;
        }

        int cachePages() { return cache.size(); }
        int dirtyPages() { return dirtyPageCount; }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka PageCache 读写缓存 Demo");
        System.out.println("=".repeat(60));

        PageCacheSimulator cache = new PageCacheSimulator(100);

        System.out.println("\n--- 顺序读取（高命中率）---");
        for (int i = 0; i < 50; i++) {
            long latency = cache.read(i * PAGE_SIZE * 10, PAGE_SIZE);
            if (i < 5 || i >= 45) {
                System.out.printf("  读取 offset=%d: %s%n",
                        i * PAGE_SIZE * 10,
                        latency < 1_000_000 ? "Cache HIT (~" + latency + "ns)" : "Cache MISS (~" + latency / 1_000_000 + "ms)");
            } else if (i == 5) {
                System.out.println("  ... (省略中间结果) ...");
            }
        }
        System.out.printf("  缓存命中率: %.1f%% | 缓存页数: %d%n",
                cache.hitRate() * 100, cache.cachePages());

        System.out.println("\n--- 随机读取（低命中率）---");
        PageCacheSimulator cache2 = new PageCacheSimulator(50);
        Random rand = new Random(42);
        for (int i = 0; i < 30; i++) {
            long offset = (long) rand.nextInt(2000) * PAGE_SIZE;
            cache2.read(offset, PAGE_SIZE);
        }
        System.out.printf("  随机读取 命中率: %.1f%%%n", cache2.hitRate() * 100);

        System.out.println("\n--- 写入 + 刷盘策略 ---");
        PageCacheSimulator cache3 = new PageCacheSimulator(50);
        long writeLatency = 0;
        for (int i = 0; i < 100; i++) {
            writeLatency = cache3.write(i * PAGE_SIZE, PAGE_SIZE);
        }
        System.out.printf("  写入 100 页: 每页 ~%dns (PageCache 写入)%n", writeLatency);
        System.out.printf("  脏页数: %d%n", cache3.dirtyPages());

        System.out.println("\n  触发 flush（模拟 fsync）...");
        long flushLatency = cache3.flush();
        System.out.printf("  刷盘耗时: ~%dms%n", flushLatency / 1_000_000);
        System.out.printf("  刷盘后脏页数: %d%n", cache3.dirtyPages());

        System.out.println("\n💡 Kafka 重度依赖 PageCache：读命中率 > 90% 时性能接近内存");
        System.out.println("   Producer 写入 PageCache 即返回，Broker 异步刷盘保证吞吐");
    }
}
