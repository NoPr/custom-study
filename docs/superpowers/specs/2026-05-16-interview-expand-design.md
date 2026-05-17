# interview 包扩充设计

> 日期：2026-05-16 | 基于：study-mq-refactor 重构后的 rocketmq/kafka 模块

## 目标

弥补 interview 包短板：Kafka 模块空包（0 题）、RocketMQ 模块缺深度面试题。新增 **7 个面试题文件**，实现原理 + 实战全覆盖。

## 现有基础

| 文件 | 模块 | 主题 |
|------|------|------|
| Q01_RocketMQ_Kafka | rocketmq | RocketMQ vs Kafka 核心对比 |
| Q02_RebalanceComparison | rocketmq | 再平衡协议对比 |
| Q03_HighAvailabilityComparison | rocketmq | 高可用一致性对比 |
| Q04_PulsarRabbitMQIntro | rocketmq | Pulsar/RabbitMQ/AMQP 简介 |

## 新增设计

### Kafka interview/（5 个新文件，从零补齐）

| 文件 | 主题 | 核心覆盖 |
|------|------|----------|
| `Q01_ISR_HW` | ISR 与水位线 | ISR 收缩/扩张条件、HW vs LEO、acks=all/1/0、min.insync.replicas 陷阱、Leader Epoch |
| `Q02_Idempotent_Transaction` | 幂等与事务 | PID+SeqNum 幂等、TransactionCoordinator、Exactly Once 语义、Kafka vs RocketMQ 事务对比 |
| `Q03_Performance_Tuning` | 性能调优 | Producer 端(batch/linger/compression)、Consumer 端(fetch)、Broker 端(线程/flush)、OS 层(PageCache) |
| `Q04_Storage_Design` | 存储设计原理 | 顺序写 vs 随机写、零拷贝 sendfile、PageCache 依赖、LogSegment(.log/.index/.timeindex)、稀疏索引 |
| `Q05_Troubleshooting` | 故障排查实战 | 消息积压(消费慢/分区少/GC)、消息丢失(Producer/Consumer/Broker)、重复消费、延迟飙高 |

### RocketMQ interview/（2 个新文件，补充深度）

| 文件 | 主题 | 核心覆盖 |
|------|------|----------|
| `Q05_Transaction_Deep` | 事务消息深度剖析 | 半消息状态机(PREPARE→COMMIT/ROLLBACK)、回查时序、vs Kafka 事务对比、半消息堆积/回查风暴 |
| `Q06_5x_NewFeatures` | 5.0 新特性 | Controller 模式、POP 消费、Proxy(gRPC)、轻量级消息队列 |

## 实现细节

- **文件格式**：延续现有 Q 系列风格——单 main 方法、printSection/printRow 表格、面试要点 Q&A
- **命名规则**：Kafka 模块独立从 Q01 开始编号
- **包路径**：`com.nopr.mq.kafka.interview` / `com.nopr.mq.rocketmq.interview`
- **Javadoc**：统一使用【模块】【分类】【主题】【描述】【关键概念】头部模板