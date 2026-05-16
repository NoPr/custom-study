package base.spark.interview;

import base.spark.BroadcastJoinDemo;
import base.spark.DAGShuffleDemo;
import base.spark.DataFrameDemo;
import base.spark.RDDDemo;

/**
 * Spark 核心面试题汇总 -- 5 大高频问题手写模拟讲解
 *
 * <p>本类整合以下面试题：
 * <ol>
 *   <li><b>RDD Lineage（血统）vs Checkpoint</b>：区别、各自适用场景、为什么 Checkpoint 能截断血统</li>
 *   <li><b>DAG 执行流程 (Job/Stage/Task)</b>：从 Action 触发到 Task 调度执行的全链路</li>
 *   <li><b>Catalyst 优化器四阶段</b>：Analysis → Logical Optimize → Physical Plan → Code Generation</li>
 *   <li><b>Spark vs MR vs Flink</b>：计算模型的本质差异、各自适用场景</li>
 *   <li><b>Broadcast 与 Shuffle 对比</b>：原理、优劣、适用条件</li>
 * </ol>
 *
 * <p>每个问题独立方法，可直接运行验证理解程度。
 *
 * @see RDDDemo RDD弹性分布式数据集
 * @see DAGShuffleDemo DAG调度与Shuffle机制
 * @see DataFrameDemo DataFrame与Catalyst优化器
 * @see BroadcastJoinDemo Broadcast/Accumulator/Join策略
 */
public class Q01_Spark_Core {

    public static void main(String[] args) {
        System.out.println("========== Spark 核心面试题 5 连击 ==========\n");

        q1RddLineageVsCheckpoint();
        q2DagExecutionFlow();
        q3CatalystFourStages();
        q4SparkVsMRVsFlink();
        q5BroadcastVsShuffle();

        System.out.println("\n========== 面试题演示完毕 ==========");
    }

    // ==================== 面试题 1: RDD Lineage vs Checkpoint ====================

    /**
     * Q1: RDD Lineage（血统）和 Checkpoint 的区别？什么场景用哪个？
     *
     * <pre>
     * Lineage (血统机制):
     *   - RDD 不可变, 每个 RDD 记录父 RDD 和变换操作
     *   - 分区丢失时, 通过 Lineage 从源头重算
     *   - 优点: 零额外存储成本, 自动容错
     *   - 缺点: 链越长重算越慢, Shuffle 后链路复杂
     *
     * Checkpoint (检查点机制):
     *   - 将 RDD 物化到可靠存储(如 HDFS), 截断血统
     *   - 丢失时从 Checkpoint 直接恢复, 无需回溯
     *   - 优点: 断点恢复快, 适合长链或迭代计算
     *   - 缺点: 需要额外存储空间 + 物化开销
     *
     * 选择策略:
     *   - 短链 + 数据不大 → Lineage 足够
     *   - 长链 (10+ 依赖) → 建议 Checkpoint
     *   - 迭代计算 (ML/GraphX) → 每 N 次迭代 Checkpoint
     *   - Shuffle 后 → 建议 Checkpoint (Shuffle 后重算代价大)
     * </pre>
     */
    static void q1RddLineageVsCheckpoint() {
        System.out.println("=== 面试题 1: RDD Lineage vs Checkpoint ===\n");

        System.out.println("""
                面试标准回答:

                Lineage 是 RDD 的容错机制, 记录每个 RDD 的"父亲是谁"和"怎么计算出来"。
                当某个分区丢失时, Spark 沿着 Lineage 链路从最近的可用祖先 RDD 重新计算该分区。
                代价：链越长重算越慢, 特别是 Shuffle 后的链路。

                Checkpoint 是"快照机制", 将 RDD 物化到 HDFS/本地磁盘, 并切断其与父 RDD 的血统关系。
                之后该 RDD 的父依赖指向一个 CheckpointRDD, 丢失分区直接从 Checkpoint 恢复。
                代价：需要额外存储空间和物化时的 I/O 开销, 但恢复速度极快。

                核心区别：
                1. Lineage 是"轻量级容错", 牺牲恢复速度换取零存储成本, 适合短链
                2. Checkpoint 是"重量级快照", 牺牲存储换取恢复速度, 适合长链/迭代

                实际场景：
                - PageRank 迭代计算: 每 10 轮 Checkpoint 一次, 既避免全链重算, 又不至于每轮都落盘
                - Shuffle 后的 RDD: Shuffle 代价高, 对其 Checkpoint 避免重新 Shuffle
                - 正常 ETL: 链长 < 5 个操作, Lineage 足够

                面试加分: 提到 Checkpoint 会触发额外 Job 执行, 且第一个 Job 完成后才截断血统
                """);
    }

    // ==================== 面试题 2: DAG 执行流程 ====================

    /**
     * Q2: 描述从 Action 触发到 Task 调度执行的全流程。
     *
     * <pre>
     * 完整流程:
     *   1. Action 触发 → SparkContext.runJob()
     *   2. DAGScheduler 逆向划分 Stage (以 ShuffleDependency 为边界)
     *   3. 每个 Stage 生成 TaskSet (分区数 = Task 数)
     *   4. TaskScheduler 将 Task 分发到 Executor
     *   5. Executor 反序列化 Task → 执行 → 返回结果
     *
     * 核心概念:
     *   - Job: 一个 Action 对应一个 Job (count/collect/save 等都触发 Job)
     *   - Stage: 以 Shuffle 为边界的 Task 集合, 同一 Stage 内 Pipeline 执行
     *   - Task: 最小执行单元, 每个分区一个 Task
     *
     * 窄依赖 Stage (ShuffleMapStage): 产生 Shuffle 数据, 供下游使用
     * 宽依赖 Stage (ResultStage): 产生最终结果, 返回 Driver
     * </pre>
     */
    static void q2DagExecutionFlow() {
        System.out.println("=== 面试题 2: DAG 执行流程 (Job/Stage/Task) ===\n");

        System.out.println("""
                面试标准回答:

                从一段代码开始：
                  val rdd = sc.textFile("hdfs://...")
                    .flatMap(_.split(" "))
                    .map((_, 1))
                    .reduceByKey(_ + _)   // ← 宽依赖, Stage 边界
                    .collect()            // ← Action, 触发 Job

                全流程分四步：

                Step 1 - Action 触发 Job
                  collect() 调用 SparkContext.runJob(), 创建 ActiveJob

                Step 2 - DAGScheduler 划分 Stage
                  从 Action 对应 RDD 开始逆向回溯：
                  - reduceByKey 是 ShuffleDependency → 划分 Stage 边界
                  - map → flatMap → textFile 都是 NarrowDependency → 在同一个 Stage
                  结果: Stage0 = [textFile, flatMap, map] → Stage1 = [reduceByKey, collect]

                Step 3 - 生成 TaskSet 并提交
                  Stage0: 假设 textFile 有 3 个分区 → 生成 3 个 ShuffleMapTask
                  Stage1: reduceByKey 后 2 个分区 → 生成 2 个 ResultTask
                  TaskScheduler 按数据本地性(data locality)调度 Task

                Step 4 - Executor 执行 Task
                  Executor 收到 Task 后反序列化
                  → 运行 task.run() (读取数据→计算→输出Shuffle/结果)
                  → 返回结果给 Driver

                面试加分:
                  - 数据本地性优先级: PROCESS_LOCAL > NODE_LOCAL > RACK_LOCAL > ANY
                  - Task 失败重试机制: 默认重试 3 次(spark.task.maxFailures)
                  - Speculative Execution(推测执行): 慢 Task 在另一节点启动副本
                """);
    }

    // ==================== 面试题 3: Catalyst 优化器 ====================

    /**
     * Q3: Catalyst 优化器的四个阶段分别做什么？
     *
     * <pre>
     * 四阶段:
     *   1. Analysis: 解析 SQL/DataFrame → 未解析逻辑计划 → 解析Schema/表/列
     *      - 使用 SessionCatalog 解析表名、列名、函数名
     *      - 生成 Resolved Logical Plan
     *
     *   2. Logical Optimize: 应用规则优化逻辑计划
     *      - 谓词下推(Predicate Pushdown): 过滤条件推到数据源
     *      - 列裁剪(Column Pruning): 只读需要的列
     *      - 常量折叠(Constant Folding): 编译时计算常量表达式
     *      - 投影合并/过滤合并等规则
     *
     *   3. Physical Plan: 选择最优物理执行计划
     *      - Join 策略选择 (BroadcastHashJoin/SortMergeJoin/...)
     *      - 成本模型(CBO)评估, 选择代价最低方案
     *
     *   4. Code Generation: 整阶段代码生成
     *      - Whole-Stage CodeGen: 融合多个算子为单个函数
     *      - Janino 编译器编译为字节码
     *      - 消除虚函数调用和中间对象
     * </pre>
     */
    static void q3CatalystFourStages() {
        System.out.println("=== 面试题 3: Catalyst 优化器四阶段 ===\n");

        System.out.println("""
                面试标准回答:

                Catalyst 是 Spark SQL 的核心优化引擎, 基于规则(Rule-Based)和成本(Cost-Based)
                进行查询优化, 整个过程分四个阶段：

                阶段1 - Analysis (分析):
                  输入: SQL字符串/DataFrame DSL
                  任务: 解析语法树, 通过 SessionCatalog 解析表名/列名/函数
                    例如: WHERE age > 25, 需要确认 age 列存在, 类型是 Int
                  输出: Resolved Logical Plan (已解析的逻辑计划)

                阶段2 - Logical Optimize (逻辑优化):
                  输入: Resolved Logical Plan
                  任务: 应用标准优化规则 (共有 50+ 条规则)
                    - 谓词下推: WHERE age > 25 推到数据源 Scan 节点
                    - 列裁剪: 只读 SELECT 需要的列, 忽略无关列
                    - 常量折叠: price * 0.9 > 100 → price > 111.11
                    - 投影裁剪: 提前删除后续不再用的列
                  输出: Optimized Logical Plan

                阶段3 - Physical Plan (物理计划):
                  输入: Optimized Logical Plan
                  任务: 将逻辑算子映射为物理算子, 生成多个候选物理计划
                    - Join: BroadcastHashJoin / SortMergeJoin / ShuffledHashJoin
                    - Scan: FileScan / DataSourceScan
                    - Aggregate: HashAggregate / SortAggregate
                  若启用 CBO(Cost-Based Optimizer), 用统计信息评估各计划代价, 选最优
                  输出: Physical Plan (可能多个, 选最优)

                阶段4 - Code Generation (代码生成):
                  输入: Physical Plan
                  任务: Whole-Stage CodeGen 将多个物理算子融合成单个函数
                    例如: scan → filter → project 融合为一个 for 循环
                    使用 Janino 编译器编译为 Java 字节码
                  输出: 编译后的 Executable Plan (RDD DAG)

                面试加分:
                  - Tungsten: 堆外内存 + 列式存储 + 代码生成, 与 Catalyst 协同
                  - 可通过 explain(true) 查看完整执行计划
                  - CBO 需要 ANALYZE TABLE 收集统计信息后才生效
                """);
    }

    // ==================== 面试题 4: Spark vs MR vs Flink ====================

    /**
     * Q4: Spark 和 MapReduce、Flink 的核心区别是什么？
     *
     * <pre>
     * Spark vs MR:
     *   - 计算模型: MR 只有 Map 和 Reduce 两阶段, Spark 支持 DAG 多阶段
     *   - 中间结果: MR 必须落盘(HDFS), Spark 可内存缓存(但 Shuffle 也落盘)
     *   - 执行效率: MR 每个操作启动新 JVM, Spark 复用 Executor 进程
     *   - 编程模型: MR 只有低级 API, Spark 有 RDD/DataFrame/SQL 多级 API
     *
     * Spark vs Flink:
     *   - 设计理念: Spark 微批次(Micro-Batch), Flink 逐事件(Event-by-Event)
     *   - 延迟: Spark Streaming 秒级, Flink 毫秒级
     *   - 状态管理: Spark 依赖外部存储/Checkpoint, Flink 有内置 State Backend
     *   - 恰好一次: Spark 靠幂等+Checkpoint, Flink 靠 Checkpoint Barrier 对齐
     *   - 批处理: Spark 原生优势, Flink 批流统一但批不如 Spark 成熟
     * </pre>
     */
    static void q4SparkVsMRVsFlink() {
        System.out.println("=== 面试题 4: Spark vs MR vs Flink ===\n");

        System.out.println("""
                面试标准回答:

                ┌──────────┬─────────────────┬──────────────────┬─────────────────┐
                │ 维度     │ MapReduce        │ Spark            │ Flink           │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 计算模型 │ Map+Reduce 两阶段│ DAG 多阶段        │ DAG 流式算子    │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 中间存储 │ 全部落盘 HDFS    │ 内存(可落盘)     │ 内存(State存储) │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 延迟     │ 分钟级(批处理)  │ 秒级(Micro-Batch)│ 毫秒级(逐事件)  │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 吞吐     │ 一般            │ 高(Batch优势)    │ 高(流原生)      │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 容错     │ Task重算        │ Lineage重算       │ Checkpoint+Save│
                │          │                 │ +Checkpoint      │ point           │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ API      │ Java MR API     │ RDD/DF/DS/ML/SQL │ DataStream/Tabl │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 资源调度 │ YARN            │ YARN/K8s/Mesos   │ YARN/K8s        │
                ├──────────┼─────────────────┼──────────────────┼─────────────────┤
                │ 典型场景 │ 大文件批处理    │ ETL/ML/BI 分析   │ 实时大屏/风控   │
                └──────────┴─────────────────┴──────────────────┴─────────────────┘

                Spark vs MR 的本质差异:
                  MR 每个阶段(Map/Reduce)都必须落盘, 且每个 Job 重新启动进程,
                  导致磁盘 I/O 密集、启动开销大。
                  Spark 通过 DAG 将多个阶段串联, 中间结果放内存, 使用常驻 Executor
                  进程, 一个 Spark 应用只需要一次资源申请, 大幅减少开销。

                Spark vs Flink 的本质差异:
                  Spark 设计初衷是批处理引擎, 流处理是后来"批的延展"(micro-batch),
                  将无限数据流切割为一个个小批次处理, 延迟在数百毫秒以上。
                  Flink 设计初衷是流处理引擎, 批是"流的特例"(有界流),
                  每个事件到达即处理, 延迟可达毫秒级, 状态管理也更原生。
                  虽然 Spark Structured Streaming 也在向持续处理靠拢, 但架构根基因不同。

                选型建议:
                  - 离线 ETL/ML/BI → Spark (生态成熟, SQL 优化好)
                  - 实时风控/大屏/CEP → Flink (毫秒延迟, 状态原生)
                  - 简单批处理/遗留系统 → MR (逐步迁移到 Spark)
                """);
    }

    // ==================== 面试题 5: Broadcast vs Shuffle ====================

    /**
     * Q5: Broadcast Join 和 Shuffle Join 的区别？什么时候用 Broadcast？
     *
     * <pre>
     * Broadcast Join (广播 Join):
     *   原理: 小表广播到每个 Executor 节点, 大表逐行在本地 HashMap 中查找
     *   网络: 小表广播一次 (N条记录 * 节点数), 大表无需网络传输
     *   内存: 小表需全部放入内存
     *   适用: 小表 < 10MB (spark.sql.autoBroadcastJoinThreshold)
     *
     * Shuffle Join (含 SortMergeJoin):
     *   原理: 两表都按 Join Key 重分区(Shuffle), 同 Key 落到同一节点再 Hash/Sort Join
     *   网络: 两表全部数据都经过网络 Shuffle
     *   内存: 无需全表放入内存, 分批处理
     *   适用: 两表都大, 无法广播
     *
     * 对比:
     *   Broadcast: 网络开销小(只传小表), 但需要小表能全放内存
     *   Shuffle: 无内存限制, 但网络开销大(全量Shuffle)
     * </pre>
     */
    static void q5BroadcastVsShuffle() {
        System.out.println("=== 面试题 5: Broadcast Join vs Shuffle Join ===\n");

        System.out.println("""
                面试标准回答:

                Broadcast Join (Map Side Join):
                  - 原理: 将小表(维度表)广播到所有 Executor 的内存中构建 HashMap,
                    大表(事实表)每条记录直接在本地 HashMap 中查找匹配的维度数据
                  - 网络: 只需传输小表数据(一次广播), 大表完全不走网络
                  - 条件: 小表大小 < spark.sql.autoBroadcastJoinThreshold (默认 10MB)
                  - 优势: 零 Shuffle, 网络成本 O(小表大小), 执行快
                  - 限制: 小表必须能全放入内存 (broadcast timeout=300s)

                Shuffle Join (含 SortMerge Join / ShuffledHashJoin):
                  - 原理: 大表和大表 Join, 需要按 Join Key 将两边数据重新分区
                    同 Key 的数据跨网络 Shuffle 到同一 Executor, 然后本地 Join
                  - 网络: 两表所有数据都要经过网络 Shuffle, 成本 O(M+N)
                  - 特点: 不受单表内存限制, 可处理 TB 级数据
                  - 代价: 网络 I/O 巨大, 写入磁盘, 速度慢

                SortMerge Join (默认 Shuffle Join):
                  - 两表先按 Join Key 排序, 再归并匹配
                  - 优势: 内存可控(不需要全表 Hash), 两表都有序时可跳过排序
                  - 适用: 两表都很大的情况

                什么时候用 Broadcast?
                  1. 事实表 JOIN 维度表(地区/品类/时间维度)
                  2. 小表 < 10MB (可调大阈值)
                  3. 手动指定 Hint: /*+ BROADCAST(small_table) */
                  4. 禁用自动广播: spark.sql.autoBroadcastJoinThreshold = -1

                核心权衡:
                  广播 = 用内存换网络 (小表放内存, 大表不走网络)
                  Shuffle = 用网络换通用性 (不受内存限制, 但网络昂贵)

                面试加分:
                  - Broadcast 也有变体: Broadcast Nested Loop Join (笛卡尔积兜底策略)
                  - 在 Spark 3.0+ 中, 引入了 AQE(Adaptive Query Execution),
                    可以在运行时根据中间数据统计动态切换 Join 策略
                  - AQE 可能将 SortMergeJoin 在运行时转为 BroadcastJoin (如果发现某分区数据量变小)
                """);
    }
}