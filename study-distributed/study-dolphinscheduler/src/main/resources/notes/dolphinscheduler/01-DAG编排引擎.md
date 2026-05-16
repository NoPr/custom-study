# 01-DAG 编排引擎

## DAG 封装模型

```mermaid
graph TD
    subgraph 用户层["Web UI 定义 DAG"]
        UI["拖拽任务节点 + 连线定义依赖"]
    end

    subgraph API["API Server"]
        JSON["DAG JSON 定义"]
        Valid["合法性校验: 无环 + 参数完整 + 节点有效"]
    end

    subgraph Master["Master 节点"]
        Parse["解析 JSON -> 内存 DAG 图"]
        Topo["Kahn 拓扑排序(BFS+入度表)"]
        Layer["分层: Layer0(入度0) -> Layer1 -> ... -> LayerN"]
    end

    subgraph Worker["Worker 节点"]
        W1["Worker-1: Layer0 任务并行执行"]
        W2["Worker-2: Layer0 任务并行执行"]
        W3["Worker-3: Layer1 任务执行(上游完成后触发)"]
    end

    subgraph ZK["ZooKeeper"]
        Lock["分布式锁: 防重复提交"]
        HB["Worker 心跳 /nodes/worker/*"]
    end

    UI -->|"1. 提交 DAG"| API
    API -->|"2. 持久化 MySQL"| MASTER_DB["MySQL"]
    API -->|"3. 生成 ProcessInstance"| Master
    Master -->|"4. 拓扑排序分批次"| Topo
    Topo -->|"5. Layer0 分发"| W1
    Topo -->|"5. Layer0 分发"| W2
    W1 -->|"6. 状态回调"| Master
    W2 -->|"6. 状态回调"| Master
    Master -->|"7. Layer1 触发"| W3
    Master <-->|"分布式锁"| ZK
    W1 <-->|"心跳上报"| ZK
    W2 <-->|"心跳上报"| ZK
    W3 <-->|"心跳上报"| ZK
```

## Kahn 拓扑排序核心算法

```mermaid
flowchart LR
    Start["开始"] --> Build["构建入度表: Map<Node, Integer>"]
    Build --> Find["找入度=0的节点 -> 入队(就绪队列)"]
    Find --> Loop{"队列非空?"}
    Loop -->|"是"| Poll["出队节点 N, 加入当前层"]
    Poll --> Iter{"遍历 N 的所有后继 S"}
    Iter -->|"每个后继"| Dec["S.入度 -= 1"]
    Dec --> Check{"S.入度 == 0?"}
    Check -->|"是"| Enqueue["S 入队"]
    Enqueue --> Iter
    Check -->|"否"| Iter
    Iter -->|"遍历完毕"| Loop
    Loop -->|"否"| Verify{"已处理节点数 == 总节点数?"}
    Verify -->|"是"| Output["输出分层列表"]
    Verify -->|"否, 存在环"| Error["抛出异常: DAG 循环依赖"]
```

### 算法复杂度

| 指标 | 值 |
|------|-----|
| 时间复杂度 | O(V + E) |
| 空间复杂度 | O(V) |
| 环检测 | 处理节点数 < V 则存在环 |

## 窄依赖 vs 宽依赖

```mermaid
graph TD
    subgraph Narrow["窄依赖 (map)"]
        direction TB
        N_Parent1["父分区0"] --> N_Child1["子分区0"]
        N_Parent2["父分区1"] --> N_Child2["子分区1"]
        N_Parent3["父分区2"] --> N_Child3["子分区2"]
    end

    subgraph Wide["宽依赖 (groupByKey-like)"]
        direction TB
        W_Parent1["父分区0"] --> W_Child["子分区0"]
        W_Parent2["父分区1"] --> W_Child
        W_Parent3["父分区2"] --> W_Child
    end
```

| 依赖类型 | 特征 | Shuffle | DolphinScheduler 表现 |
|---------|------|---------|----------------------|
| 窄依赖 | 1:1 或 N:1(父分区->子分区) 一对一 | 无 | 不需要数据重分布，pipeline 执行 |
| 宽依赖 | N:M 或多父->1子 | 有 | 上游全部分区完成后，下游才可执行 |

## 条件分支

```mermaid
flowchart TD
    Source["Source 数据源读取"] --> QC["QualityCheck 质量检查"]
    QC -->|"qualityScore > 0.8"| High["高质量处理"]
    QC -->|"qualityScore <= 0.8"| Low["低质量告警"]
    High --> Merge["数据聚合"]
    Low --> Merge
    Merge --> Load["数据加载"]
```

## 子工作流 SubProcess

```mermaid
graph TD
    subgraph Main["主工作流: ML Training"]
        DL["DataLoad 加载数据集"] --> PRE["Preprocess 数据预处理"]
        PRE --> TRAIN["Train 模型训练"]
        TRAIN --> EVAL["Eval 模型评估"]
    end

    PRE -.->|"嵌套子 DAG"| SUB

    subgraph SUB["子工作流: Preprocess"]
        direction LR
        S1["去重"] --> S2["补齐缺失值"] --> S3["归一化"]
    end
```

## 面试要点

1. **Kahn 拓扑排序为什么用 BFS 而不是 DFS？** BFS 天然支持分层，同一层入度同时为 0 的任务可并行调度。DFS 只能得到一条有效序列，但无法表达并行关系。

2. **DolphinScheduler 的 DAG 定义支持哪种数据类型？** JSON。Web UI 拖拽后将 DAG 结构序列化为 JSON，存储在 MySQL `t_ds_process_definition` 表中。

3. **如何保证 DAG 不会重复执行？** ZooKeeper 分布式锁。提交时 Master 获取 processInstance 的锁，同一实例不会同时被两个 Master 调度。

4. **DAG 中的条件节点是如何实现的？** 上游任务执行完毕后，Master 根据其输出参数 (全局参数 varPool) 评估条件表达式，决定走 succeed / failed 分支。条件节点的两个分支在 JSON 中都有定义。