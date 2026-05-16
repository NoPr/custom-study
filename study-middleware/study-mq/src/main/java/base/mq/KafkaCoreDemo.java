package base.mq;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Kafka 核心概念：Topic/Partition/Broker/ISR/Leader-Follower/HW-LEO
 *
 * <p>ISR(In-Sync Replica) 机制：follower 落后阈值 replica.lag.time.max.ms。
 * HW(High Watermark) vs LEO(Log End Offset)：消费者可见范围。
 * Partition Leader 选举 + Controller Broker。
 *
 * <p>Kafka 集群架构：
 * <pre>
 * Zookeeper/KRaft → Controller (负责 Leader 选举、分区管理)
 *   └── Broker 0 (Leader P0) ── Broker 1 (Follower P0, Leader P1) ── Broker 2 (Follower P1)
 *
 * Partition 副本模型 (Replication Factor = 3):
 *   Leader (读写) ← ISR → Follower-1, Follower-2
 *   └── 落后于 replica.lag.time.max.ms (默认 30s) 的 Follower 被踢出 ISR
 *
 * HW (High Watermark):
 *   = ISR 中最小的 LEO
 *   消费者只能读取 HW 之前的消息
 *
 * LEO (Log End Offset):
 *   每个副本的下一条消息写入位置 = 最后一条消息 offset + 1
 * </pre>
 *
 * @author study-tuling
 */
public class KafkaCoreDemo {

    /* ======================== 数据模型 ======================== */

    /** 副本角色 */
    enum ReplicaRole {LEADER, FOLLOWER}

    /** 日志条目 */
    record LogEntry(long offset, String key, String value, long timestamp) {
    }

    /** Partition 副本 */
    static class Replica {
        final int brokerId;
        final ReplicaRole role;
        /** 日志段 */
        final List<LogEntry> log = new ArrayList<>();
        /** LEO: 下一条消息写入位置 */
        long leo = 0;
        /** HW: ISR 中最小的 LEO */
        long hw = 0;
        /** 最后同步时间 */
        volatile long lastCaughtUpTimeMs = System.currentTimeMillis();

        Replica(int brokerId, ReplicaRole role) {
            this.brokerId = brokerId;
            this.role = role;
        }

        /** 追加消息 */
        void append(LogEntry entry) {
            log.add(entry);
            leo = entry.offset + 1;
        }

        /** 追赶 Leader (Follower fetch) */
        void catchUp(long newLeo, long newHw) {
            this.leo = newLeo;
            this.hw = newHw;
            this.lastCaughtUpTimeMs = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("Replica{broker=%d, role=%s, leo=%d, hw=%d}",
                    brokerId, role, leo, hw);
        }
    }

    /** Partition */
    static class Partition {
        final String topic;
        final int partitionId;
        /** 副本列表: index0=Leader */
        final List<Replica> replicas;
        /** ISR: 与 Leader 保持同步的副本集合 (brokerId) */
        final Set<Integer> isr = new LinkedHashSet<>();
        /** Leader epoch 版本号 */
        int leaderEpoch = 0;

        Partition(String topic, int partitionId, int... brokerIds) {
            this.topic = topic;
            this.partitionId = partitionId;
            this.replicas = new ArrayList<>();
            for (int i = 0; i < brokerIds.length; i++) {
                ReplicaRole role = (i == 0) ? ReplicaRole.LEADER : ReplicaRole.FOLLOWER;
                Replica replica = new Replica(brokerIds[i], role);
                this.replicas.add(replica);
                this.isr.add(brokerIds[i]);
            }
        }

        /** 获取 Leader 副本 */
        Replica leader() {
            return replicas.get(0);
        }

        /** 获取 Follower 副本列表 */
        List<Replica> followers() {
            return replicas.stream()
                    .filter(r -> r.role == ReplicaRole.FOLLOWER)
                    .collect(Collectors.toList());
        }

        /** 推进 HW: HW = min(ISR 所有副本的 LEO) */
        long updateHW() {
            long minLeo = replicas.stream()
                    .filter(r -> isr.contains(r.brokerId))
                    .mapToLong(r -> r.leo)
                    .min()
                    .orElse(leader().leo);
            leader().hw = minLeo;
            return minLeo;
        }

        /** 检查 Follower 是否落后 (超过 lag 阈值踢出 ISR) */
        void checkISR(long lagMaxMs) {
            long now = System.currentTimeMillis();
            Iterator<Integer> it = isr.iterator();
            while (it.hasNext()) {
                int brokerId = it.next();
                if (brokerId == leader().brokerId) continue;
                Replica follower = replicas.stream()
                        .filter(r -> r.brokerId == brokerId)
                        .findFirst().orElse(null);
                if (follower != null && (now - follower.lastCaughtUpTimeMs) > lagMaxMs) {
                    it.remove();
                    System.out.printf("  [ISR] Broker-%d 落后 %dms, 踢出 ISR%n",
                            brokerId, now - follower.lastCaughtUpTimeMs);
                }
            }
        }
    }

    /** Controller Broker：负责 Leader 选举 */
    static class Controller {
        final int controllerBrokerId;

        Controller(int controllerBrokerId) {
            this.controllerBrokerId = controllerBrokerId;
        }

        /** Leader 宕机 → 从 ISR 中选新 Leader */
        Replica electLeader(Partition partition, Set<Integer> isr) {
            System.out.printf("  [Controller] Leader 选举: topic=%s, partition=%d, ISR=%s%n",
                    partition.topic, partition.partitionId, isr);
            // 策略: AR(Assigned Replicas) 中第一个在 ISR 中的副本
            for (Replica replica : partition.replicas) {
                if (isr.contains(replica.brokerId)) {
                    System.out.printf("  [Controller] 新 Leader: Broker-%d%n", replica.brokerId);
                    return replica;
                }
            }
            throw new IllegalStateException("无可用 ISR 副本!");
        }
    }

    /* ======================== 消息生产消费模拟 ======================== */

    static class KafkaProducer {
        final AtomicLong offsetGenerator = new AtomicLong(0);

        /** 发送消息到 Partition Leader */
        LogEntry send(Partition partition, String key, String value) {
            long offset = offsetGenerator.getAndIncrement();
            LogEntry entry = new LogEntry(offset, key, value, System.currentTimeMillis());
            partition.leader().append(entry);
            System.out.printf("  [Producer] 发送: partition=%d, offset=%d, key=%s, value=%s%n",
                    partition.partitionId, offset, key, value);
            return entry;
        }

        /** 模拟 Follower 从 Leader 同步 (Fetch 请求) */
        void followerFetch(Partition partition) {
            Replica leader = partition.leader();
            for (Replica follower : partition.followers()) {
                if (follower.leo < leader.leo) {
                    follower.catchUp(leader.leo, leader.hw);
                    System.out.printf("  [Follower] Broker-%d 追赶 Leader: leo=%d, hw=%d%n",
                            follower.brokerId, follower.leo, follower.hw);
                }
            }
            // 推进 HW
            long newHw = partition.updateHW();
            System.out.printf("  [HW] 推进: HW=%d%n", newHw);
        }
    }

    static class KafkaConsumer {
        final String groupId;

        KafkaConsumer(String groupId) {
            this.groupId = groupId;
        }

        /** 拉取 HW 之前的消息 */
        List<LogEntry> poll(Partition partition, long fromOffset) {
            Replica leader = partition.leader();
            long readableUpTo = leader.hw; // 消费者只能读到 HW

            List<LogEntry> result = new ArrayList<>();
            for (LogEntry entry : leader.log) {
                if (entry.offset >= fromOffset && entry.offset < readableUpTo) {
                    result.add(entry);
                }
            }
            System.out.printf("  [Consumer] %s poll: partition=%d, offset=%d, HW=%d, 拉取=%d 条%n",
                    groupId, partition.partitionId, fromOffset, readableUpTo, result.size());
            return result;
        }
    }

    /* ======================== ISR 动态维护演示 ======================== */

    static void isrDynamicDemo() {
        System.out.println("\n--- ISR 动态维护演示 ---");

        // 3 副本: broker-0(Leader), broker-1, broker-2
        Partition partition = new Partition("orders", 0, 0, 1, 2);
        KafkaProducer producer = new KafkaProducer();

        System.out.println("初始 ISR: " + partition.isr);

        // 发送 3 条消息
        for (int i = 0; i < 3; i++) {
            producer.send(partition, "key-" + i, "value-" + i);
        }

        // Broker-1 同步
        Replica follower1 = partition.replicas.get(1); // broker-1
        follower1.catchUp(partition.leader().leo, partition.leader().hw);
        System.out.printf("  Broker-1 同步完成: leo=%d%n", follower1.leo);

        // Broker-2 落后 (模拟长时间未同步)
        Replica follower2 = partition.replicas.get(2); // broker-2
        follower2.lastCaughtUpTimeMs = System.currentTimeMillis() - 35_000; // 35秒前
        partition.checkISR(30_000); // lag.max.ms = 30s
        System.out.println("当前 ISR: " + partition.isr);

        // 随 Follower 恢复重新加入 ISR
        follower2.catchUp(partition.leader().leo, partition.leader().hw);
        partition.isr.add(2);
        System.out.println("Broker-2 恢复同步, ISR: " + partition.isr);
    }

    /* ======================== HW/LEO 推进演示 ======================== */

    static void hwLeoDemo() {
        System.out.println("\n--- HW / LEO 推进演示 ---");

        Partition partition = new Partition("payments", 0, 0, 1);
        KafkaProducer producer = new KafkaProducer();

        System.out.println("初始状态: Leader(LEO=0, HW=0), Follower(LEO=0, HW=0)");

        // Producer 发送消息
        producer.send(partition, "pay-1", "100");
        System.out.printf("  发送后: Leader(LEO=%d, HW=%d), Follower(LEO=%d)%n",
                partition.leader().leo, partition.leader().hw,
                partition.followers().get(0).leo);

        producer.send(partition, "pay-2", "200");
        System.out.printf("  发送后: Leader(LEO=%d, HW=%d), Follower(LEO=%d)%n",
                partition.leader().leo, partition.leader().hw,
                partition.followers().get(0).leo);

        // Follower 追赶
        producer.followerFetch(partition);
        System.out.printf("  Follower同步后: Leader(LEO=%d, HW=%d), Follower(LEO=%d)%n",
                partition.leader().leo, partition.leader().hw,
                partition.followers().get(0).leo);

        // Consumer 读取
        KafkaConsumer consumer = new KafkaConsumer("pay-group");
        List<LogEntry> consumed = consumer.poll(partition, 0);
        System.out.println("  Consumer 可见消息 (offset < HW):");
        consumed.forEach(e -> System.out.printf("    offset=%d, value=%s%n", e.offset, e.value));

        // 未同步的消息 Consumer 看不到
        producer.send(partition, "pay-3", "300");
        System.out.printf("  新消息发送(未同步): Leader(LEO=%d, HW=%d)%n",
                partition.leader().leo, partition.leader().hw);
        List<LogEntry> consumed2 = consumer.poll(partition, 0);
        System.out.printf("  Consumer 可见 (HW=%d): %d 条%n",
                partition.leader().hw, consumed2.size());
    }

    /* ======================== Leader 选举演示 ======================== */

    static void leaderElectionDemo() {
        System.out.println("\n--- Partition Leader 选举演示 ---");

        Partition partition = new Partition("users", 0, 0, 1, 2);
        Controller controller = new Controller(0);
        KafkaProducer producer = new KafkaProducer();

        // 发送消息 + Follower 同步
        producer.send(partition, "user-1", "Alice");
        producer.send(partition, "user-2", "Bob");
        Replica f1 = partition.replicas.get(1);
        Replica f2 = partition.replicas.get(2);
        f1.catchUp(partition.leader().leo, partition.leader().hw);
        f2.catchUp(partition.leader().leo, partition.leader().hw);
        partition.updateHW();

        System.out.printf("Leader(LEO=%d, HW=%d) F1(LEO=%d) F2(LEO=%d)%n",
                partition.leader().leo, partition.leader().hw, f1.leo, f2.leo);

        // 模拟 Leader Broker-0 宕机
        System.out.println("\n>>> Broker-0 (Leader) 宕机! <<<");
        partition.isr.remove(0);

        // Controller 选举新 Leader
        Replica newLeader = controller.electLeader(partition, partition.isr);

        System.out.printf("""
                选举结果:
                  - 原 Leader: Broker-0 (宕机)
                  - 新 Leader: Broker-%d (ISR 中第一可用副本)
                  - 新 Leader LEO=%d, HW=%d
                  - 数据一致性: ISR 中副本数据一致, 不丢消息
                %n""", newLeader.brokerId, newLeader.leo, newLeader.hw);
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        System.out.println("========== Kafka 核心架构模拟 ==========\n");

        // 1. 基本概念
        System.out.println("--- 1. Kafka 核心组件 ---");
        System.out.println("""
                Topic: 消息主题, 逻辑概念
                Partition: 物理分片, 每个 Partition 是一个有序不可变日志
                Broker: Kafka 服务节点
                ISR (In-Sync Replicas): 与 Leader 保持同步的副本集合
                HW (High Watermark): ISR 中最小的 LEO, 消费者可见上界
                LEO (Log End Offset): 每个副本下一条消息的写入位置
                Controller: 集群中一个特殊的 Broker, 负责 Leader 选举
                """);

        // 2. Partition + ISR 模型
        System.out.println("--- 2. Partition 副本模型 ---");
        Partition partition = new Partition("test-topic", 0, 0, 1, 2);
        System.out.printf("Partition: %s-%d, RF=3, ISR=%s%n",
                partition.topic, partition.partitionId, partition.isr);
        System.out.printf("Leader: %s%n", partition.leader());
        partition.followers().forEach(f -> System.out.printf("Follower: %s%n", f));

        // 3. HW/LEO 推进
        hwLeoDemo();

        // 4. ISR 动态维护
        isrDynamicDemo();

        // 5. Leader 选举
        leaderElectionDemo();

        // 总结
        System.out.println("--- 6. Kafka 核心概念总结 ---");
        System.out.println("""
                ISR 机制:
                  - ISR = 与 Leader 保持同步的副本集合
                  - replica.lag.time.max.ms (默认 30s): Follower 在该时间内未同步则踢出 ISR
                  - min.insync.replicas: 最少同步副本数, 不满足则拒绝写入 (acks=all)
                
                HW vs LEO:
                  - LEO = 下一条消息写入位置 (每个副本各自维护)
                  - HW = min(ISR 中所有副本的 LEO)
                  - Consumer 只能消费 offset < HW 的消息
                  - HW 保证: ISR 中所有副本都有该消息 → 切换 Leader 不丢
                
                acks 配置:
                  - acks=0: 不等待确认 (最快, 可能丢)
                  - acks=1: Leader 写入成功即返回 (可能丢未同步的消息)
                  - acks=all(-1): 所有 ISR 确认后返回 (最可靠)
                
                Controller:
                  - ZK/KRaft 选出一个 Controller Broker
                  - 负责: Partition Leader 选举, ISR 管理, 分区重分配
                  - Leader 宕机 → Controller 从 ISR 中选新 Leader
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}