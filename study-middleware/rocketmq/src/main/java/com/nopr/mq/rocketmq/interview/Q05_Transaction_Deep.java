package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】事务消息深度剖析 —— 半消息状态机·回查机制·故障场景
 * 【描述】深度解析 RocketMQ 事务消息：半消息(PREPARE)的完整生命周期、
 *         CheckListener 回查时序与频率、事务状态(UNKNOWN/COMMIT/ROLLBACK)
 *         转换规则、与 Kafka 事务的机制/场景/限制差异对比、
 *         典型故障（半消息堆积/回查风暴/CheckListener 异常）。
 * 【关键概念】半消息、事务状态机、CheckListener、回查机制、超时策略、
 *             TRANSACTION_CHECK_TIMES、TransactionStatus.Unknown
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.TransactionDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q05_Transaction_Deep {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  RocketMQ 事务消息深度剖析 面试专辑");
        System.out.println("=".repeat(70));

        halfMessageLifecycle();
        checkListenerDetails();
        failureScenarios();
        kafkaComparison();
        interviewQA();
    }

    static void halfMessageLifecycle() {
        printSection("1. 半消息生命周期");

        System.out.println("  阶段 1: 发送半消息（PREPARE）");
        System.out.println("    Producer.sendMessageInTransaction(msg, txListener)");
        System.out.println("    → Broker 写入半消息（Topic=%RETRY%ConsumeGroup%），Consumer 不可见");
        System.out.println();
        System.out.println("  阶段 2: 执行本地事务");
        System.out.println("    → txListener.executeLocalTransaction(msg, arg)");
        System.out.println("    → 返回 LocalTransactionState:");
        System.out.println("        - COMMIT_MESSAGE → 半消息正式投递，Consumer 可见");
        System.out.println("        - ROLLBACK_MESSAGE → 半消息丢弃");
        System.out.println("        - UNKNOW → 半消息挂起，等待回查");
        System.out.println();
        System.out.println("  阶段 3: 回查（Check）");
        System.out.println("    → Broker 定时扫描超时的 UNKNOW 半消息");
        System.out.println("    → 回调 txListener.checkLocalTransaction(msgExt)");
        System.out.println("    → 最多回查 TRANSACTION_CHECK_TIMES（默认 15 次）");
        System.out.println("    → 超次后自动 ROLLBACK");
    }

    static void checkListenerDetails() {
        printSection("2. CheckListener 回查机制详解");

        printRow("触发条件", "executeLocalTransaction 返回 UNKNOW + 超时", "");
        printRow("首次回查延迟", "transaction.timeout（默认 6s）后触发", "");
        printRow("回查间隔", "递增: 6s → 10s → 30s → 1m → 2m → ... → 2h", "");
        printRow("最大回查次数", "TRANSACTION_CHECK_TIMES=15 次", "");
        printRow("超次处理", "自动 ROLLBACK → 半消息丢弃", "");
        System.out.println();
        System.out.println("  ⚠️ 注意事项:");
        System.out.println("    - checkLocalTransaction 必须幂等（可能被多次回调）");
        System.out.println("    - 回查期间 Consumer 仍然看不到该消息");
        System.out.println("    - 回查在 Broker 端异步线程池执行（checkExecutorService）");
    }

    static void failureScenarios() {
        printSection("3. 典型故障场景");

        System.out.println("  故障 1: 半消息堆积");
        System.out.println("    原因: executeLocalTransaction 大量返回 UNKNOW，回查线程池饱和");
        System.out.println("    排查: 检查 checkListener 是否超时/异常");
        System.out.println("    解决: 增大 checkRequestHoldMax(默认 2000)、优化 checkListener 逻辑");
        System.out.println();
        System.out.println("  故障 2: 回查风暴");
        System.out.println("    原因: Broker 宕机恢复后，堆积的半消息集中回查");
        System.out.println("    影响: checkExecuteQueueSize 打满，正常消息投递变慢");
        System.out.println("    解决: 限制单次回查数量、分批处理回查任务");
        System.out.println();
        System.out.println("  故障 3: CheckListener 持续 Unknown");
        System.out.println("    原因: 依赖的外部系统（DB/Redis/RPC）不可用");
        System.out.println("    后果: 15 次回查后自动 ROLLBACK，实际本地事务可能已完成（数据不一致）");
        System.out.println("    解决: CheckListener 需查询本地事务状态，不可依赖外部系统");
        System.out.println();
        System.out.println("  故障 4: Consumer 消费重复");
        System.out.println("    原因: COMMIT 后 Consumer 消费成功，但 offset 未提交→重试");
        System.out.println("    解决: Consumer 端确保幂等消费（从根源解决）");
    }

    static void kafkaComparison() {
        printSection("4. RocketMQ 事务 vs Kafka 事务");

        System.out.printf("  %-22s %-23s %-23s%n", "对比维度", "RocketMQ 事务消息", "Kafka 事务");
        System.out.println("  " + "-".repeat(68));
        printRow("核心 API", "TransactionMQProducer + CheckListener", "KafkaProducer.initTransactions + commit");
        printRow("协调方式", "Broker 定时回查 checkLocalTransaction", "TransactionCoordinator 协调 + COMMIT marker");
        printRow("外部系统", "天然支持 DB→MQ（本地事务+半消息+回查）", "不支持（仅 Kafka→Kafka）");
        printRow("超时处理", "回查 15 次后自动 ROLLBACK", "transaction.timeout.ms 到期自动 ABORT");
        printRow("消费者透明", "✅ 半消息不可见，COMMIT 后可见", "需配 isolation.level=read_committed");
        printRow("实现复杂度", "中等（CheckListener + 业务幂等）", "较高（TransactionalId + epoch 管理）");
        printRow("典型场景", "订单状态→MQ 通知（DB+MQ 一致性）", "流处理一致(Kafka Streams EOS)");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: CheckListener 返回 Unknown 会发生什么？");
        System.out.println("  A: Broker 按递增间隔定时回调 checkLocalTransaction，最多 15 次。");
        System.out.println("     超次后自动 ROLLBACK。Unknown 期间 Consumer 不可见该消息。");
        System.out.println();
        System.out.println("  Q: 事务消息的可靠性边界在哪？");
        System.out.println("  A: 单向保证——本地事务成功 → 消息必然投递。反方向不保证：");
        System.out.println("     消息投递成功 ≠ Consumer 消费成功，需 Consumer 端幂等兜底。");
        System.out.println();
        System.out.println("  Q: 什么场景不适合事务消息？");
        System.out.println("  A: ① 超高吞吐(万级 TPS)→半消息回查开销大");
        System.out.println("     ② 外部系统弱依赖 → 回查可能因外部不可用而一直 Unknown");
        System.out.println("     ③ 非事务型消息（普通消息不需要半消息机制）");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-22s: %s%n", dim, value);
    }
}
