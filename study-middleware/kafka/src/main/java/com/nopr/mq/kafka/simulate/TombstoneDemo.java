package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】墓碑日志 —— value=null·延迟删除·compact 联动
 * 【描述】模拟 Kafka 墓碑消息机制：发送 value=null 的消息标记待删除 Key，
 *         在 compact 策略下分两阶段执行（标记→延迟→真删），演示墓碑消息的
 *         生命周期和与 compact 策略的联动。
 * 【关键概念】墓碑消息、tombstone、value=null、延迟删除、log.cleanup.policy=compact、
 *             delete.retention.ms、CDC 软删除
 * 【关联类】@see com.nopr.mq.kafka.simulate.LogCleanupDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class TombstoneDemo {

    record TombstoneMessage(long offset, String key, String value, long timestamp, boolean isTombstone) {
        static TombstoneMessage normal(long offset, String key, String value, long ts) {
            return new TombstoneMessage(offset, key, value, ts, false);
        }
        static TombstoneMessage tombstone(long offset, String key, long ts) {
            return new TombstoneMessage(offset, key, null, ts, true);
        }
    }

    static class TombstoneBroker {
        private final List<TombstoneMessage> log = new ArrayList<>();
        private long offset = 0;
        private final long tombstoneRetentionMs;

        TombstoneBroker(long tombstoneRetentionMs) {
            this.tombstoneRetentionMs = tombstoneRetentionMs;
        }

        void append(String key, String value) {
            log.add(TombstoneMessage.normal(offset++, key, value, System.currentTimeMillis()));
        }

        void markDeleted(String key) {
            log.add(TombstoneMessage.tombstone(offset++, key, System.currentTimeMillis()));
        }

        void compact(long now) {
            Map<String, TombstoneMessage> latest = new LinkedHashMap<>();
            for (TombstoneMessage msg : log) {
                if (msg.isTombstone()) {
                    long age = now - msg.timestamp();
                    if (age >= tombstoneRetentionMs) {
                        latest.remove(msg.key());
                    } else {
                        latest.put(msg.key(), msg);
                    }
                } else {
                    latest.put(msg.key(), msg);
                }
            }
            log.clear();
            log.addAll(latest.values());
        }

        void printState(String label) {
            System.out.println("  [" + label + "] 共 " + log.size() + " 条:");
            for (TombstoneMessage m : log) {
                String marker = m.isTombstone() ? "\uD83D\uDC80TOMBSTONE" : "  NORMAL  ";
                System.out.printf("    offset=%d key=%-8s value=%-12s %s%n",
                        m.offset(), m.key(),
                        m.value() == null ? "(null)" : m.value(), marker);
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 墓碑日志 Demo");
        System.out.println("=".repeat(60));

        TombstoneBroker broker = new TombstoneBroker(5000);

        broker.append("user-A", "alice@ex.com");
        broker.append("user-B", "bob@ex.com");
        broker.append("user-A", "alice_new@ex.com");
        broker.printState("阶段1: 初始消息");

        broker.markDeleted("user-A");
        broker.printState("阶段2: 标记删除 user-A");

        broker.compact(System.currentTimeMillis());
        broker.printState("阶段3: 立即compact（墓碑保留）");

        System.out.println("\n  \u23F3 等待墓碑过期（6秒）...");
        try { Thread.sleep(6000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        broker.compact(System.currentTimeMillis());
        broker.printState("阶段4: 墓碑过期后compact（user-A 真删）");

        System.out.println("\n\uD83D\uDCA1 墓碑消息保障最终一致性，确保分布式系统中 Key 真正被删除");
    }
}
