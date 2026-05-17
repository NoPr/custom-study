package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】高可用一致性对比 —— NameServer·ZooKeeper·KRaft
 * 【描述】对比 RocketMQ (NameServer + DLedger/Controller) 和
 *         Kafka (ZooKeeper → KRaft) 的元数据管理与高可用方案。
 *         分析 CAP 取舍、Leader 选举、故障恢复、元数据一致性保障。
 * 【关键概念】NameServer、DLedger、Controller、ZooKeeper、KRaft、
 *             Raft Consensus、CAP 理论、元数据管理、Leader 选举
 * 【关联类】@see com.nopr.mq.kafka.simulate.SplitBrainDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q03_HighAvailabilityComparison {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  RocketMQ vs Kafka 高可用一致性对比");
        System.out.println("=".repeat(70));

        printSection("1. 元数据管理架构");
        printRow("组件", "NameServer（无状态路由注册中心）", "ZooKeeper（CP 强一致）→ KRaft（自管理）");
        printRow("CAP 分类", "AP（可用性优先，最终一致）", "CP（一致性优先）→ KRaft（CP）");
        printRow("数据模型", "扁平 KV：topic→QueueData/BrokerData", "层级树：/brokers/topics/...");
        printRow("节点数量", "2~3 台（无状态，可独立部署）", "3/5 台奇数节点（Zab/Raft 选举需要）");
        printRow("数据一致性", "各节点独立，Broker 同时向所有 NS 注册", "Leader 写入→Follower 同步，过半确认");

        printSection("2. Leader 选举机制");
        printRow("选举组件", "DLedger (Raft) / Controller 5.0", "ZK Zab 协议 / KRaft Raft 协议");
        printRow("选举触发", "Broker 宕机或网络分区", "Controller 宕机 / Partition Leader 故障");
        printRow("选举速度", "秒级（DLedger Raft）", "秒级（Raft 选举超时 200ms~）");
        printRow("脑裂防护", "✅ Leader Epoch / Raft Term", "✅ Controller Epoch + Leader Epoch (KRaft)");

        printSection("3. 故障恢复");
        printRow("Broker 宕机", "Producer 重试→其他 Broker，Consumer 从 Slave 读（5.0）", "Controller 选举新 Leader，ISR 同步");
        printRow("元数据故障", "NameServer 宕机不影响已建立连接", "ZK 多数存活即正常；KRaft 多数存活即正常");
        printRow("数据恢复", "主从同步（SYNC_MASTER/ASYNC_MASTER）", "ISR 同步 + HW 水位线机制");
        printRow("恢复时间", "秒级（Consumer 自动重连其他 NS）", "秒~分钟级（取决于 partition 数量和 ISR 状态）");

        printSection("4. 5.0 架构演进对比");
        printRow("RocketMQ 5.0", "Controller 模式替代 DLedger，内置 Raft，去 NameServer 依赖", "—");
        printRow("Kafka 3.x", "—", "KRaft 替代 ZK，内置 Raft，去 ZK 依赖");
        printRow("统一趋势", "✅ 都向自管理 Raft 共识演进", "✅ 都向自管理 Raft 共识演进");
        printRow("外部依赖", "仍需 NameServer（路由层）", "完全自管理，无外部依赖");

        printSection("5. DLedger vs KRaft 深度对比");
        System.out.println("  RocketMQ DLedger:");
        System.out.println("    - 基于 Raft 协议，用于 CommitLog 主从同步");
        System.out.println("    - 仅管理数据一致性，元数据仍依赖 NameServer");
        System.out.println("    - 5.0 Controller 模式将 Raft 扩展到元数据管理");
        System.out.println();
        System.out.println("  Kafka KRaft:");
        System.out.println("    - 基于 Raft 协议，管理元数据日志(Metadata Topic)");
        System.out.println("    - Controller 即 KRaft Leader，统一元数据和数据");
        System.out.println("    - 单一 Raft 集群管理一切，架构更简洁");
        System.out.println("    - 支持百万 Partition 级别元数据管理");

        printSection("面试要点");
        System.out.println("  Q: 为什么 RocketMQ 用 NameServer 而 Kafka 用 ZK？");
        System.out.println("  A: NameServer 追求 AP(可用性+分区容忍)，无状态+最终一致性，");
        System.out.println("     部署简单(2~3台)，适合 RocketMQ「路由尽量简单」的设计理念。");
        System.out.println("     ZK 追求 CP(一致性+分区容忍)，保证元数据强一致，但部署复杂(3~5台)。");
        System.out.println("     二者最终都走向自管理 Raft：RocketMQ Controller 5.0 / Kafka KRaft 3.x。");

        System.out.println("\n  Q: 都说去 ZK，RocketMQ 和 Kafka 谁更彻底？");
        System.out.println("  A: Kafka KRaft 3.3+ 生产可用，完全移除 ZK 依赖，自管理 Raft 集群。");
        System.out.println("     RocketMQ Controller 5.0 仍需 NameServer 做路由发现，去 ZK 但未去 NS。");
        System.out.println("     Kafka 更彻底——单一 Raft 集群管理一切。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u251C%s\u252C%s\u2524%n", "\u2500".repeat(30), "\u2500".repeat(33));
    }

    static void printRow(String dim, String rocketmq, String kafka) {
        System.out.printf("  \u2502 %-28s \u2502 %-31s \u2502 %-31s \u2502%n",
                dim, truncate(rocketmq, 31), truncate(kafka, 31));
    }

    static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
