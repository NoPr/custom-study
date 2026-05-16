package base.mq.interview;

/**
 * 面试：RocketMQ vs Kafka 全维度对比
 *
 * <p>架构对比：NameServer vs Zookeeper/KRaft。
 * 消息模型对比：Queue vs Partition。
 * 事务消息对比：RocketMQ 原生 vs Kafka Exactly-Once。
 * 适用场景：RocketMQ(金融/订单) vs Kafka(日志/流处理)。
 *
 * <p>高频面试题：
 * <ol>
 *   <li>RocketMQ 和 Kafka 架构上有什么本质区别？</li>
 *   <li>为什么 RocketMQ 用 NameServer 而 Kafka 用 ZK/KRaft？</li>
 *   <li>消息模型有什么不同？Queue vs Partition</li>
 *   <li>事务消息实现有什么区别？哪种更适合业务场景？</li>
 *   <li>各自的优势场景是什么？如何选型？</li>
 *   <li>两者在性能上谁更强？为什么？</li>
 * </ol>
 *
 * @author study-tuling
 */
public class Q01_RocketMQ_Kafka {

    public static void main(String[] args) {
        System.out.println("========== RocketMQ vs Kafka 全维度对比 ==========\n");

        // ======================== 1. 架构对比 ========================
        System.out.println("=" .repeat(60));
        System.out.println("  1. 架构对比");
        System.out.println("=" .repeat(60));

        System.out.println("""
                
                ┌─────────────────────────────────────────────────────────┐
                │                    RocketMQ 架构                          │
                │  Producer → NameServer(无状态) → Broker(Master+Slave)    │
                │      ↓ 心跳上报                                           │
                │  Consumer ← NameServer ← Broker                           │
                │                                                          │
                │  NameServer: 几乎无状态, 各节点独立, 无选主                   │
                │  Broker: 主从架构, 支持 DLedger(Raft) 自动切换               │
                └─────────────────────────────────────────────────────────┘
                
                ┌─────────────────────────────────────────────────────────┐
                │                     Kafka 架构                            │
                │  Producer → ZK/KRaft → Controller → Broker(Leader选举)   │
                │      ↓ 元数据管理                                        │
                │  Consumer ← ZK/KRaft ← Broker                            │
                │                                                          │
                │  ZK/KRaft: 强一致性协调, 选主, 元数据存储                     │
                │  Controller: 一个 Broker 承担 Leader 选举和分区管理         │
                └─────────────────────────────────────────────────────────┘
                """);

        printComparison("架构对比",
                "维度", "RocketMQ", "Kafka",
                "注册中心", "NameServer (自研, AP)", "Zookeeper (CP) / KRaft (自研)",
                "选主", "无, 各节点对等", "ZK 选举 Controller, Controller 选 Leader",
                "一致性", "最终一致 (心跳 30s)", "强一致 (ZK) / KRaft (Raft)",
                "Broker 发现", "Broker 向 NameServer 注册", "Broker 向 ZK 注册临时节点",
                "复杂度", "低, 无外部依赖", "高, 依赖 ZK 或 KRaft");

        // ======================== 2. 消息模型对比 ========================
        System.out.println("\n" + "=" .repeat(60));
        System.out.println("  2. 消息模型对比");
        System.out.println("=" .repeat(60));

        System.out.println("""
                
                RocketMQ Queue (MessageQueue):
                  - Topic 下多个 Queue (默认 4 读 4 写)
                  - Queue 是逻辑概念, 底层 CommitLog 物理存储
                  - 读写 Queue 可分离: writeQueueNums ≠ readQueueNums
                  - Consumer 负载均衡: 平均分配 Queue 给 Consumer
                
                Kafka Partition:
                  - Topic 下多个 Partition
                  - Partition 是物理存储单元 = 目录下的分段日志文件
                  - 每个 Partition 一个 Leader + 多个 Follower
                  - Consumer 负载均衡: 一个 Partition 只能被同一 Group 内一个 Consumer 消费
                """);

        printComparison("消息模型对比",
                "维度", "RocketMQ", "Kafka",
                "逻辑单元", "MessageQueue", "Partition",
                "物理存储", "CommitLog (所有 Queue 共享)", "Partition 独立日志段 (.log/.index)",
                "读写分离", "支持 (readQueueNums≠writeQueueNums)", "不支持",
                "消费并行度", "由 Queue 数量决定", "由 Partition 数量决定",
                "顺序消息", "原生支持分区有序+全局有序", "Partition 内有序",
                "Tag/SQL过滤", "Tag + SQL92 表达式过滤", "无内置过滤 (消费端处理)",
                "消息回溯", "按时间/offset 回溯", "按 offset 回溯");

        // ======================== 3. 事务消息对比 ========================
        System.out.println("\n" + "=" .repeat(60));
        System.out.println("  3. 事务消息对比");
        System.out.println("=" .repeat(60));

        System.out.println("""
                
                RocketMQ 事务消息:
                  1. sendMessageInTransaction → 半消息 (消费者不可见)
                  2. executeLocalTransaction → 本地事务 (如写 DB)
                  3. commit / rollback
                  4. checkLocalTransaction → 回查 (超时后 Broker 回调)
                  特点: 不依赖 XA/2PC, 通过回查实现最终一致
                  场景: 订单创建+库存扣减+消息通知
                
                Kafka 事务 (KIP-98):
                  1. initTransactions → 获取 PID + TransactionCoordinator
                  2. beginTransaction → 事务开始
                  3. send(record) → 写入但标记未提交
                  4. sendOffsetsToTransaction → 消费位移也纳入事务
                  5. commitTransaction / abortTransaction
                  特点: 基于 PID+SeqNum 幂等, TransactionCoordinator 协调
                  场景: Kafka Streams consume-transform-produce
                """);

        printComparison("事务消息对比",
                "维度", "RocketMQ", "Kafka",
                "实现方式", "半消息 + 回查", "PID + TransactionCoordinator",
                "事务协调", "Broker 内置", "独立 TransactionCoordinator",
                "超时处理", "CheckListener 回查本地事务", "超时自动 abort",
                "原子性范围", "1 Topic (半消息机制)", "跨 Topic/跨分区",
                "典型场景", "分布式事务 (订单+库存)", "Kafka Streams EOS",
                "学习成本", "低 (3 步)", "中 (6 步)",
                "适用性", "业务场景友好", "流处理场景友好");

        // ======================== 4. 适用场景 ========================
        System.out.println("\n" + "=" .repeat(60));
        System.out.println("  4. 适用场景与选型");
        System.out.println("=" .repeat(60));

        System.out.println("""
                
                选 RocketMQ 的场景:
                  ✓ 金融/支付场景 (事务消息原生支持)
                  ✓ 电商订单流程 (顺序消息 + 事务消息)
                  ✓ 需要 Tag/SQL 过滤 (减少消费端逻辑)
                  ✓ 业务消息可靠性要求极高 (同步刷盘 + 同步复制)
                  ✓ 阿里技术栈 (无缝对接)
                  ✓ 不希望引入 Zookeeper 依赖
                
                选 Kafka 的场景:
                  ✓ 日志采集 + 实时分析 (ELK/EFK)
                  ✓ 流处理 (Kafka Streams / Flink Source)
                  ✓ 大数据管道 (Spark Streaming)
                  ✓ 高吞吐场景 (顺序写 + PageCache + sendfile)
                  ✓ 需要 Connect 生态 (CDC, 数据同步)
                  ✓ 已有 ZK/KRaft 基础设施
                """);

        // ======================== 5. 性能对比 ========================
        System.out.println("=" .repeat(60));
        System.out.println("  5. 性能对比");
        System.out.println("=" .repeat(60));

        printComparison("性能对比 (基准场景, 取决于配置)",
                "维度", "RocketMQ", "Kafka",
                "吞吐量", "~10w TPS (单机)", "~100w TPS (单机)",
                "延迟", "ms 级 (Pull)", "ms 级 (Pull)",
                "存储", "CommitLog 顺序写", "Partition 分段顺序写",
                "零拷贝", "mmap (CommitLog)", "sendfile (网络传输)",
                "页缓存", "mmap 映射", "PageCache (OS 管理)",
                "刷盘", "同步/异步可选", "OS 异步刷盘 (依赖 PageCache)",
                "高性能原因",
                "NIO+CommitLog+mmpa",
                "sendfile+PageCache+顺序IO");

        // ======================== 6. 选型建议 ========================
        System.out.println("\n" + "=" .repeat(60));
        System.out.println("  6. 选型建议 (面试说这句就够了)");
        System.out.println("=" .repeat(60));

        System.out.println("""
                
                一句话选型:
                  "业务消息用 RocketMQ，大数据/日志用 Kafka。"
                
                详细:
                  1. 如果你要处理订单、支付、库存这类对事务消息和可靠性的
                     要求高的业务场景 → RocketMQ (半消息+回查)
                  2. 如果你要做日志采集、流处理、大数据管道，对吞吐量要求
                     极高 → Kafka (sendfile + PageCache + 分段日志)
                  3. 如果两者都需要 → 都用，它们不冲突
                  
                  核心差异总结:
                    RocketMQ = "消息队列"（侧重业务功能）
                    Kafka    = "流平台"（侧重数据管道）
                """);

        System.out.println("========== 对比完毕 ==========");
    }

    /** 对齐打印对比表格 */
    private static void printComparison(String title,
                                        String col1, String col2, String col3,
                                        String... rows) {
        System.out.printf("  【%s】%n", title);
        String fmt = "  %-16s │ %-30s │ %-30s%n";
        System.out.printf(fmt, col1, col2, col3);
        System.out.printf("  %s%n", "-".repeat(80));
        for (int i = 0; i < rows.length; i += 3) {
            System.out.printf(fmt, rows[i], rows[i + 1], rows[i + 2]);
        }
        System.out.println();
    }
}