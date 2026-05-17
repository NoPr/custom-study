# study-mq 重构设计方案

## 背景

`study-mq` 模块是 `study-middleware` 下的消息队列学习模块，当前包含 7 个 Java 文件（约 2300 行），覆盖 RocketMQ 和 Kafka 的核心概念。全部采用纯 Java 手写模拟实现，无外部 MQ 客户端依赖。

## 目标

1. 将 RocketMQ 和 Kafka 拆分为两个独立模块
2. 加深现有主题的实现深度
3. 新增 Kafka 日志处理、RocketMQ 高级特性、消费者组再平衡、高可用一致性、其他 MQ 协议等主题
4. 引入「模拟 + 真实客户端」混合模式
5. 统一类文件头部规范

## 模块与包结构

```
study-middleware/
├── study-mq/                             # 迁移完成后删除
│
├── rocketmq/                             # RocketMQ 独立模块
│   ├── pom.xml
│   └── src/main/java/com/nopr/mq/rocketmq/
│       ├── simulate/                     # 手写模拟
│       │   ├── RocketMQCoreDemo.java         ← 从 study-mq 迁入 + review
│       │   ├── TransactionDemo.java          ← 从 study-mq 迁入 + review
│       │   ├── MessageOrderDemo.java         ← 从 study-mq 迁入 + review
│       │   ├── MessageReliabilityDemo.java   ← 从 study-mq 迁入 + review
│       │   ├── DeadLetterQueueDemo.java      ← 🆕 P2
│       │   ├── DelayMessageDemo.java         ← 🆕 P2
│       │   ├── MessageFilterDemo.java        ← 🆕 P2
│       │   ├── MessageTraceDemo.java         ← 🆕 P2
│       │   └── ConsumeRetryDemo.java         ← 🆕 P2
│       ├── client/                       # 真实 RocketMQ Client 演示
│       │   └── ...                      ← 🆕 P4
│       └── interview/                    # 面试题
│           └── Q01_RocketMQ_Kafka.java       ← 从 study-mq 迁入
│
└── kafka/                                # Kafka 独立模块
    ├── pom.xml
    └── src/main/java/com/nopr/mq/kafka/
        ├── simulate/                     # 手写模拟
        │   ├── KafkaCoreDemo.java            ← 从 study-mq 迁入 + review + P1 加深
        │   ├── KafkaIdempotentDemo.java      ← 从 study-mq 迁入 + review + P1 加深
        │   ├── MessageOrderDemo.java         ← 从 study-mq 迁入 + review
        │   ├── MessageReliabilityDemo.java   ← 从 study-mq 迁入 + review
        │   ├── LogCleanupDemo.java           ← 🆕 P1 日志清理策略
        │   ├── TombstoneDemo.java            ← 🆕 P1 墓碑日志
        │   ├── SplitBrainDemo.java           ← 🆕 P1 脑裂
        │   ├── BatchSendDemo.java            ← 🆕 P1 批量发送
        │   ├── RebalanceDemo.java            ← 🆕 P1 再平衡
        │   ├── LowLatencyDemo.java           ← 🆕 P1 低延迟
        │   ├── ZeroCopyDemo.java             ← 🆕 P3 零拷贝
        │   ├── PageCacheDemo.java            ← 🆕 P3 PageCache
        │   ├── CompressionDemo.java          ← 🆕 P3 消息压缩
        │   └── LogSegmentDemo.java           ← 🆕 P3 日志段管理
        ├── client/                       # 真实 Kafka Client 演示
        │   └── ...                      ← 🆕 P4
        └── interview/                    # 面试题
            └── ...                      ← 🆕 P4
```

## 分步执行计划

### P0：模块创建
- 创建 `rocketmq/pom.xml` 和 `kafka/pom.xml`
- 注册到 `study-middleware/pom.xml` 的 `<modules>` 中
- 依赖仅 Lombok（provided），后续 P4 阶段引入真实 MQ Client

### P1：拆分迁移 + Review + Kafka 日志处理
- 将 study-mq 的 7 个文件按主题拆分，迁移到对应模块的 `simulate/` 包
- 迁移过程中 review + 加深代码质量
- 包路径从 `base.mq` 改为 `com.nopr.mq.rocketmq` / `com.nopr.mq.kafka`
- 新增 Kafka 日志处理 6 个 Demo：
  - **LogCleanupDemo**：日志清理策略（compact vs delete），演示 Key 相同消息的压缩保留
  - **TombstoneDemo**：墓碑消息，value=null 的特殊处理，延迟删除机制
  - **SplitBrainDemo**：脑裂场景，网络分区导致双 Leader，epoch/fencing 机制
  - **BatchSendDemo**：批量发送，linger.ms + batch.size，吞吐 vs 延迟权衡
  - **RebalanceDemo**：再平衡机制，Range/RoundRobin/Sticky/CooperativeSticky 分配策略
  - **LowLatencyDemo**：低延迟优化，零拷贝、PageCache、mmap、sendfile

### P2：RocketMQ 高级特性
- 新增 5 个 simulate Demo：
  - **DeadLetterQueueDemo**：死信队列，消费失败重试 N 次后进入 DLQ
  - **DelayMessageDemo**：延迟消息，18 个延迟级别，内部定时任务实现
  - **MessageFilterDemo**：消息过滤，Tag 过滤 vs SQL92 表达式过滤
  - **MessageTraceDemo**：消息轨迹，生产→存储→消费全链路追踪
  - **ConsumeRetryDemo**：消费重试，阶梯式等待，重试队列 vs 死信队列的关系

### P3：Kafka 存储与性能
- 新增 4 个 simulate Demo：
  - **ZeroCopyDemo**：零拷贝原理，传统 IO vs mmap vs sendfile 数据路径对比
  - **PageCacheDemo**：PageCache 读缓存命中/未命中，刷盘策略对性能影响
  - **CompressionDemo**：消息压缩，gzip/snappy/lz4/zstd，批量+压缩组合
  - **LogSegmentDemo**：日志段管理，segment 切分、索引文件（offset index + time index）

### P4：跨模块 + 真实客户端
- 消费者组再平衡协议演进（跨 RocketMQ/Kafka 对比）
- 高可用一致性对比（NameServer vs ZK vs KRaft）
- Pulsar/RabbitMQ/AMQP 简介（新建 `simulate/` 或单独小模块）
- RocketMQ Client 真实连接演示（`client/` 包）
- Kafka Client 真实连接演示（`client/` 包）
- Kafka interview 面试题补充

### 收尾
- 确认所有文件迁移完毕
- 删除 `study-mq` 模块
- 从 `study-middleware/pom.xml` 移除 `study-mq`

## 类文件头部规范

```java
/**
 * 【模块】rocketmq / kafka
 * 【分类】simulate / client / interview
 * 【主题】简短标题
 * 【描述】详细说明该类演示了什么概念、模拟了什么场景
 * 【关键概念】逗号分隔的关键术语列表
 * 【关联类】@see com.nopr.mq.xxx.XxxDemo
 *
 * @author NoPr
 * @since YYYY-MM-DD
 */
```

## 关键决策记录

| 决策 | 结论 |
|------|------|
| 模块命名 | `rocketmq`、`kafka`（去掉 study- 前缀，父模块已含语境） |
| 包路径 | `com.nopr.mq.rocketmq.*` / `com.nopr.mq.kafka.*` |
| 实现深度 | 混合模式：核心概念手写模拟 + 高级特性真实客户端 |
| 演进策略 | 渐进演进，分 5 阶段交付，每阶段可独立验证 |
| 分包策略 | `simulate/`（手写）、`client/`（真实）、`interview/`（面试） |
| 拆分粒度 | 完全拆分为两个独立 Maven 模块 |
