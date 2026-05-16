# Watermark 与 Checkpoint

> 水位线生成合并 + Checkpoint Barrier 对齐 + Chandy-Lamport 分布式快照原理。

## 1. Watermark 生成与传递

```mermaid
flowchart LR
    subgraph Source["Source (Kafka)"]
        P0["Partition-0<br/>eventTime=10000"]
        P1["Partition-1<br/>eventTime=8000"]
        P2["Partition-2<br/>eventTime=3000<br/>⚠ Idle"]
    end

    P0 --> |"wm=7000(10000-3000)"| COMB["WatermarkCombiner<br/>合并规则: min(p0,p1,p2)"]
    P1 --> |"wm=5000(8000-3000)"| COMB
    P2 --> |"wm=0(3000-3000)<br/>Idle → 排除"| COMB

    COMB --> |"combined=min(7000,5000)=5000"| OP["下游 Operator<br/>窗口触发条件:<br/>watermark >= window.end"]
```

## 2. Checkpoint Barrier 对齐流程

```mermaid
flowchart TD
    JM["JobManager<br/>触发 Checkpoint #1"] --> |"注入 Barrier"| SRC["Source<br/>Kafka Partition"]

    SRC --> |"Barrier + 数据"| CH0["Channel-0"]
    SRC --> |"Barrier + 数据"| CH1["Channel-1"]

    CH0 --> |"Barrier #1 先到"| MAP["Map Operator<br/>对齐中..."]

    subgraph Align["Barrier 对齐过程"]
        A1["暂停 Channel-0 消费"]
        A2["继续消费 Channel-1 数据<br/>(暂存到 buffer)"]
        A3["等待 Channel-1 Barrier 到达"]
    end

    CH1 --> |"Barrier #1 后到"| MAP
    MAP --> Align

    Align --> |"所有 Channel Barrier 到达"| SNAP["做本地快照<br/>record → operatorSnapshot"]

    SNAP --> |"向下游广播 Barrier"| SINK["Sink Operator"]

    SINK --> |"Barrier #1 到达"| ACK["通知 JM<br/>Checkpoint #1 完成"]
```

## 3. Chandy-Lamport 分布式快照

```mermaid
sequenceDiagram
    participant JM as JobManager
    participant Src as Source
    participant Map as MapOperator
    participant Sink as Sink

    JM->>Src: 注入 Barrier(Marker) ckId=1
    Src->>Src: 记录本地状态<br/>(Kafka Offset=100)
    Src->>Map: 发送 Barrier #1 + 后续数据

    Map->>Map: 收到 Barrier #1<br/>暂停 Channel-0<br/>缓存 Channel-1 数据
    Note over Map: 等待所有 Channel<br/>Barrier 到达

    Map->>Map: 所有 Barrier 到达<br/>记录本地状态<br/>(Key: A→15, B→35)
    Map->>Sink: 发送 Barrier #1

    Sink->>Sink: 收到 Barrier #1<br/>记录本地状态<br/>(已写入: 5条)
    Sink->>JM: ACK: Checkpoint #1 完成

    JM->>JM: 全局快照完成 ✅
```

## 4. Savepoint vs Checkpoint 对比图

```mermaid
graph LR
    subgraph CK["Checkpoint (自动)"]
        CK1["JM 定时触发<br/>interval=5min"]
        CK2["故障恢复专用<br/>完成后自动清理"]
        CK3["格式: 后端原生"]
        CK4["并行度: 必须匹配"]
    end

    subgraph SP["Savepoint (手动)"]
        SP1["CLI/REST 触发<br/>bin/flink savepoint"]
        SP2["升级/迁移/回滚<br/>永久保留"]
        SP3["格式: 标准化<br/>可跨版本"]
        SP4["并行度: 可修改"]
    end

    CK1 -.-> |"恢复"| RESTORE["故障恢复"]
    SP1 -.-> |"升级"| UPGRADE["版本升级"]
    SP1 -.-> |"迁移"| MIGRATE["集群迁移"]

    RESTORE --> |"从最近 CK"| RESUME["继续运行"]
    UPGRADE --> |"从 SP"| RESUME2["新版本运行"]
```

## 5. Exactly-Once 端到端流程

```mermaid
flowchart TD
    subgraph Source["Source: Kafka"]
        S1["读取 Offset=100-199"]
    end

    subgraph Flink["Flink 内部"]
        F1["Barrier 对齐<br/>保证 State 一致性"]
        F2["Checkpoint 快照<br/>State + Offset"]
    end

    subgraph Sink["TwoPhaseCommitSink"]
        K1["Phase 1: preCommit<br/>kafkaProducer.flush()"]
        K2["Phase 2: commit<br/>kafkaProducer.commitTx()"]
        K3["Phase 3(失败): abort<br/>kafkaProducer.abortTx()"]
    end

    S1 --> F1
    F1 --> F2
    F2 --> |"CK 完成"| K1
    K1 --> |"JM 确认"| K2
    K2 --> |"成功"| RESULT["Exactly-Once 达成"]
    K1 --> |"CK 失败"| K3
    K3 --> |"回滚"| ROLLBACK["事务回滚"]
```

## 6. Watermark + Checkpoint 协同

| 机制 | 作用 | 触发周期 |
|------|------|----------|
| Watermark | 处理乱序数据，触发窗口计算 | 每条数据/每200ms |
| Checkpoint | 故障恢复，保证状态一致性 | 每 5min |
| Savepoint | 手动触发的升级/迁移快照 | 按需 |

**协同关系**：
- Watermark 保证数据语义正确（乱序容忍）
- Checkpoint 保证 Exactly-Once（故障恢复不丢不重）
- Savepoint 保证兼容升级（状态跨版本迁移）