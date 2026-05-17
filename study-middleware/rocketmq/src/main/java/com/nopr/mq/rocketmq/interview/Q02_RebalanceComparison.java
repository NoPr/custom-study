package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】再平衡协议对比 —— RocketMQ vs Kafka 消费者组协调机制
 * 【描述】对比 RocketMQ 和 Kafka 消费者组再平衡（Rebalance）的架构差异：
 *         RocketMQ 客户端自主负载均衡 vs Kafka GroupCoordinator 中心化协调。
 *         涵盖分配策略、触发时机、Stop-the-world 影响、协议演进方向。
 * 【关键概念】Rebalance、GroupCoordinator、AllocateMessageQueueStrategy、
 *             RangeAssignor、StickyAssignor、CooperativeStickyAssignor、
 *             Eager Rebalance、客户端负载均衡、服务端协调
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.MessageOrderDemo
 *         @see com.nopr.mq.kafka.simulate.RebalanceDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q02_RebalanceComparison {

    /**
     * 对比维度表格输出
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  RocketMQ vs Kafka 再平衡协议对比");
        System.out.println("=".repeat(70));

        printSection("1. 架构模型");
        printRow("协调方式", "客户端自主负载均衡", "GroupCoordinator 中心化协调");
        printRow("协调节点", "无中心节点", "GroupCoordinator（某 Broker 担任）");
        printRow("注册发现", "NameServer 心跳", "Consumer 向 Coordinator 注册");
        printRow("心跳机制", "Consumer→所有Broker(30s)", "Consumer→Coordinator(session.timeout.ms)");

        printSection("2. 分配策略");
        printRow("内置策略",
                "5种：平均哈希/环形/手动/机房/一致性哈希",
                "4种：Range/RoundRobin/Sticky/CooperativeSticky");
        printRow("策略接口", "AllocateMessageQueueStrategy", "PartitionAssignor");
        printRow("分配粒度", "MessageQueue", "Partition");
        printRow("触发时机", "Consumer 数量变化 / 定时 20s", "Consumer 加入离开 / session 超时");

        printSection("3. 执行方式");
        printRow("Eager 模式", "默认，释放所有 Queue 重新分配", "默认，Revoke 所有 Partition 再分配");
        printRow("Cooperative", "不支持（Dledger 5.0 规划中）", "✅ CooperativeSticky（2.4+ 生产可用）");
        printRow("Stop-the-world", "❌（客户端级，影响单 Consumer）", "⚠️ Eager 模式 STW，Cooperative 无 STW");
        printRow("分配时长", "极快（纯本地计算）", "较慢（网络协调+元数据同步）");

        printSection("4. 故障恢复");
        printRow("Consumer 宕机", "Broker 检测心跳超时→通知其他 Consumer 重分配", "Coordinator 检测 session 超时→触发 Rebalance");
        printRow("恢复速度", "较快（NameServer 瞬时感知）", "较慢（session.timeout.ms + rebalance.timeout.ms）");
        printRow("消息重复风险", "中等（offset 管理在客户端）", "较低（offset 由 Coordinator 管理）");

        printSection("5. 适用场景");
        printRow("推荐场景", "Consumer 数量稳定, 要求极低延迟", "Consumer 频繁扩缩容, 需精确 offset 管理");
        printRow("Kafka优势", "—", "Sticky 减少分区迁移, Cooperative 无 STW");
        printRow("RocketMQ优势", "无中心化瓶颈, 客户端计算更快", "—");

        printSection("面试要点");
        System.out.println("  Q: RocketMQ 和 Kafka 的 Rebalance 有何本质区别？");
        System.out.println("  A: RocketMQ 是客户端自主拉取 NameServer 路由信息后在本地计算分配,");
        System.out.println("     无中心协调节点,分配决策发生在 Consumer 内部,速度更快但一致性较弱。");
        System.out.println("     Kafka 通过 GroupCoordinator(Broker) 统一协调,所有 Consumer 向");
        System.out.println("     Coordinator 注册,分配决策在服务端,一致性更强但有单点协调开销。");

        System.out.println("\n  Q: CooperativeSticky 解决了什么问题？");
        System.out.println("  A: 传统 Eager Rebalance(Revoke All → Reassign) 在 Consumer 变化时");
        System.out.println("     所有 Consumer 停止消费,造成消费停滞(Stop-the-world)。");
        System.out.println("     CooperativeSticky 分阶段执行:先 Revoke 最少 Partition,再 Assign,");
        System.out.println("     未被影响的 Consumer 继续消费,零停顿。Kafka 2.4+ 生产可用。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u251C%s\u252C%s\u2524%n", "\u2500".repeat(30), "\u2500".repeat(33));
    }

    static void printRow(String dim, String rocketmq, String kafka) {
        System.out.printf("  \u2502 %-28s \u2502 %-31s \u2502 %-31s \u2502%n", dim, truncate(rocketmq, 31), truncate(kafka, 31));
    }

    static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
