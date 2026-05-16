# DAG 调度与 Shuffle 机制

## 1. DAG Scheduler 作业划分流程

```mermaid
flowchart TD
    Action["Action 触发 (collect/count/save)"]
    Action --> Job["创建 Job"]
    Job --> Backward["DAGScheduler 逆向回溯 RDD 依赖链"]
    
    Backward --> Check{遇到宽依赖?}

    Check -->|否: 窄依赖| SameStage["加入当前 Stage"]
    Check -->|是: 宽依赖| NewStage["划分新 Stage 边界"]
    
    SameStage --> Backward
    NewStage --> Backward

    NewStage --> GenTask["生成 TaskSet\n(分区数 = Task 数)"]
    GenTask --> Submit["TaskScheduler 提交 Task 到 Executor"]
    Submit --> Executor["Executor 执行 Task\n(数据本地性调度)"]
```

## 2. Job → Stage → Task 实例

```mermaid
graph TD
    subgraph Code["Spark 代码"]
        C1["textFile('hdfs://...')"]
        C2["flatMap(_.split(' '))"]
        C3["map((_, 1))"]
        C4["reduceByKey(_ + _)"]
        C5["filter(_._2 > 10)"]
        C6["collect()"]
        C1 --> C2 --> C3 --> C4 --> C5 --> C6
    end

    subgraph Stage0["Stage 0 (无Shuffle)"]
        T1["Task-0: 读Part0 → flatMap → map"]
        T2["Task-1: 读Part1 → flatMap → map"]
        T3["Task-2: 读Part2 → flatMap → map"]
    end

    subgraph Stage1["Stage 1 (ShuffleMapStage)"]
        S1["Task-0: Shuffle Write → reduceByKey"]
        S2["Task-1: Shuffle Write → reduceByKey"]
    end

    subgraph Stage2["Stage 2 (ResultStage)"]
        R1["Task-0: Shuffle Read → filter → collect"]
        R2["Task-1: Shuffle Read → filter → collect"]
    end

    Stage0 -->|"Shuffle Write"| Stage1
    Stage1 -->|"Shuffle Read"| Stage2
```

## 3. HashShuffle vs SortShuffle

```mermaid
graph TD
    subgraph HashShuffle["HashShuffle (Spark 1.x)"]
        M1["Mapper 1"] -->|文件1| R1["Reducer 1"]
        M1 -->|文件2| R2["Reducer 2"]
        M2["Mapper 2"] -->|文件3| R1
        M2 -->|文件4| R2
        M3["Mapper 3"] -->|文件5| R1
        M3 -->|文件6| R2
    end

    subgraph SortShuffle["SortShuffle (Spark 2.x+)"]
        SM1["Mapper 1"] -->|"数据文件(.data) + 索引(.index)"| SR["Reducer"]
        SM2["Mapper 2"] -->|"数据文件(.data) + 索引(.index)"| SR
    end
```

| 对比 | HashShuffle | SortShuffle |
|------|-----------|------------|
| 文件数 | M * R | 2 * M |
| M=1000,R=1000 | 100万个文件 | 2000个文件 |
| Mapper端排序 | 无 | 有(TimSort) |
| Reducer端 | 自己排序 | 归并已排序数据 |
| 内存占用 | 低 | 需要排序缓冲区 |

## 4. Shuffle Write/Read 完整流程

```mermaid
sequenceDiagram
    participant M as Mapper(Task)
    participant Disk as 本地磁盘
    participant R as Reducer(Task)

    Note over M: === Shuffle Write ===
    M->>M: 1. 内存缓冲区写数据(按partitionId排序)
    M->>M: 2. 缓冲区满 → spill溢写磁盘(临时文件)
    M->>M: 3. 多轮spill后归并合并
    M->>Disk: 4. 写最终数据文件(.shuffle.data)
    M->>Disk: 5. 写索引文件(.shuffle.index)
    M->>M: 6. 上报MapStatus给Driver

    Note over R: === Shuffle Read ===
    R->>M: 7. 从各Mapper fetch指定partition数据
    R->>R: 8. 内存缓存 → 多路归并排序
    R->>R: 9. 聚合计算(reduceByKey)
    R->>Disk: 10. 内存不足时spill磁盘
```

**Shuffle Write 三个阶段：**
1. **内存缓冲** -- 按 (partitionId, key) 排序，写入内存缓冲区
2. **Spill 溢写** -- 缓冲区满则溢写到磁盘临时文件
3. **Merge 合并** -- 多轮 spill 后归并合并为最终数据文件+索引文件

**Shuffle Read 三个阶段：**
1. **Fetch 拉取** -- 从每个 Mapper 节点拉取对应分区的数据块
2. **Merge 归并** -- 将来自多个 Mapper 的数据块进行多路归并排序
3. **Aggregate 聚合** -- 执行 reduceByKey/groupByKey 等聚合操作

## 5. 手写简易 DAGScheduler 核心逻辑

```mermaid
flowchart TD
    Start["schedule(finalRDD)"]
    Start --> Init["createResultStage(finalRDD)"]
    Init --> Recursive["递归划分 Stage"]
    Recursive --> GetParents["getParentStages(rdd)"]
    GetParents --> CheckDep{依赖类型?}
    CheckDep -->|ShuffleDependency| NewStage["创建 ShuffleMapStage"]
    CheckDep -->|NarrowDependency| Same["继续当前 Stage"]
    NewStage --> GetParents
    Same --> GetParents
    GetParents --> Submit["submitStage(stage)"]
    Submit --> Missing{"父Stage存在且\n未完成?"}
    Missing -->|是| Submit
    Missing -->|否| Run["submitMissingTasks(stage)"]
    Run --> Done["完成"]
```

核心代码逻辑：
```java
// 递归按宽依赖划分 Stage
void createStage(RDD rdd) {
    for (Dependency dep : rdd.dependencies()) {
        if (dep instanceof ShuffleDependency) {
            // 宽依赖 → 创建父 Stage
            createStage(dep.rdd);
        }
        // 窄依赖 → 归入当前 Stage
    }
}
```