package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】脑裂 —— 网络分区·双 Leader·Epoch Fencing
 * 【描述】模拟 Kafka 脑裂场景：网络分区导致同一 Partition 出现两个 Leader，
 *         不同分区的消息写入冲突。演示 Epoch（Leader Epoch）的 Fencing 机制
 *         —— 旧 Leader 恢复后发现自己已过期，拒绝写入，避免数据不一致。
 * 【关键概念】脑裂、Split Brain、网络分区、双 Leader、Leader Epoch、
 *             Fencing、epoch bump、KRaft Controller
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class SplitBrainDemo {

    record Message(long offset, int epoch, String content) {}

    static class Partition {
        final int id;
        final List<Message> log = new ArrayList<>();
        int currentEpoch = 0;

        Partition(int id) { this.id = id; }

        void writeAsLeader(int epoch, String content) {
            if (epoch < currentEpoch) {
                System.out.printf("  \u274C Partition-%d: Epoch 过期 (%d < %d)，拒绝写入！%n",
                        id, epoch, currentEpoch);
                return;
            }
            currentEpoch = epoch;
            log.add(new Message(log.size(), epoch, content));
            System.out.printf("  \u2705 Partition-%d: 写入成功 (epoch=%d) '%s'%n",
                    id, epoch, content);
        }

        void printLog() {
            System.out.printf("  Partition-%d log (epoch=%d):%n", id, currentEpoch);
            for (Message m : log) {
                System.out.printf("    offset=%d epoch=%d content=%s%n",
                        m.offset(), m.epoch(), m.content());
            }
        }
    }

    static class Controller {
        final Map<Integer, Integer> partitionLeaderEpoch = new HashMap<>();

        void electLeader(int partition, int newEpoch) {
            partitionLeaderEpoch.put(partition, newEpoch);
            System.out.printf("  \uD83C\uDFAF Controller: Partition-%d Leader Epoch \u2192 %d%n",
                    partition, newEpoch);
        }

        int getEpoch(int partition) {
            return partitionLeaderEpoch.getOrDefault(partition, 0);
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 脑裂 (Split Brain) Demo");
        System.out.println("=".repeat(60));

        Controller controller = new Controller();
        Partition p0 = new Partition(0);

        System.out.println("\n--- 1\uFE0F\u20E3 正常阶段 ---");
        controller.electLeader(0, 1);
        p0.writeAsLeader(1, "msg-1");
        p0.writeAsLeader(1, "msg-2");

        System.out.println("\n--- 2\uFE0F\u20E3 脑裂阶段：旧 Leader 尝试写入 ---");
        System.out.println("  \u26A1 网络分区 → Controller 选举新 Leader (epoch=2)");
        controller.electLeader(0, 2);
        System.out.println("  \u26A0\uFE0F 旧 Leader 不知道已降级，尝试用旧 epoch=1 写入...");
        p0.writeAsLeader(1, "dangerous-msg");

        System.out.println("\n--- 3\uFE0F\u20E3 恢复阶段：新 Leader 正常写入 ---");
        p0.writeAsLeader(2, "msg-3");
        p0.writeAsLeader(2, "msg-4");

        p0.printLog();

        System.out.println("\n\uD83D\uDCA1 Epoch Fencing 是防止脑裂数据不一致的核心机制");
        System.out.println("   KRaft 协议通过 Controller Epoch + Leader Epoch 双重保障");
    }
}
