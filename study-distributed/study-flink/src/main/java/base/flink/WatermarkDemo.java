package base.flink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Flink 水位线原理：EventTime vs ProcessingTime + BoundedOutOfOrderness(允许乱序延迟3s)
 * + Watermark 生成(定时注入+IdleSource处理) + 多流 Watermark 合并取最小。
 *
 * <p>核心考点：
 * <ul>
 *   <li><b>EventTime</b>：数据本身的时间戳，处理乱序需 Watermark</li>
 *   <li><b>ProcessingTime</b>：机器处理数据的时间，无乱序概念但结果不确定</li>
 *   <li><b>BoundedOutOfOrderness</b>：允许最大乱序延迟，watermark = maxTimestamp - outOfOrderness</li>
 *   <li><b>Watermark 生成</b>：Source 定时注入(Periodic)/每条数据注入(Punctuated)</li>
 *   <li><b>IdleSource</b>：某分区长时间无数据，标记该分区的 watermark 不参与合并</li>
 *   <li><b>多流 Watermark 合并</b>：Operator 取所有上游最小的 watermark 作为当前 watermark</li>
 * </ul>
 *
 * @author study-tuling
 */
public class WatermarkDemo {

    /* ======================== EventTime vs ProcessingTime ======================== */

    /** 事件：携带 EventTime */
    record Event(String key, long eventTime, int value) {
    }

    /** ProcessingTime 处理记录 */
    record ProcessingRecord(String key, long eventTime, long processingTime, int value) {
    }

    /* ======================== Watermark 生成器 ======================== */

    /**
     * Watermark 生成器接口。
     * <p>BoundedOutOfOrderness：watermark = maxTimestamp - outOfOrdernessMs
     * <p>当 watermark 推进到窗口结束时间，触发窗口计算。
     */
    interface WatermarkGenerator {
        /** 每条数据到达时更新 maxTimestamp */
        void onEvent(long eventTimestamp);

        /** 定时(200ms)生成 watermark */
        long getCurrentWatermark();
    }

    /**
     * BoundedOutOfOrdernessWatermark：允许最大乱序延迟 3s。
     * <p>watermark = maxTimestamp - 3000ms
     * <p>含义：告诉窗口"早于 watermark-3s 的数据都已到达"。
     */
    static class BoundedOutOfOrdernessWatermark implements WatermarkGenerator {
        /** 允许的最大乱序延迟 */
        private final long outOfOrdernessMs;
        /** 已处理数据的最大时间戳 */
        private final AtomicLong maxTimestamp = new AtomicLong(Long.MIN_VALUE + 1);

        BoundedOutOfOrdernessWatermark(long outOfOrdernessMs) {
            this.outOfOrdernessMs = outOfOrdernessMs;
        }

        @Override
        public void onEvent(long eventTimestamp) {
            // CAS 更新最大时间戳
            maxTimestamp.accumulateAndGet(eventTimestamp, Math::max);
        }

        @Override
        public long getCurrentWatermark() {
            return maxTimestamp.get() - outOfOrdernessMs;
        }
    }

    /* ======================== IdleSource 处理 ======================== */

    /**
     * IdleSource 处理器：某分区长时间无数据时标记为空闲。
     * <p>被标记为 Idle 的 Source 不再参与 watermark 合并，避免单个分区拖慢全局 watermark。
     */
    static class IdleSourceTracker {
        /** 分区最后活跃时间 */
        private final Map<Integer, Long> partitionLastActivity = new ConcurrentHashMap<>();
        /** 空闲超时 ms */
        private final long idleTimeoutMs;

        IdleSourceTracker(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
        }

        /**
         * 记录分区活跃
         * @param partition 分区索引
         * @param currentTimeMs 当前时间戳
         */
        void recordActivity(int partition, long currentTimeMs) {
            partitionLastActivity.merge(partition, currentTimeMs, Math::max);
        }

        /**
         * 检查分区是否空闲（超时无数据）
         * @param partition 分区索引
         * @param currentTimeMs 当前时间戳
         * @return true=空闲，不参与 watermark 合并
         */
        boolean isIdle(int partition, long currentTimeMs) {
            Long lastActive = partitionLastActivity.get(partition);
            if (lastActive == null) return true;
            return (currentTimeMs - lastActive) > idleTimeoutMs;
        }

        /** 获取活跃分区列表 */
        Set<Integer> getActivePartitions(long currentTimeMs) {
            Set<Integer> active = new HashSet<>();
            for (Map.Entry<Integer, Long> entry : partitionLastActivity.entrySet()) {
                if ((currentTimeMs - entry.getValue()) <= idleTimeoutMs) {
                    active.add(entry.getKey());
                }
            }
            return active;
        }
    }

    /* ======================== 多流 Watermark 合并 ======================== */

    /**
     * 多流 Watermark 合并器：Operator 取所有上游最小 watermark。
     * <p>公式：combinedWatermark = min(watermark1, watermark2, ..., watermarkN)
     * <p>原理：下游窗口只有在所有上游 watermark 都推进后才触发计算，
     *   保证任何仍低于该 watermark 的乱序数据不会被窗口丢弃。
     */
    static class WatermarkCombiner {
        /** 上游 name → watermark */
        private final Map<String, Long> upstreamWatermarks = new ConcurrentHashMap<>();

        void updateWatermark(String upstreamName, long watermark) {
            upstreamWatermarks.put(upstreamName, watermark);
        }

        /** 合并所有上游取最小 */
        long getCombinedWatermark() {
            if (upstreamWatermarks.isEmpty()) return Long.MIN_VALUE;
            return upstreamWatermarks.values().stream()
                    .min(Long::compare)
                    .orElse(Long.MIN_VALUE);
        }

        /** 排除某个上游后合并 */
        long getCombinedWatermarkExcluding(String excluded) {
            return upstreamWatermarks.entrySet().stream()
                    .filter(e -> !e.getKey().equals(excluded))
                    .map(Map.Entry::getValue)
                    .min(Long::compare)
                    .orElse(Long.MIN_VALUE);
        }
    }

    /* ======================== 演示逻辑 ======================== */

    static void rdEventTimeVsProcessingTime() {
        System.out.println("========== EventTime vs ProcessingTime ==========\n");

        System.out.println("EventTime (数据时间戳):");
        System.out.println("  - 窗口: [00:00, 00:05) [00:05, 00:10)");
        System.out.println("  - Watermark: 允许 3s 乱序 → watermark = maxEventTime - 3s");
        System.out.println("  - 优点: 结果确定性，不受机器时钟影响，适合乱序+延迟场景");
        System.out.println("  - 缺点: 需要 Watermark，延迟数据可能被丢弃");
        System.out.println();

        System.out.println("ProcessingTime (机器时间):");
        System.out.println("  - 窗口: 以系统时钟为基准");
        System.out.println("  - 触发器: 系统时间到达窗口结束 → 立即触发");
        System.out.println("  - 优点: 极低延迟，不需要 Watermark，无乱序顾虑");
        System.out.println("  - 缺点: 结果不确定性（取决于数据到达时间而非事件发生时间）");
        System.out.println();

        // 模拟事件到达时间 vs 事件发生时间
        record DelayedEvent(long eventTime, long processingTime) {}

        List<DelayedEvent> timeline = List.of(
                new DelayedEvent(1000L, 1000L),   // 准时
                new DelayedEvent(2000L, 2100L),   // 轻微延迟
                new DelayedEvent(3000L, 8000L)    // 网络延迟 5s
        );

        System.out.printf("  %-12s | %-14s | %-14s%n", "事件时间", "到达时间", "延迟");
        for (DelayedEvent e : timeline) {
            long delay = e.processingTime - e.eventTime;
            System.out.printf("  t=%-10d | t=%-12d | %dms%n",
                    e.eventTime, e.processingTime, delay);
        }
        System.out.println("\n  结论: EventTime 保证语义正确, Watermark 容忍乱序\n");
    }

    static void rdBoundedOutOfOrdernessDemo() {
        System.out.println("========== BoundedOutOfOrderness Watermark ==========\n");

        long outOfOrdernessMs = 3_000L;
        BoundedOutOfOrdernessWatermark generator = new BoundedOutOfOrdernessWatermark(outOfOrdernessMs);

        // 模拟乱序到达的事件（时间戳故意打乱）
        List<Long> eventTimestamps = List.of(1000L, 5000L, 2000L, 8000L, 3000L, 6000L, 10000L);

        System.out.printf("允许乱序延迟: %dms%n", outOfOrdernessMs);
        System.out.printf("  %-12s | %-16s | %s%n", "事件时间", "watermark", "备注");
        System.out.println("  ────────────────────────────────────────────");

        for (long eventTs : eventTimestamps) {
            generator.onEvent(eventTs);
            long wm = generator.getCurrentWatermark();
            String note = wm >= eventTs ? "✅ 未超过" : (eventTs - wm > outOfOrdernessMs + 1000)
                    ? "⚠ 可能触发窗口计算" : "";
            System.out.printf("  t=%-10s | watermark=%-8d | %s%n",
                    formatTimestamp(eventTs), wm, note);
        }

        System.out.printf("%n  最终 watermark = %d (%s)%n",
                generator.getCurrentWatermark(),
                formatTimestamp(generator.getCurrentWatermark()));
        System.out.printf("  含义: 时间戳 < %d 的数据已全部到达 (或作为延迟数据丢弃)%n",
                generator.getCurrentWatermark());
        System.out.println("  窗口: [0,5000) 的 end=5000 < watermark=7000 → 触发计算\n");
    }

    static void rdMultiStreamWatermarkMerge() {
        System.out.println("========== 多流 Watermark 合并取最小 ==========\n");

        WatermarkCombiner combiner = new WatermarkCombiner();

        // 模拟3个上游 kafka 分区
        List<Runnable> partitions = List.of(
                () -> {
                    // Partition-0 活跃, timestamp 10000
                    combiner.updateWatermark("Kafka-P0", 10000L);
                    System.out.println("  [Kafka-P0] watermark=10000");
                },
                () -> {
                    // Partition-1 活跃, timestamp 8000
                    combiner.updateWatermark("Kafka-P1", 8000L);
                    System.out.println("  [Kafka-P1] watermark=8000");
                },
                () -> {
                    // Partition-2 长时间无数据, watermark 停滞在 3000
                    combiner.updateWatermark("Kafka-P2", 3000L);
                    System.out.println("  [Kafka-P2] watermark=3000 (停滞)");
                }
        );

        partitions.forEach(Runnable::run);

        long combinedBefore = combiner.getCombinedWatermark();
        System.out.printf("\n  全部3流合并: %d%n", combinedBefore);
        System.out.printf("   → P2 停滞, 拖慢全局 Watermark! 窗口永远不会触发%n%n");

        // 排除 P2 (标记为 IdleSource)
        long combinedExcluding = combiner.getCombinedWatermarkExcluding("Kafka-P2");
        System.out.printf("  排除 P2(Idle): %d%n", combinedExcluding);
        System.out.println("   → IdleSource 处理后 P2 不再参与合并, watermark 正常推进");
        System.out.println("\n  Watermark 合并公式: up1=9000, up2=7000, up3=11000 → combined=min=7000\n");
    }

    static void rdIdleSourceHandling() {
        System.out.println("========== IdleSource 空闲分区处理 ==========\n");

        IdleSourceTracker tracker = new IdleSourceTracker(3_000L);

        // 模拟 3 个 Kafka 分区
        long now = System.currentTimeMillis();
        tracker.recordActivity(0, now);       // P0 活跃
        tracker.recordActivity(1, now - 2000); // P1 活跃
        // P2 已超过 3 秒无数据 → 空闲

        System.out.printf("超时阈值: %dms%n", tracker.idleTimeoutMs);
        System.out.printf("  Partition-0: 最后活跃=%dms ago → %s%n",
                now - (tracker.partitionLastActivity.getOrDefault(0, 0L)),
                tracker.isIdle(0, now) ? "Idle 🔴" : "Active ✅");
        System.out.printf("  Partition-1: 最后活跃=%dms ago → %s%n",
                now - (tracker.partitionLastActivity.getOrDefault(1, 0L)),
                tracker.isIdle(1, now) ? "Idle 🔴" : "Active ✅");
        System.out.printf("  Partition-2: 无数据记录 → Idle 🔴%n");

        Set<Integer> active = tracker.getActivePartitions(now);
        System.out.printf("%n  活跃分区: %s%n", active);
        System.out.println("  → P2 被标记为 Idle, 不参与 Watermark 合并");
        System.out.println("  → Flink 自动检测: 通过 SourceFunction.SourceContext.markAsTemporarilyIdle()\n");
    }

    static String formatTimestamp(long ts) {
        return ts == Long.MIN_VALUE + 1 ? "(无数据)" : String.format("%dms", ts);
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        rdEventTimeVsProcessingTime();
        rdBoundedOutOfOrdernessDemo();
        rdMultiStreamWatermarkMerge();
        rdIdleSourceHandling();

        System.out.println("========== 演示完毕 ==========");
    }
}