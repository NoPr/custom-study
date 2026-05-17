package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】Pulsar/RabbitMQ/AMQP 简介 —— 其他主流 MQ 对比
 * 【描述】简要介绍 Apache Pulsar（计算存储分离、分层存储、多租户）、
 *         RabbitMQ（AMQP 协议、Exchange 灵活路由）、以及它们与
 *         RocketMQ/Kafka 的定位差异和适用场景对比。
 * 【关键概念】Pulsar、BookKeeper、计算存储分离、分层存储、多租户、
 *             RabbitMQ、AMQP 0-9-1、Exchange、Binding、Routing Key、
 *             Pub/Sub、Queue、灵活路由
 * 【关联类】@see Q01_RocketMQ_Kafka、Q02_RebalanceComparison、Q03_HighAvailabilityComparison
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q04_PulsarRabbitMQIntro {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Pulsar / RabbitMQ / AMQP 简介与对比");
        System.out.println("=".repeat(70));

        pulsarIntro();
        rabbitmqIntro();
        comparisonMatrix();
        interviewQA();
    }

    static void pulsarIntro() {
        System.out.println("\n  \u250C" + "\u2500".repeat(66) + "\u2510");
        System.out.println("  \u2502 Apache Pulsar                                              \u2502");
        System.out.println("  \u2514" + "\u2500".repeat(66) + "\u2518");

        System.out.println("  架构: Producer → Broker(无状态) → BookKeeper(有状态存储) + ZooKeeper(元数据)");
        System.out.println();
        System.out.println("  核心特性:");
        System.out.println("    1. 计算存储分离: Broker 只负责消息服务(无状态可弹性扩缩),");
        System.out.println("       BookKeeper 负责持久化存储(Bookie 节点)");
        System.out.println("    2. 分层存储: 热数据→BookKeeper, 冷数据→S3/HDFS, 降低存储成本");
        System.out.println("    3. 多租户: tenant/namespace/topic 三级隔离, 原生支持");
        System.out.println("    4. 统一消息模型: 同一 Topic 支持 Queue(独占/灾备/共享)+ Stream(KeyShared)");
        System.out.println("    5. 跨地域复制: Geo-Replication, 原生多集群同步");
        System.out.println("    6. 延迟消息: 支持任意时间精度(无 18 级限制)");
        System.out.println();
        System.out.println("  优点: 云原生架构、弹性伸缩、多租户隔离、统一消息模型");
        System.out.println("  缺点: 组件多(Broker+BookKeeper+ZK),运维复杂度高, 社区较小");
        System.out.println("  适用: 多租户 SaaS 平台、需要分层存储、跨区域同步的企业级场景");
    }

    static void rabbitmqIntro() {
        System.out.println("\n  \u250C" + "\u2500".repeat(66) + "\u2510");
        System.out.println("  \u2502 RabbitMQ / AMQP 0-9-1                                       \u2502");
        System.out.println("  \u2514" + "\u2500".repeat(66) + "\u2518");

        System.out.println("  协议: AMQP 0-9-1 (Advanced Message Queuing Protocol)");
        System.out.println("  架构: Producer → Exchange(路由) → Binding(绑定规则) → Queue → Consumer");
        System.out.println();
        System.out.println("  核心概念:");
        System.out.println("    Exchange 类型:");
        System.out.println("      - Direct:   Routing Key 精确匹配");
        System.out.println("      - Topic:    Routing Key 通配符匹配(*.#)");
        System.out.println("      - Fanout:   广播到所有绑定 Queue");
        System.out.println("      - Headers:  按消息 Header 属性匹配");
        System.out.println("    Binding: Exchange 到 Queue 的路由规则");
        System.out.println("    Routing Key: Producer 发送时指定,决定消息路由路径");
        System.out.println();
        System.out.println("  特色功能:");
        System.out.println("    1. 灵活路由: Exchange+Binding 组合实现复杂路由拓扑");
        System.out.println("    2. 消息确认: Publisher Confirm + Consumer Ack 双重保障");
        System.out.println("    3. TTL+DLX: 消息/队列 TTL 到期→死信 Exchange→死信队列");
        System.out.println("    4. 延迟队列: TTL+DLX 组合实现（无原生延迟）");
        System.out.println("    5. 优先级队列: 最大优先级 255");
        System.out.println();
        System.out.println("  优点: 灵活路由、AMQP 标准协议、管理界面友好、插件生态丰富");
        System.out.println("  缺点: 吞吐低(单机万级 TPS)、无分区概念(扩展靠集群)、Erlang 技术栈");
        System.out.println("  适用: 复杂路由需求、低吞吐高可靠性场景（订单状态机、工作流调度）");
    }

    static void comparisonMatrix() {
        System.out.println("\n  \u250C" + "\u2500".repeat(66) + "\u2510");
        System.out.println("  \u2502 四大 MQ 核心对比矩阵                                               \u2502");
        System.out.println("  \u251C" + "\u2500".repeat(14) + "\u252C" + "\u2500".repeat(17) + "\u252C" + "\u2500".repeat(17) + "\u252C" + "\u2500".repeat(14) + "\u2524");

        String[][] rows = {
                {"维度", "RocketMQ", "Kafka", "Pulsar", "RabbitMQ"},
                {"吞吐量", "十万级 TPS", "百万级 TPS", "百万级 TPS", "万级 TPS"},
                {"延迟", "ms 级", "ms 级", "ms 级", "μs 级(低负载)"},
                {"消息模型", "Pub/Sub + 队列", "Pub/Sub(Stream)", "统一(Queue+Stream)", "AMQP Queue"},
                {"存储", "CommitLog 顺序写", "Partition 顺序写", "BookKeeper(计算存储分离)", "内存+磁盘(Mnesia)"},
                {"路由", "NameServer(Topic→Queue)", "Controller(Partition)", "Broker(无状态)", "Exchange+Binding"},
                {"协议", "自研(Remoting)", "自研(Custom)", "自研(Pulsar)", "AMQP 0-9-1"},
                {"社区", "阿里主导+Apache", "Confluent+Apache", "Apache(StreamNative)", "VMware(RabbitMQ)"},
                {"运维", "中等", "中等→简单(KRaft)", "复杂(多组件)", "简单"},
        };

        for (String[] row : rows) {
            System.out.printf("  \u2502 %-12s \u2502 %-15s \u2502 %-15s \u2502 %-15s \u2502 %-12s \u2502%n",
                    truncate(row[0], 12), truncate(row[1], 15),
                    truncate(row[2], 15), truncate(row[3], 15),
                    truncate(row[4], 12));
        }
        System.out.println("  \u2514" + "\u2500".repeat(14) + "\u2534" + "\u2500".repeat(17) + "\u2534" + "\u2500".repeat(17) + "\u2534" + "\u2500".repeat(14) + "\u2518");
    }

    static void interviewQA() {
        printSection("面试要点");
        System.out.println("  Q: RocketMQ/Kafka/Pulsar/RabbitMQ 如何选型？");
        System.out.println("  A: 按场景选择——");
        System.out.println("     - 高吞吐+大数据流处理 → Kafka（生态最完善,Streams/Kafka Connect）");
        System.out.println("     - 金融级事务+延迟消息 → RocketMQ（阿里验证,事务/延迟一等公民）");
        System.out.println("     - 云原生+多租户+弹性 → Pulsar（计算存储分离,分层存储）");
        System.out.println("     - 复杂路由+低吞吐高可靠 → RabbitMQ（AMQP 灵活路由,管理友好）");

        System.out.println("\n  Q: AMQP 协议的核心设计思想？");
        System.out.println("  A: 解耦 Producer 和 Consumer 的直接关系,通过 Exchange+Binding 中间层");
        System.out.println("     实现灵活的消息路由拓扑,支持多种 Exchange 类型适应不同分发模式。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
