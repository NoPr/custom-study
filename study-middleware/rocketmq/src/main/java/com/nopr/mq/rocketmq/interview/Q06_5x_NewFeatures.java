package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】5.0 新特性 —— Controller·POP·Proxy·轻量级队列
 * 【描述】介绍 RocketMQ 5.0 重大架构变更：Controller 模式（替代 DLedger，内置 Raft）、
 *         POP 消费（解决客户端负载不均衡）、Proxy 代理（gRPC 多语言支持）、
 *         轻量级消息队列（topic→queue 自动扩展）、与 4.x 的关键差异。
 * 【关键概念】Controller 5.0、POP 消费、Proxy、gRPC、DLedger 淘汰、
 *             Raft 元数据、轻量级 Queue、消息类型分离、4.x→5.0 迁移
 * 【关联类】@see Q03_HighAvailabilityComparison
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q06_5x_NewFeatures {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  RocketMQ 5.0 新特性 面试专辑");
        System.out.println("=".repeat(70));

        controllerMode();
        popConsumption();
        proxyGateway();
        lightweightQueue();
        versionDiff();
        interviewQA();
    }

    static void controllerMode() {
        printSection("1. Controller 模式（替代 DLedger）");

        System.out.println("  4.x 架构: Master-Slave → DLedger (Raft) → 仅数据同步使用 Raft");
        System.out.println("  5.x 架构: Controller → 内置 Raft → 元数据 + 数据统一管理");
        System.out.println();
        System.out.println("  Controller 核心变化:");
        printRow("元数据管理", "Broker 注册到 Controller（替代 NameServer 部分职责）", "");
        printRow("Leader 选举", "Controller 基于 Raft 管理，替代 DLedger 选举", "");
        printRow("高可用", "Controller 集群 (3/5 奇数节点) 自管理 Raft", "");
        printRow("脑裂防护", "Term + LeaderEpoch，类似 Kafka KRaft", "");
        printRow("NameServer", "仍需 NameServer 做路由发现（未完全去 NS）", "");

        System.out.println();
        System.out.println("  📌 Controller 尚未完全替代 NameServer，架构演进仍在进行中");
    }

    static void popConsumption() {
        printSection("2. POP 消费（解决负载不均衡）");

        System.out.println("  4.x Push/Pull 消费的痛点:");
        System.out.println("    - Push: 客户端负载均衡(AllocateMessageQueueStrategy)，");
        System.out.println("      单 Queue 只能被单 Consumer 消费");
        System.out.println("    - 问题: Consumer 数量 > Queue 数量 → 部分 Consumer 空闲");
        System.out.println("    - 解决方法: 增加 Queue 数（上限 65536，但增加 OPS 开销）");
        System.out.println();
        System.out.println("  5.0 POP 消费:");
        System.out.println("    - Broke 端排队：Consumer 不绑定 Queue，向 Broker 发起 POP 请求");
        System.out.println("    - Broker 从多个 Queue 聚合消息返回 → 解决负载不均");
        System.out.println("    - Consumer 无状态弹性扩缩：100 个 Consumer 共享 4 个 Queue");
        System.out.println();
        System.out.println("  优势: Consumer 扩缩容零感知，消息分发更均匀");
        System.out.println("  代价: Broker 端多一次 POP 聚合，轻微增加延迟");
    }

    static void proxyGateway() {
        printSection("3. Proxy 代理（多语言 + 协议演进）");

        System.out.println("  4.x 协议: Remoting（自研 Java 序列化）→ Client 强耦合 Java");
        System.out.println("  5.0 Proxy:");
        System.out.println("    - 协议层: gRPC + Protobuf（语言无关）");
        System.out.println("    - 部署: Proxy 独立进程 → 负载均衡 → 后端 Broker");
        System.out.println("    - Client: Go / Python / C++ / Rust 等原生 SDK");
        System.out.println();
        System.out.println("  架构:");
        System.out.println("    Client (gRPC) → Proxy (名称发现) → Namesrv → Broker");
        System.out.println();
        System.out.println("  优势: 多语言 SDK 成本降低 90%，协议标准化便于云原生集成");
    }

    static void lightweightQueue() {
        printSection("4. 轻量级消息队列");

        System.out.println("  4.x Topic 与 Queue 的关系:");
        System.out.println("    Topic → 固定 Queue 数（需预先规划，改 Queue 数需重建 Topic）");
        System.out.println();
        System.out.println("  5.0 轻量级 Queue:");
        printRow("动态扩展", "Topic 读写负载 → Broker 自动增减 Queue 数", "");
        printRow("单分区消费", "Queue 支持单 Consumer 读（收拢→POP 聚合分发）", "");
        printRow("资源隔离", "不同 Topic 间 Queue 物理隔离，互不影响", "");
        printRow("适用场景", "不需要严格有序的场景（90% 场景适用）", "");
    }

    static void versionDiff() {
        printSection("5. 4.x vs 5.0 关键差异");

        System.out.printf("  %-22s %-23s %-23s%n", "维度", "4.x (DLedger)", "5.0 (Controller)");
        System.out.println("  " + "-".repeat(68));
        printRow("选主", "DLedger(Raft 选主)", "Controller(Raft 选主+元数据管理)");
        printRow("消费", "Push/Pull(客户端负载均衡)", "新增 POP(Broker 端聚合分发)");
        printRow("协议", "Remoting(Java私协议)", "新增 gRPC proxy(多语言)");
        printRow("Queue", "固定数量", "新增轻量级 Queue(动态扩展)");
        printRow("架构", "NameServer + DLedger + Broker", "NameServer + Controller + Proxy + Broker");
        printRow("迁移路径", "-", "4.x → 5.0 平滑升级（兼容 4.x Remoting)");

        System.out.println();
        System.out.println("  ⚠️ 注意: 5.0 仍兼容 4.x Remoting 协议，可渐进迁移");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: POP 消费和 Push 消费如何选择？");
        System.out.println("  A: Consumer 经常扩缩容 → POP（Broker 端聚合分发，无绑定限制）");
        System.out.println("     Consumer 数量 ≤ Queue 数且稳定 → Push（成熟稳定 + 低延迟）");
        System.out.println();
        System.out.println("  Q: RocketMQ 5.0 Controller 是否完全替代 NameServer？");
        System.out.println("  A: 否。Controller 管理元数据(Broker 注册/Topic 配置)，NameServer 负责路由发现。");
        System.out.println("     未来方向是 Controller 统一管理 + 去除 NameServer，但 5.0 尚未实现。");
        System.out.println();
        System.out.println("  Q: 4.x DLedger 迁移到 5.0 Controller 是否需要改业务代码？");
        System.out.println("  A: Controller 模式需要 Broker 端重新配置（controllerAddr），");
        System.out.println("     但 Producer/Consumer 连接方式不变（仍连 NameServer），业务代码零改动。");
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
