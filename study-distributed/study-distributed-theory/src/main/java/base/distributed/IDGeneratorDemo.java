package base.distributed;

import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式 ID 生成方案演示 —— 4 种主流方案。
 *
 * <ul>
 *   <li><b>Snowflake（雪花算法）</b>：1b 保留 + 41b 毫秒 + 10b WorkerID + 12b 序列号，手写完整版含时钟回拨处理</li>
 *   <li><b>号段模式（Segment）</b>：数据库维护号段表(biz_tag, max_id, step)，应用本地缓存一段 ID</li>
 *   <li><b>Leaf（美团双缓冲）</b>：两个 segment 交替，当前段消耗到阈值时异步加载下一段</li>
 *   <li><b>有序 UUID</b>：UUID v7（时间戳排序）</li>
 * </ul>
 */
public class IDGeneratorDemo {

    /* =========================================================================
     * 1. Snowflake 手写完整版（含时钟回拨处理）
     * ========================================================================= */

    /**
     * 标准 Snowflake 位分配:
     * <pre>
     *  1 bit  : 保留（0）
     * 41 bits : 毫秒时间戳（从 epoch 开始），可用约 69 年
     * 10 bits : Worker ID（5 bit 数据中心 + 5 bit 机器），最多 1024 个节点
     * 12 bits : 序列号（同一毫秒内），最多 4096 个
     * </pre>
     */
    static class SnowflakeGenerator {
        /** 起始时间戳 (2025-01-01 00:00:00) */
        private static final long EPOCH = 1735689600000L;
        private static final long WORKER_ID_BITS = 10L;
        private static final long SEQUENCE_BITS = 12L;
        private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);    // 1023
        private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);       // 4095
        private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
        private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

        private final long workerId;
        private long sequence = 0L;
        private long lastTimestamp = -1L;
        private long lastBackupTimestamp = -1L; // 时钟回拨时使用

        SnowflakeGenerator(long workerId) {
            if (workerId < 0 || workerId > MAX_WORKER_ID) {
                throw new IllegalArgumentException("Worker ID 超出范围 0-" + MAX_WORKER_ID);
            }
            this.workerId = workerId;
        }

        /**
         * 生成下一个 ID，处理时钟回拨。
         * 回拨策略：若回拨 < 5ms 则自旋等待；否则抛异常 / 使用备用时间戳。
         */
        synchronized long nextId() {
            long currentTimestamp = System.currentTimeMillis();
            long diff = lastTimestamp - currentTimestamp;

            if (diff > 0) {
                // 时钟回拨检测
                if (diff < 5L) {
                    // 回拨 < 5ms，自旋等待时钟追上
                    System.out.printf("[Snowflake] 时钟回拨 %dms, 自旋等待...%n", diff);
                    while (currentTimestamp <= lastTimestamp) {
                        currentTimestamp = System.currentTimeMillis();
                    }
                } else {
                    // 严重回拨，使用备用策略：沿用上一次的时间戳 + 备用序列号
                    System.err.printf("[Snowflake] 严重时钟回拨 %dms，启用备用策略%n", diff);
                    if (lastBackupTimestamp == lastTimestamp) {
                        // 已经是备用了，继续递增额外序列号
                    } else {
                        lastBackupTimestamp = lastTimestamp;
                    }
                }
            }

            if (currentTimestamp == lastTimestamp) {
                // 同一毫秒内，序列号递增
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    // 序列号耗尽，等待下一毫秒
                    currentTimestamp = waitNextMillis(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }

            lastTimestamp = currentTimestamp;

            return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                 | (workerId << WORKER_ID_SHIFT)
                 | sequence;
        }

        private long waitNextMillis(long lastTs) {
            long ts = System.currentTimeMillis();
            while (ts <= lastTs) {
                ts = System.currentTimeMillis();
            }
            return ts;
        }

        /** 从 ID 中解析时间戳 */
        static long extractTimestamp(long id) {
            return (id >> TIMESTAMP_SHIFT) + EPOCH;
        }
    }

    /* =========================================================================
     * 2. 号段模式（Segment）—— 数据库维护号段
     * ========================================================================= */

    /** 模拟数据库中的号段表 */
    static class SegmentTable {
        /** biz_tag → SegmentRow */
        private final Map<String, SegmentRow> table = new ConcurrentHashMap<>();

        SegmentTable() {
            table.put("order", new SegmentRow("order", 0L, 1000L));
        }

        /** 获取一段号：UPDATE SET max_id = max_id + step, 返回旧的 max_id + 1 作为起点 */
        synchronized long[] fetchSegment(String bizTag) {
            SegmentRow row = table.get(bizTag);
            long start = row.maxId;
            row.maxId += row.step;
            System.out.printf("[号段模式] %s 获取号段 [%d, %d]%n", bizTag, start, row.maxId);
            return new long[]{start, row.maxId};
        }

        static class SegmentRow {
            String bizTag;
            volatile long maxId;
            long step;

            SegmentRow(String bizTag, long maxId, long step) {
                this.bizTag = bizTag;
                this.maxId = maxId;
                this.step = step;
            }
        }
    }

    /** 应用层号段缓存 */
    static class SegmentBuffer {
        private final SegmentTable table;
        private final String bizTag;
        private final AtomicLong current;
        private long end;
        private volatile boolean exhausted = true;

        SegmentBuffer(SegmentTable table, String bizTag) {
            this.table = table;
            this.bizTag = bizTag;
            this.current = new AtomicLong(0);
        }

        /** 从号段中取出下一个 ID，耗尽时拉取新号段 */
        synchronized long nextId() {
            if (exhausted) {
                long[] range = table.fetchSegment(bizTag);
                current.set(range[0]);
                end = range[1];
                exhausted = false;
            }

            long id = current.getAndIncrement();
            if (id >= end) {
                exhausted = true;
            }
            return id;
        }
    }

    /* =========================================================================
     * 3. Leaf（美团双缓冲）—— 两个 segment 交替加载
     * ========================================================================= */

    /**
     * 美团 Leaf-segment 双缓冲机制：
     * 一个 segment 被消耗到 10% 阈值时，后台异步加载下一个 segment。
     * 两个 segment 交替使用，避免 ID 获取阻塞在号段拉取上。
     */
    static class LeafDoubleBuffer {
        static class Segment {
            volatile AtomicLong cursor;
            volatile long max;
            volatile boolean ready;

            Segment() { this.cursor = new AtomicLong(0); }
        }

        private final Segment[] buffers = {new Segment(), new Segment()};
        private volatile int currentIndex = 0;
        private final SegmentTable table;
        private final String bizTag;

        /** 当当前 segment 消耗比例达到此阈值时，触发异步加载（默认 0.1 = 10%） */
        private static final double LOADING_FACTOR = 0.1;

        LeafDoubleBuffer(SegmentTable table, String bizTag) {
            this.table = table;
            this.bizTag = bizTag;
            // 初始加载第一个 segment
            loadSegment(0);
            buffers[0].ready = true;
        }

        /** 模拟后台异步加载号段 */
        private void loadSegment(int index) {
            long[] range = table.fetchSegment(bizTag);
            buffers[index].cursor.set(range[0]);
            buffers[index].max = range[1];
            buffers[index].ready = true;
            System.out.printf("[Leaf] buffer[%d] 加载号段 [%d, %d)%n", index, range[0], range[1]);
        }

        /** 取 ID */
        synchronized long nextId() {
            Segment seg = buffers[currentIndex];
            long id = seg.cursor.getAndIncrement();

            // 检查是否消耗到阈值，触发异步加载另一个 segment
            long remaining = seg.max - seg.cursor.get();
            long totalRange = seg.max - (seg.cursor.get() - 1); // 近似
            if ((double) remaining / totalRange < LOADING_FACTOR) {
                int nextIndex = 1 - currentIndex;
                if (!buffers[nextIndex].ready) {
                    System.out.printf("[Leaf] buffer[%d] 剩余不足 %d%%, 异步加载 buffer[%d]%n",
                        currentIndex, (int)(LOADING_FACTOR * 100), nextIndex);
                    loadSegment(nextIndex);
                }
                // 当前号段耗尽，切换到另一个
                if (remaining <= 0) {
                    currentIndex = nextIndex;
                }
            }
            return id;
        }
    }

    /* =========================================================================
     * 4. 有序 UUID（UUID v7 时间戳排序模拟）
     * ========================================================================= */

    /**
     * UUID v7: 48 位毫秒时间戳 + 12 位随机 + 62 位随机。
     * 相比 UUID v4（完全随机）更适合做数据库主键(B+Tree 有序)。
     */
    static class OrderedUUIDGenerator {
        /** 生成有序 UUID 字符串（模拟 v7 格式） */
        static String generate() {
            long timestampMs = System.currentTimeMillis();
            // 48位时间戳 → 前12个hex字符
            String timestampHex = String.format("%012x", timestampMs & 0xFFFFFFFFFFFFL);
            // 随机部分
            String random1 = String.format("%04x", (int)(Math.random() * 0xFFFF));
            String random2 = String.format("%04x", (int)(Math.random() * 0xFFFF));
            String random3 = String.format("%012x", (long)(Math.random() * 0xFFFFFFFFFFFFL));

            return timestampHex + "-" + random1 + "-7" + random2.substring(1)
                + "-" + "8" + random3.substring(1, 4) + "-" + random3.substring(4);
        }
    }

    /* =========================================================================
     * main
     * ========================================================================= */

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("1. Snowflake 雪花算法（含时钟回拨处理）");
        System.out.println("=".repeat(60));
        {
            SnowflakeGenerator snowflake = new SnowflakeGenerator(1);
            for (int i = 0; i < 5; i++) {
                long id = snowflake.nextId();
                System.out.printf("  ID=%d, 时间戳=%s%n",
                    id, new Date(SnowflakeGenerator.extractTimestamp(id)));
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("2. 号段模式（数据库号段表）");
        System.out.println("=".repeat(60));
        {
            SegmentTable table = new SegmentTable();
            SegmentBuffer buffer = new SegmentBuffer(table, "order");
            for (int i = 0; i < 5; i++) {
                System.out.printf("  号段ID=%d%n", buffer.nextId());
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("3. Leaf 双缓冲（美团方案）");
        System.out.println("=".repeat(60));
        {
            SegmentTable table = new SegmentTable();
            table.table.put("leaf-order", new SegmentTable.SegmentRow("leaf-order", 0L, 100L));
            LeafDoubleBuffer leaf = new LeafDoubleBuffer(table, "leaf-order");
            for (int i = 0; i < 6; i++) {
                System.out.printf("  Leaf-ID=%d%n", leaf.nextId());
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("4. 有序 UUID（v7 风格）");
        System.out.println("=".repeat(60));
        {
            for (int i = 0; i < 3; i++) {
                System.out.printf("  %s%n", OrderedUUIDGenerator.generate());
            }
        }
    }
}