# 分区选举算法设计与模拟

> 日期：2026-05-16 | 模块：kafka/simulate/

## 目标

新增 2 个 simulate 模拟文件，深入分区选举算法原理：
1. 5 种经典分布式共识选举算法手写模拟
2. Kafka 内部 5 种 LeaderSelector 机制模拟

## 文件设计

### 1. ElectionAlgorithmDemo — 共识算法原理模拟

**包路径：** `com.nopr.mq.kafka.simulate`

| 算法 | 核心机制 | 模拟内容 |
|------|----------|----------|
| **Bully** | 最高 ID 获胜 | 节点发现无 Leader → 向所有更高 ID 发 Election → 超时无响应则自宣告 Leader → 新节点直接挑战 |
| **Ring** | 令牌环传递 | 节点组成逻辑环 → 检测 Leader 失效 → 生成 Election 消息沿环传递 → 最高 ID 胜出 |
| **Paxos** | Prepare/Accept 两阶段 | Proposer+Acceptor+Learner → Prepare(N) → Promise → Accept(N,V) → Accepted → 多数派通过 |
| **ZAB** | ZXID 比较 | 节点投票给 ZXID 最大的候选者 → Leader 发 NEW_LEADER → Follower 同步 ACK → 过半即提交 |
| **Raft** | Term + Vote | Follower 超时变 Candidate → Term+1 → RequestVote → 过半投票 → Leader → AppendEntries 心跳 |

**MQ 对应关系：**
- Raft → KRaft(Kafka 3.3+) / DLedger(RocketMQ 4.5+)
- ZAB → ZooKeeper(Kafka 旧版 Controller 选举)
- Paxos → Pulsar BookKeeper 元数据管理

**实现风格：** 延续现有 simulate 模式——内部类建模 + main 方法演示 + printSection/printRow 表格输出

### 2. KafkaLeaderSelectorDemo — Kafka 5 种 LeaderSelector 模拟

**包路径：** `com.nopr.mq.kafka.simulate`

| Selector | 触发场景 | 模拟内容 |
|------|----------|----------|
| **NoOpLeaderSelector** | 元数据 Topic | 不选 Leader，返回 NoLeader |
| **OfflinePartitionLeader** | 分区下线 / ISR 全宕 | 直接返回 None，Admin 手动介入 |
| **ReassignedPartitionLeader** | 分区重分配 | 从新 AR 的第一个存活副本中选 |
| **PreferredReplicaPartitionLeader** | auto.leader.rebalance | 选 Preferred Replica 为 Leader |
| **ControlledShutdownLeader** | Broker 优雅关闭 | Leader 迁移到其他 ISR 副本 |

**附带：** Kafka Controller 选举流程（ZK 临时节点 / KRaft Quorum）

### 实现细节

- **命名规则：** `ElectionAlgorithmDemo` / `KafkaLeaderSelectorDemo`
- **Javadoc：** 统一【模块】【分类】【主题】【描述】【关键概念】头部模板
- **关联类：** @see KafkaCoreDemo / @see SplitBrainDemo / @see RebalanceDemo

## 范围边界

- ✅ 5 种共识算法 + 5 种 Kafka LeaderSelector
- ✅ 面试 Q&A 在代码末尾
- ❌ 不做 RocketMQ DLedger 独立模拟（已有 KafkaCoreDemo 覆盖）
- ❌ 不做 Pulsar/RabbitMQ 选举（Q03/Q04 已覆盖）