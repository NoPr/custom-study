package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】日志清理策略 —— compact·delete·Key 压缩保留
 * 【描述】模拟 Kafka 日志清理策略。delete（按时间/大小删除旧消息）和
 *         compact（相同 Key 只保留最新 Value，适合 changelog 场景）。
 *         演示两种策略下消息的保留与删除规则。
 * 【关键概念】日志清理、log.cleanup.policy、compact、delete、Key 压缩、
 *             retention.ms、segment.bytes
 * 【关联类】@see com.nopr.mq.kafka.simulate.TombstoneDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class LogCleanupDemo {

    record LogMessage(long offset, String key, String value, long timestamp) {}

    static class SimpleLog {
        private final List<LogMessage> messages = new ArrayList<>();

        void append(LogMessage msg) { messages.add(msg); }

        List<LogMessage> cleanupByRetention(long retentionMs) {
            long cutoff = System.currentTimeMillis() - retentionMs;
            List<LogMessage> removed = new ArrayList<>();
            messages.removeIf(msg -> {
                if (msg.timestamp() < cutoff) { removed.add(msg); return true; }
                return false;
            });
            return removed;
        }

        List<LogMessage> cleanupByCompact() {
            Map<String, LogMessage> latestByKey = new LinkedHashMap<>();
            for (LogMessage msg : messages) {
                if (msg.value() == null && latestByKey.containsKey(msg.key())) {
                    latestByKey.remove(msg.key());
                } else {
                    latestByKey.put(msg.key(), msg);
                }
            }
            messages.clear();
            messages.addAll(latestByKey.values());
            return new ArrayList<>(latestByKey.values());
        }

        List<LogMessage> all() { return Collections.unmodifiableList(messages); }
        int size() { return messages.size(); }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 日志清理策略 Demo");
        System.out.println("=".repeat(60));

        deleteCleanupDemo();
        compactCleanupDemo();
    }

    static void deleteCleanupDemo() {
        System.out.println("\n--- delete 策略：按保留时间清理 ---");

        SimpleLog log = new SimpleLog();
        long now = System.currentTimeMillis();

        log.append(new LogMessage(0, "order-1", "created", now - 7_200_000));
        log.append(new LogMessage(1, "order-2", "created", now - 3_600_000));
        log.append(new LogMessage(2, "order-3", "shipped", now - 600_000));
        log.append(new LogMessage(3, "order-4", "delivered", now));

        System.out.println("清理前消息数: " + log.size());

        long retentionMs = 3_600_000;
        List<LogMessage> removed = log.cleanupByRetention(retentionMs);

        System.out.println("清理掉 " + removed.size() + " 条（时间 > 1小时前的）");
        System.out.println("清理后消息数: " + log.size());
    }

    static void compactCleanupDemo() {
        System.out.println("\n--- compact 策略：相同 Key 只保留最新 ---");

        SimpleLog log = new SimpleLog();
        long now = System.currentTimeMillis();

        log.append(new LogMessage(0, "user-1", "张三", now - 10_000));
        log.append(new LogMessage(1, "user-2", "李四", now - 8_000));
        log.append(new LogMessage(2, "user-1", "张三丰", now - 5_000));
        log.append(new LogMessage(3, "user-3", "王五", now - 3_000));
        log.append(new LogMessage(4, "user-2", "李四光", now - 1_000));
        log.append(new LogMessage(5, "user-1", null, now));

        System.out.println("清理前消息数: " + log.size());
        for (LogMessage m : log.all()) {
            System.out.printf("  offset=%d key=%s value=%s%n",
                    m.offset(), m.key(), m.value());
        }

        log.cleanupByCompact();

        System.out.println("清理后消息数: " + log.size());
        for (LogMessage m : log.all()) {
            System.out.printf("  offset=%d key=%s value=%s%n",
                    m.offset(), m.key(), m.value());
        }
        System.out.println("\n\uD83D\uDCA1 compact 用于 CDC/changelog 场景，保序 + Key 版本压缩");
    }
}
