package com.nopr.mq.kafka.interview;

/**
 * 【模块】kafka
 * 【分类】interview
 * 【主题】幂等与事务 —— PID·SeqNum·TransactionCoordinator·Exactly Once
 * 【描述】深入解析 Kafka 消息不重不丢机制：幂等性(PID+SeqNum 去重)、
 *         事务(TransactionCoordinator 协调两阶段提交)、Exactly Once 语义
 *         (读-处理-写原子操作)、与 RocketMQ 事务消息的机制差异对比。
 * 【关键概念】enable.idempotence、PID、SeqNum、TransactionCoordinator、
 *             __transaction_state、ProducerIdAndEpoch、isolation.level、
 *             read_committed、Exactly Once、RocketMQ 半消息对比
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaIdempotentDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q02_Idempotent_Transaction {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka 幂等与事务 面试专辑");
        System.out.println("=".repeat(70));

        idempotenceMechanism();
        transactionMechanism();
        exactlyOnceSemantics();
        rocketmqTransactionComparison();
        interviewQA();
    }

    static void idempotenceMechanism() {
        printSection("1. 幂等性 (Idempotence)");

        System.out.println("  动机: Producer 重试导致重复写入 → 开启幂等后 Broker 自动去重");
        System.out.println();
        System.out.println("  实现: enable.idempotence=true 后 Producer 初始化时获得:");
        System.out.println("    - PID (Producer ID): Broker 分配的唯一标识");
        System.out.println("    - SeqNum: 每个 Partition 的消息序列号（单调递增，从 0 开始）");
        System.out.println();
        System.out.println("  去重逻辑:");
        System.out.println("    Broker 为每个 PID 维护最近 5 个 SeqNum 的缓存");
        System.out.println("    收到消息 → 检查 PID + SeqNum:");
        System.out.println("      - SeqNum 比缓存小 1 以上 → 乱序，抛出 OutOfOrderSequenceException");
        System.out.println("      - SeqNum 已存在 → 重复，丢弃");
        System.out.println("      - SeqNum 合法 → 正常写入");
        System.out.println();
        System.out.println("  限制: 单 Producer 单 Partition 单会话内去重（Producer 重启 PID 变化）");
    }

    static void transactionMechanism() {
        printSection("2. 事务 (Transaction)");

        System.out.println("  架构:");
        System.out.println("    - TransactionCoordinator: 某 Broker 兼任，管理事务状态");
        System.out.println("    - __transaction_state: 内部 Topic，存储事务日志");
        System.out.println("    - TransactionalId: Producer 配置，关联 PID 的稳定标识");
        System.out.println("    - ProducerIdAndEpoch: 防止僵尸 Producer（epoch 单调递增）");
        System.out.println();
        System.out.println("  流程:");
        System.out.println("    1. initTransactions() → 向 Coordinator 注册 TransactionalId");
        System.out.println("    2. beginTransaction() → 开启事务");
        System.out.println("    3. send(record) → 消息写入分区（对 Consumer 不可见）");
        System.out.println("    4. sendOffsetsToTransaction() → 提交消费 offset");
        System.out.println("    5. commitTransaction() → Coordinator 写入 PREPARE_COMMIT → 所有分区写入 COMMIT marker");
        System.out.println();
        System.out.println("  Consumer 端:");
        System.out.println("    isolation.level=read_committed → 只读到已 COMMIT 的消息（过滤 ABORT）");
        System.out.println("    isolation.level=read_uncommitted → 读到所有消息（默认，性能更高）");
    }

    static void exactlyOnceSemantics() {
        printSection("3. Exactly Once 语义");

        System.out.println("  Kafka Streams EOS: 读-处理-写 三步原子化");
        System.out.println();
        printRow("At-Most-Once", "acks=0, Consumer 先 commit 后处理 → 可能丢", "");
        printRow("At-Least-Once", "acks=1, Consumer 先处理后 commit → 可能重复（默认）", "");
        printRow("Exactly-Once", "幂等 Producer + 事务 + read_committed Consumer → 不丢不重", "");

        System.out.println();
        System.out.println("  ⚠️ 注意: Kafka 的 Exactly Once 仅限于「Kafka → Kafka」的流处理场景，");
        System.out.println("     如果涉及外部系统(DB/RPC)，仍需引入分布式事务（如 Seata）或最终一致性。");
    }

    static void rocketmqTransactionComparison() {
        printSection("4. Kafka 事务 vs RocketMQ 事务消息");

        System.out.printf("  %-20s %-22s %-22s%n", "维度", "Kafka 事务", "RocketMQ 事务");
        System.out.println("  " + "-".repeat(64));
        printRow("实现方式", "TransactionCoordinator + 两阶段提交 + COMMIT marker", "半消息 + 本地事务 + 回查 CheckListener");
        printRow("API 模型", "begin/commit/abort 主动控制", "sendMessageInTransaction + executeLocalTransaction");
        printRow("消费者感知", "需配 read_committed 过滤", "半消息对 Consumer 不可见（透明）");
        printRow("回查机制", "无（超时 abort）", "✅ CheckListener 定期回查 UNKNOWN 状态");
        printRow("适用场景", "流处理(Kafka→Kafka)", "分布式事务(DB+RPC→MQ)");
        printRow("复杂度", "较高（需管理 TransactionalId）", "中等（实现 CheckListener 即可）");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: 幂等 Producer 重启后 PID 变了怎么办？");
        System.out.println("  A: 幂等性在单会话内有效。跨会话去重需要事务 + TransactionalId，");
        System.out.println("     TransactionalId 关联到 PID，Producer 重启后 Coordinator 分配新 PID 但 epoch+1，");
        System.out.println("     旧 PID 写入会被 Fencing 拒绝。");
        System.out.println();
        System.out.println("  Q: Kafka 事务能替代 RocketMQ 事务消息吗？");
        System.out.println("  A: 不能直接替代。Kafka 事务解决 Kafka→Kafka 的一致性问题，");
        System.out.println("     RocketMQ 事务解决 DB/外部系统→MQ 的分布式事务。场景互补。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-20s: %s%n", dim, value);
    }
}
