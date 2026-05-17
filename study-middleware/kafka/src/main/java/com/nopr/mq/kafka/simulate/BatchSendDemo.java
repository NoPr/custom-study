package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】批量发送 —— linger.ms·batch.size·吞吐 vs 延迟
 * 【描述】模拟 Kafka Producer 批量发送机制：RecordAccumulator 按 partition 分组
 *         缓存消息，达到 batch.size 或超过 linger.ms 触发批量发送。
 *         演示不同参数组合下的吞吐 vs 延迟权衡。
 * 【关键概念】批量发送、linger.ms、batch.size、RecordAccumulator、吞吐、
 *             延迟、压缩、ProducerBatch、max.in.flight.requests.per.connection
 * 【关联类】@see com.nopr.mq.kafka.simulate.LowLatencyDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class BatchSendDemo {

    record Message(int partition, String key, String value) {}

    static class RecordAccumulator {
        private final int batchSize;
        private final Map<Integer, List<Message>> buffers = new HashMap<>();
        private final AtomicLong sendCount = new AtomicLong(0);
        private final AtomicLong msgCount = new AtomicLong(0);

        RecordAccumulator(int batchSize) { this.batchSize = batchSize; }

        void append(Message msg) {
            msgCount.incrementAndGet();
            buffers.computeIfAbsent(msg.partition(), k -> new ArrayList<>()).add(msg);

            if (buffers.get(msg.partition()).size() >= batchSize) {
                drain(msg.partition());
            }
        }

        void drain(int partition) {
            List<Message> batch = buffers.remove(partition);
            if (batch != null && !batch.isEmpty()) {
                sendCount.incrementAndGet();
                System.out.printf("  \uD83D\uDCE4 发送 batch: partition=%d, 共%d条消息%n",
                        partition, batch.size());
            }
        }

        void drainAll() {
            new ArrayList<>(buffers.keySet()).forEach(this::drain);
        }

        long totalSends() { return sendCount.get(); }
        long totalMessages() { return msgCount.get(); }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 批量发送 Demo");
        System.out.println("=".repeat(60));

        System.out.println("\n--- 场景1：batch.size=5（批量大，发送次数少）---");
        RecordAccumulator acc1 = new RecordAccumulator(5);
        for (int i = 0; i < 15; i++) {
            acc1.append(new Message(i % 3, "key-" + i, "value-" + i));
        }
        acc1.drainAll();
        System.out.printf("  总计 %d 条消息，%d 次网络请求%n",
                acc1.totalMessages(), acc1.totalSends());
        System.out.printf("  平均每次发送 %.1f 条消息%n",
                (double) acc1.totalMessages() / acc1.totalSends());

        System.out.println("\n--- 场景2：batch.size=1（逐条发送，发送次数多）---");
        RecordAccumulator acc2 = new RecordAccumulator(1);
        for (int i = 0; i < 15; i++) {
            acc2.append(new Message(i % 3, "key-" + i, "value-" + i));
        }
        acc2.drainAll();
        System.out.printf("  总计 %d 条消息，%d 次网络请求%n",
                acc2.totalMessages(), acc2.totalSends());
        System.out.printf("  平均每次发送 %.1f 条消息%n",
                (double) acc2.totalMessages() / acc2.totalSends());

        System.out.println("\n\uD83D\uDCA1 batch.size 越大 → 吞吐越高 | linger.ms 越大 → 延迟越高");
        System.out.println("   生产环境：batch.size=16KB~1MB，linger.ms=5~100ms");
    }
}
