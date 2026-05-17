package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】Kafka Leader 选举 —— 5 种 LeaderSelector + Controller 选举流程
 * 【描述】模拟 Kafka 源码 kafka.controller 包中的 5 种 LeaderSelector：
 *         NoOpLeaderSelector（元数据 Topic）、OfflinePartitionLeader（分区下线）、
 *         ReassignedPartitionLeader（重分配中选 Leader）、
 *         PreferredReplicaPartitionLeader（优先选首选副本）、
 *         ControlledShutdownLeader（优雅关闭迁移）。
 *         附带 Kafka Controller 选举流程（ZK 临时节点 vs KRaft Quorum）。
 * 【关键概念】LeaderSelector、PartitionLeaderElection、NoOp、Offline、
 *             Reassigned、PreferredReplica、ControlledShutdown、AR/ISR、
 *             auto.leader.rebalance、Preferred Replica
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *          @see com.nopr.mq.kafka.simulate.SplitBrainDemo
 *          @see com.nopr.mq.kafka.simulate.ElectionAlgorithmDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class KafkaLeaderSelectorDemo {

    /* ── 共享模型 ────────────────────────────────────────────── */
    static class Replica {
        int id;
        boolean isAlive;
        Replica(int id, boolean isAlive) { this.id = id; this.isAlive = isAlive; }
    }

    static class Partition {
        int id;
        List<Replica> replicas = new ArrayList<>();     // AR: Assigned Replicas
        List<Replica> isr = new ArrayList<>();           // ISR: In-Sync Replicas
        int leaderId = -1;
        int preferredReplicaId;

        Partition(int id) { this.id = id; }

        void setPreferred(int replicaId) { this.preferredReplicaId = replicaId; }

        void electLeader(String result) {
            if (result == null) {
                leaderId = -1;
                System.out.printf("  [Partition-%d] No Leader 选出%n", id);
            } else {
                leaderId = Integer.parseInt(result);
                System.out.printf("  [Partition-%d] \uD83D\uDC51 Leader=Replica-%d%n", id, leaderId);
            }
        }
    }

    /* ==========================================================
     *  Selector 1: NoOpLeaderSelector
     *  场景：元数据 Topic（__consumer_offsets / __transaction_state）
     *  不选 Leader，分区由内部管理器控制
     *  对应源码：NoOpLeaderSelector
     * ========================================================== */
    static String noOpSelector(Partition p) {
        System.out.println("  Type=NoOp → 元数据 Topic，不执行 Leader 选举（由内部管理器控制）");
        return null;
    }

    static void demo_noOp() {
        printSection("1. NoOpLeaderSelector — 不选 Leader");

        System.out.println("  使用场景: __consumer_offsets / __transaction_state 等内部 Topic");
        System.out.println("  这些 Topic 的分区 Leader 由 GroupCoordinator/TransactionCoordinator");
        System.out.println("  所在的 Broker 自动成为 Leader，不走通用选举流程。");
        System.out.println();

        Partition p = new Partition(0);
        p.replicas.add(new Replica(1, true));
        p.replicas.add(new Replica(2, true));
        p.electLeader(noOpSelector(p));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 源码位置: kafka.controller.NoOpLeaderSelector");
        System.out.println("     在 PartitionStateMachine 中，NoOp 的 Partition 跳过选举逻辑。");
    }

    /* ==========================================================
     *  Selector 2: OfflinePartitionLeader
     *  场景：分区 AR 中所有副本宕机 / ISR 为空
     *  无法选出 Leader → 分区进入 Offline 状态
     *  对应源码：OfflinePartitionLeaderSelector
     * ========================================================== */
    static String offlineSelector(Partition p) {
        long aliveInAR = p.replicas.stream().filter(r -> r.isAlive).count();
        if (aliveInAR == 0) {
            System.out.println("  Type=Offline → AR 中无存活副本，分区进入 Offline 状态");
            System.out.println("                需 Admin 手动干预（重启 Broker / reassign-partitions）");
            return null;
        }
        System.out.println("  AR 中还有存活副本，不应走 OfflineSelector");
        return null;
    }

    static void demo_offline() {
        printSection("2. OfflinePartitionLeader — 分区下线（无 Leader）");

        System.out.println("  触发条件: Partition AR (Assigned Replicas) 中所有 Broker 宕机");
        System.out.println("  结果: 分区无法选出 Leader，进入 Offline 状态");
        System.out.println("  恢复: Broker 重启后 Controller 自动重新选举");
        System.out.println();

        Partition p = new Partition(0);
        p.replicas.add(new Replica(1, false)); // 宕机
        p.replicas.add(new Replica(2, false));
        p.replicas.add(new Replica(3, false));
        p.isr.addAll(p.replicas);
        p.electLeader(offlineSelector(p));

        System.out.println();
        System.out.println("  \u26A0\uFE0F 面试: 线上出现 OfflinePartition → 检查 Broker 存活状态 →");
        System.out.println("    如果 unclean.leader.election.enable=true，ISR 空时也可能从非 ISR 选 Leader。");
    }

    /* ==========================================================
     *  Selector 3: ReassignedPartitionLeader
     *  场景：kafka-reassign-partitions.sh 执行分区重分配
     *  Controller 从新 AR 的第一个存活副本选 Leader
     *  对应源码：ReassignedPartitionLeaderSelector
     * ========================================================== */
    static String reassignedSelector(Partition p) {
        for (Replica r : p.replicas) {
            if (r.isAlive) {
                System.out.printf("  Type=Reassigned → 新 AR[0]=Replica-%d 存活 → 选为 Leader%n", r.id);
                return String.valueOf(r.id);
            }
        }
        System.out.println("  Type=Reassigned → 新 AR 中无存活副本");
        return null;
    }

    static void demo_reassigned() {
        printSection("3. ReassignedPartitionLeader — 分区重分配中选 Leader");

        System.out.println("  场景: Admin 执行 kafka-reassign-partitions.sh 重新分配分区副本");
        System.out.println("  结果: Controller 从新 AR (Assigned Replicas) 的第一个存活副本选 Leader");
        System.out.println();

        System.out.println("  原 AR: [1,2,3] → 新 AR: [4,5,6] （Broker-4,5,6 可能已在 ISR 也可能不在）");
        Partition p = new Partition(0);
        p.replicas.add(new Replica(4, true));
        p.replicas.add(new Replica(5, true));
        p.replicas.add(new Replica(6, false));
        p.electLeader(reassignedSelector(p));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 注意: Reassigned 场景下不要求新 Leader 在 ISR 中——");
        System.out.println("     只要活着的副本足够，重分配期间 Controller 直接指定 Leader。");
        System.out.println("     Follower 后续会追赶 Leader 加入 ISR。");
    }

    /* ==========================================================
     *  Selector 4: PreferredReplicaPartitionLeader
     *  场景：auto.leader.rebalance.enable=true 时自动均衡
     *       Leader 不在 Preferred Replica 上 → Controller 发起迁移
     *  对应源码：PreferredReplicaPartitionLeaderSelector
     * ========================================================== */
    static String preferredReplicaSelector(Partition p) {
        if (p.preferredReplicaId <= 0) {
            System.out.println("  Type=PreferredReplica → 无 Preferred，ISR 第一个作为 Leader");
            for (Replica r : p.isr) {
                if (r.isAlive) return String.valueOf(r.id);
            }
            return null;
        }
        for (Replica r : p.isr) {
            if (r.id == p.preferredReplicaId && r.isAlive) {
                System.out.printf("  Type=PreferredReplica → Preferred=Replica-%d 在 ISR 且存活 → \uD83D\uDC51%n", r.id);
                return String.valueOf(r.id);
            }
        }
        System.out.printf("  Type=PreferredReplica → Preferred=Replica-%d 不在 ISR/宕机 → ISR 中另选%n",
                p.preferredReplicaId);
        for (Replica r : p.isr) {
            if (r.isAlive) return String.valueOf(r.id);
        }
        return null;
    }

    static void demo_preferredReplica() {
        printSection("4. PreferredReplicaPartitionLeader — 优先选 Preferred Replica");

        System.out.println("  Preferred Replica: Partition 创建时 AR 的第一个副本");
        System.out.println("  意义: 让 Leader 分布回到初始均衡状态（每个 Broker 均匀承担 Leader）");
        System.out.println();

        System.out.println("  正常场景: Preferred=Replica-1 在 ISR 且存活");
        Partition p1 = new Partition(1);
        p1.replicas.add(new Replica(1, true));
        p1.replicas.add(new Replica(2, true));
        p1.isr.add(p1.replicas.get(0));
        p1.isr.add(p1.replicas.get(1));
        p1.setPreferred(1);
        p1.electLeader(preferredReplicaSelector(p1));

        System.out.println();
        System.out.println("  异常场景: Preferred=Replica-1 宕机，ISR=[2,3]，选 Replica-2");
        Partition p2 = new Partition(2);
        p2.replicas.add(new Replica(1, false));
        p2.replicas.add(new Replica(2, true));
        p2.replicas.add(new Replica(3, true));
        p2.isr.add(p2.replicas.get(1));
        p2.isr.add(p2.replicas.get(2));
        p2.setPreferred(1);
        p2.electLeader(preferredReplicaSelector(p2));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 生产建议: auto.leader.rebalance.enable=true（默认）→");
        System.out.println("     Controller 定期检查并迁移 Leader 到 Preferred Replica");
        System.out.println("     → Leader 分布均匀 → 各 Broker 负载均衡");
    }

    /* ==========================================================
     *  Selector 5: ControlledShutdownLeader
     *  场景：Broker 收到 SIGTERM（优雅关闭）
     *  Controller 将该 Broker 的 Leader 迁移到 ISR 中其他副本
     *  目标：分区零停机切换
     *  对应源码：ControlledShutdownLeaderSelector
     * ========================================================== */
    static String controlledShutdownSelector(Partition p, int shuttingDownBrokerId) {
        if (p.leaderId == shuttingDownBrokerId) {
            System.out.printf("  Type=ControlledShutdown → Leader=Replica-%d 在 Broker-%d 上 → 需要迁移%n",
                    p.leaderId, shuttingDownBrokerId);
            for (Replica r : p.isr) {
                if (r.id != shuttingDownBrokerId && r.isAlive) {
                    System.out.printf("    迁移到 ISR 中的 Replica-%d%n", r.id);
                    return String.valueOf(r.id);
                }
            }
            System.out.println("    \u26A0\uFE0F ISR 中无其他可用副本 → 分区将短暂不可用");
            return null;
        }
        System.out.printf("  Type=ControlledShutdown → Broker-%d 无 Leader 分区，无需迁移%n", shuttingDownBrokerId);
        return null;
    }

    static void demo_controlledShutdown() {
        printSection("5. ControlledShutdownLeader — 优雅关闭 Leader 迁移");

        System.out.println("  场景: 运维重启 Broker → kill -15 (SIGTERM) → Broker 通知 Controller");
        System.out.println("  Controller 收到 ControlledShutdown 请求后:");
        System.out.println("    ① 遍历该 Broker 上所有 Leader 分区");
        System.out.println("    ② 逐个从 ISR 中选新 Leader 迁移");
        System.out.println("    ③ 所有 Leader 迁移完成 → Broker 安全关闭");
        System.out.println();

        System.out.println("  模拟: Broker-1 优雅关闭，其上有 Partition-0 的 Leader");
        Partition p = new Partition(0);
        p.replicas.add(new Replica(1, true));
        p.replicas.add(new Replica(2, true));
        p.replicas.add(new Replica(3, true));
        p.isr.add(p.replicas.get(0));
        p.isr.add(p.replicas.get(1));
        p.isr.add(p.replicas.get(2));
        p.leaderId = 1; // 当前 Leader 在 Broker-1
        p.electLeader(controlledShutdownSelector(p, 1));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 ControlledShutdown vs 硬宕机:");
        System.out.println("    - 优雅关闭: Leader 逐分区迁移 → 服务不中断 → ~秒级完成");
        System.out.println("    - 硬宕机: Controller 检测 Broker 失联 → ISR 收缩 → 重新选 Leader → ~30s");
    }

    /* ==========================================================
     *  6. Controller 选举流程（附赠）
     * ========================================================== */
    static void controllerElection() {
        printSection("6. Kafka Controller 选举流程");

        System.out.println("  [ZK 模式] (Kafka < 3.3):");
        System.out.println("    1. 所有 Broker 竞争创建 ZK 临时节点 /controller");
        System.out.println("    2. 创建成功者 → Controller（ZK ZAB 协议保证唯一）");
        System.out.println("    3. Controller 在 ZK 注册 Watch → 监听 Broker/Partition 变更");
        System.out.println("    4. Controller 宕机 → 临时节点消失 → 其他 Broker 抢创建 → 新 Controller");
        System.out.println("    选举速度: ZK session timeout (默认 18s)");
        System.out.println();
        System.out.println("  [KRaft 模式] (Kafka ≥ 3.3):");
        System.out.println("    1. 控制器节点组成 Quorum (3/5 奇数) → Raft 选举 Leader");
        System.out.println("    2. Raft Leader = Active Controller（替代 ZK 临时节点）");
        System.out.println("    3. 元数据日志由 Raft 复制到所有 Quorum 节点");
        System.out.println("    4. Controller 宕机 → Raft 自动选新 Leader（秒级）");
        System.out.println();
        System.out.println("  \uD83D\uDCA1 对比: ZK 模式依赖外部 ZK → session_timeout 延迟高；");
        System.out.println("     KRaft 模式内建 Raft → 故障恢复秒级 + 无需维护 ZK 集群");
    }

    /* ==========================================================
     *  总结与面试 Q&A
     * ========================================================== */
    static void summary() {
        printSection("7. 5 种 LeaderSelector 对比");

        System.out.printf("  %-28s %-18s %-20s%n", "Selector", "触发条件", "选 Leader 策略");
        System.out.println("  " + "-".repeat(66));
        printRow3("NoOpLeaderSelector", "元数据 Topic", "不参与选举");
        printRow3("OfflinePartitionLeader", "AR 全宕", "无法选 Leader");
        printRow3("ReassignedPartition", "kafka-reassign-partitions", "新 AR 第一个存活副本");
        printRow3("PreferredReplica", "auto.leader.rebalance", "优先选 AR[0]");
        printRow3("ControlledShutdown", "Broker SIGTERM", "ISR 中其他存活副本");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");

        System.out.println("  Q: Kafka 什么情况下分区会没有 Leader？");
        System.out.println("  A: ① AR 中所有副本宕机 → OfflinePartition");
        System.out.println("     ② ISR 为空且 unclean.leader.election.enable=false → 无 Leader");
        System.out.println("     ③ Controller 自身宕机中（KRaft 选主期间）→ 暂不可用");
        System.out.println();
        System.out.println("  Q: Preferred Replica 均衡是怎么触发的？");
        System.out.println("  A: auto.leader.rebalance.enable=true（默认）+");
        System.out.println("     leader.imbalance.check.interval.seconds=300（默认5分钟）");
        System.out.println("     → Controller 检查 Leader 不均衡比例 > leader.imbalance.per.broker.percentage=10%");
        System.out.println("     → 触发 PreferredReplicaPartitionLeader → 分批迁移");
        System.out.println();
        System.out.println("  Q: ControlledShutdown 失败会怎样？");
        System.out.println("  A: 超时后 Controller 视为硬宕机 → 从 ISR 重新选 Leader");
        System.out.println("     → Producer 短暂不可用（等待新 Leader 选出 + 元数据更新）→ ~30s");
    }

    /* ==========================================================
     *  main / 工具方法
     * ========================================================== */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka LeaderSelector 模拟 — 5 种选主策略");
        System.out.println("=".repeat(70));

        demo_noOp();
        demo_offline();
        demo_reassigned();
        demo_preferredReplica();
        demo_controlledShutdown();
        controllerElection();
        summary();
        interviewQA();
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n%n", "\u2500".repeat(66));
    }

    static void printRow3(String selector, String trigger, String strategy) {
        System.out.printf("  %-28s %-18s %-20s%n", selector, trigger, strategy);
    }
}