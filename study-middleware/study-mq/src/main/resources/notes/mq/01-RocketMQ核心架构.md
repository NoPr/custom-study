# 01-RocketMQ核心架构

## NameServer 路由架构

```mermaid
graph TD
    subgraph Producer
        P["Producer Group"]
    end

    subgraph NameServer["NameServer 集群 (无状态)"]
        NS1["NameServer-1"]
        NS2["NameServer-2"]
        NS3["NameServer-3"]
    end

    subgraph BrokerCluster["Broker 集群"]
        subgraph MasterSlave1["broker-a"]
            M1["Master (192.168.1.10:10911)"]
            S1["Slave (192.168.1.11:10911)"]
        end
        subgraph MasterSlave2["broker-b"]
            M2["Master (192.168.1.20:10911)"]
            S2["Slave (192.168.1.21:10911)"]
        end
    end

    subgraph Consumer
        C["Consumer Group"]
    end

    P -->|"1.拉取 Topic 路由"| NS1
    P -->|"1.拉取 Topic 路由"| NS2
    P -->|"3.发送消息"| M1
    P -->|"3.发送消息"| M2

    M1 -->|"2.心跳注册 Topic 路由"| NS1
    M1 -->|"2.心跳注册 Topic 路由"| NS2
    M1 -->|"4.主从同步"| S1

    M2 -->|"2.心跳注册 Topic 路由"| NS1
    M2 -->|"2.心跳注册 Topic 路由"| NS2
    M2 -->|"4.主从同步"| S2

    C -->|"1.拉取路由+Broker"| NS1
    C -->|"5.拉取消息"| M1
    C -->|"5.拉取消息"| S1
```

**交互时序：**
1. Broker 每 30s 向所有 NameServer 注册 Topic 路由信息
2. Producer 启动时从 NameServer 拉取 Topic 路由，每 30s 更新
3. Producer 根据路由选择 MessageQueue 发送消息
4. Broker Master 将消息同步到 Slave（同步/异步复制）
5. Consumer 从 Broker 拉取消息（Pull 模式）

## 存储模型

```mermaid
graph LR
    subgraph CommitLog["CommitLog (物理存储)"]
        direction TB
        CL1["[00000000000000000000]<br/>offset=0: msg1(topicA,q0)"]
        CL2["[offset 200]: msg2(topicA,q1)"]
        CL3["[offset 450]: msg3(topicB,q0)"]
        CL4["[offset 700]: msg4(topicA,q2)"]
        CL5["..." ]
    end

    subgraph CQ_A_Q0["ConsumeQueue TopicA-Queue0"]
        CQA0_1["offset=0, size=200, tagHash"]
    end

    subgraph CQ_A_Q1["ConsumeQueue TopicA-Queue1"]
        CQA1_1["offset=200, size=250, tagHash"]
    end

    subgraph CQ_B_Q0["ConsumeQueue TopicB-Queue0"]
        CQB0_1["offset=450, size=250, tagHash"]
    end

    subgraph CQ_A_Q2["ConsumeQueue TopicA-Queue2"]
        CQA2_1["offset=700, size=300, tagHash"]
    end

    CL1 -.->|"索引"| CQA0_1
    CL2 -.->|"索引"| CQA1_1
    CL3 -.->|"索引"| CQB0_1
    CL4 -.->|"索引"| CQA2_1
```

**CommitLog**: 所有 Topic 的消息混合存储，顺序追加写（1GB/文件）
**ConsumeQueue**: 每条 20 字节索引（commitLogOffset+size+tagCode），快速定位

## 核心组件关系

| 组件 | 职责 | 关键配置 |
|------|------|---------|
| NameServer | 路由注册中心，无状态 | 无持久化，靠 Broker 心跳上报 |
| Broker | 消息存储和中转 | flushDiskType(ASYNC/SYNC_FLUSH) |
| CommitLog | 物理消息存储 | 1GB/文件，顺序写，mmap |
| ConsumeQueue | 逻辑队列索引 | 20 字节/条，5亿条约 10GB |
| Producer | 消息生产者 | sendMsgTimeout, retryTimesWhenSendFailed |
| Consumer | 消息消费者（Pull） | pullBatchSize, consumeFromWhere |

## 刷盘与复制策略

| 策略 | 说明 | 可靠性 | 性能 |
|------|------|--------|------|
| ASYNC_FLUSH | 异步刷盘，写 PageCache 返回 | 低 | 高 |
| SYNC_FLUSH | 同步刷盘，fsync 后返回 | 高 | 低 |
| ASYNC_MASTER | 异步主从复制 | 低 | 高 |
| SYNC_MASTER | 同步主从复制 | 高 | 中 |

## 面试要点

1. **NameServer 为什么无状态？** 为了高可用和简化部署，各节点对等，没有选主问题。即使全部挂掉，也不影响已建立的连接。
2. **CommitLog 为什么所有 Topic 混存？** 减少磁盘随机写，物理上只有 CommitLog 一个文件顺序写，相比 Kafka 每个 Partition 一个文件，减少 IO 压力。
3. **ConsumeQueue 为什么是 20 字节？** 紧凑设计：offset(8)+size(4)+tagCode(8)，每个文件可存 30w 条索引（约 5.7MB），加载到内存快。