package base.spark;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DAG Scheduler 作业划分与 Shuffle 机制手写模拟
 *
 * <p>DAG Scheduler 作业划分流程：
 * <ol>
 *   <li><b>Job</b>：每次 Action 操作触发一个 Job</li>
 *   <li><b>Stage</b>：以宽依赖（ShuffleDependency）为边界，逆向划分 Stage</li>
 *   <li><b>Task</b>：每个 Stage 按分区数生成等量 Task，Task 是最小执行单元</li>
 * </ol>
 *
 * <p>Shuffle 机制演进：
 * <ul>
 *   <li><b>HashShuffle（Spark 1.x 默认）</b>：每个 Mapper 为每个 Reducer 创建独立文件
 *       -- M*R 个临时文件，小文件过多，I/O 压力大</li>
 *   <li><b>SortShuffle（Spark 2.x+ 默认）</b>：每个 Mapper 写一个数据文件+一个索引文件
 *       -- 先内存排序(可能 spill 磁盘归并)，再写单文件，Reducer 按索引拉取</li>
 * </ul>
 *
 * <p>Shuffle Write/Read 流程：
 * <ol>
 *   <li><b>Shuffle Write（Mapper 端）</b>：
 *       数据按 partitionId 排序 -> 内存缓冲区 -> spill 溢写磁盘
 *       -> 多文件合并排序 -> 写最终文件+索引文件</li>
 *   <li><b>Shuffle Read（Reducer 端）</b>：
 *       从各 Mapper 节点 fetch 对应分区数据 -> 内存缓存 -> 合并排序 -> 聚合计算</li>
 * </ol>
 *
 * @see RDDDemo RDD弹性分布式数据集
 * @see DataFrameDemo DataFrame与Catalyst优化器
 */
public class DAGShuffleDemo {

    public static void main(String[] args) {
        System.out.println("========== DAG Scheduler 作业划分 & Shuffle 机制演示 ==========\n");

        dagStageDivisionDemo();
        manualDAGSchedulerDemo();
        hashShuffleVsSortShuffleDemo();
        shuffleWriteReadDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 1. DAG 作业划分：Job -> Stage -> Task ====================

    static void dagStageDivisionDemo() {
        System.out.println("--- 1. DAG Scheduler 作业划分 (Job → Stage → Task) ---");

        System.out.println("""
                假设 Spark 应用代码:
                  val rdd1 = sc.textFile("hdfs://...")        // 窄依赖 Stage0
                  val rdd2 = rdd1.flatMap(_.split(" "))       // 窄依赖
                  val rdd3 = rdd2.map((_, 1))                 // 窄依赖
                  val rdd4 = rdd3.reduceByKey(_ + _)           // 宽依赖 ← Stage 分界线!
                  val rdd5 = rdd4.map(x => (x._2, x._1))      // 窄依赖 Stage1
                  val rdd6 = rdd5.sortByKey()                  // 宽依赖 ← Stage 分界线!
                  rdd6.collect()                                // Action → 触发 Job

                DAG 划分过程:
                  Action: collect() 触发 Job
                    └── 从 rdd6 逆向回溯
                          ├── sortByKey (宽依赖) → 划分 Stage2
                          ├── map (窄依赖) → 属于 Stage2
                          ├── reduceByKey (宽依赖) → 划分 Stage1
                          ├── map (窄依赖)
                          ├── flatMap (窄依赖) → 都属于 Stage1
                          └── textFile (窄依赖)
                                      → 划分 Stage0

                Stage 与 Task 对应:
                  ┌─────────┐   ┌──────────────┐   ┌──────────────┐
                  │ Stage 0 │──>│   Stage 1     │──>│   Stage 2    │
                  │textFile  │   │ reduceByKey   │   │  sortByKey   │
                  │flatMap   │   │ (宽依赖Shuffle) │   │ (宽依赖Shuffle) │
                  │map(+1)   │   │ map(swap)     │   │collect()     │
                  │          │   │               │   │              │
                  │ 3个分区  │   │   2个分区     │   │   2个分区    │
                  │→3个Task  │   │ → 2个Task    │   │ → 2个Task   │
                  └─────────┘   └──────────────┘   └──────────────┘
                """);

        System.out.println("  关键: Stage 以 Shuffle 为边界划分, 同一 Stage 内 Pipeline 执行\n");
    }

    // ==================== 2. 手写简易 DAGScheduler ====================

    static void manualDAGSchedulerDemo() {
        System.out.println("--- 2. 手写简易 DAGScheduler (递归 + Stage 提交) ---");

        // 构建一个包含窄依赖和宽依赖的 RDD 依赖图
        DAGNode node0 = new DAGNode("textFile", false, 3);   // Stage0: 源数据, 3分区
        DAGNode node1 = new DAGNode("map(+1)", false, 1);    // 窄依赖
        DAGNode node2 = new DAGNode("reduceByKey", true, 2); // 宽依赖 → Stage1
        DAGNode node3 = new DAGNode("map(swap)", false, 1);  // 窄依赖
        DAGNode node4 = new DAGNode("collect()", false, 1);  // Action

        // 构建依赖关系
        node0.addChild(node1);
        node1.addChild(node2);
        node2.addChild(node3);
        node3.addChild(node4);

        // 执行 DAG 调度
        SimpleDAGScheduler scheduler = new SimpleDAGScheduler();
        List<Stage> stages = scheduler.schedule(node4);

        System.out.println("DAG 划分结果 (" + stages.size() + " 个 Stage):");
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            System.out.printf("  Stage%d: %s | 宽依赖=%s | Task数=%d%n",
                    i, stage.operations, stage.isShuffleBoundary, stage.taskCount);
        }

        // 模拟 Stage 提交执行
        System.out.println("\n提交执行顺序 (Stage0 → StageN, 每 Stage 内 Task 并行):");
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            System.out.printf("  提交 Stage%d: %s (%d 个 Task 并行执行)%n",
                    i, stage.operations, stage.taskCount);
            for (int t = 0; t < stage.taskCount; t++) {
                System.out.printf("    Task%d 执行完毕%n", t);
            }
            if (stage.isShuffleBoundary) {
                System.out.printf("    >>> Stage%d Shuffle 写磁盘, 等待下一 Stage fetch%n", i);
            }
        }

        System.out.println("  关键: DAGScheduler 逆向划分 Stage, Stage 间 Shuffle 数据落盘串联\n");
    }

    // ==================== 3. HashShuffle vs SortShuffle ====================

    static void hashShuffleVsSortShuffleDemo() {
        System.out.println("--- 3. HashShuffle vs SortShuffle 对比 ---");

        // 模拟 Mapper 端数据
        List<KV> mapperData = Arrays.asList(
                new KV("a", 1), new KV("b", 2), new KV("a", 3),
                new KV("c", 4), new KV("b", 5), new KV("a", 6)
        );
        int reducerCount = 2;

        System.out.println("原始 Mapper 数据: " + mapperData);
        System.out.println("Reducer 数量: " + reducerCount);

        // === HashShuffle 模拟 ===
        System.out.println("\n[HashShuffle 方式] 每个 Mapper 为每个 Reducer 创建独立文件:");
        System.out.println("  缺点: M*R 个临时文件, 假设 M=1000, R=1000 → 100万个小文件");

        List<List<KV>> hashBuckets = new ArrayList<>();
        for (int i = 0; i < reducerCount; i++) {
            hashBuckets.add(new ArrayList<>());
        }
        for (KV kv : mapperData) {
            int bucketIdx = Math.abs(kv.key.hashCode()) % reducerCount;
            hashBuckets.get(bucketIdx).add(kv);
        }
        for (int i = 0; i < reducerCount; i++) {
            System.out.printf("  文件_partition_%d.shuffle → %s%n", i, hashBuckets.get(i));
        }
        System.out.println("  临时文件总数: " + reducerCount + " (单 Mapper)");

        // === SortShuffle 模拟 ===
        System.out.println("\n[SortShuffle 方式] Mapper 端内存排序后写单数据文件+单索引文件:");
        System.out.println("  优点: 每 Mapper 只写2个文件 (M*2), 大幅减少文件数");

        // Mapper 端排序
        List<KV> sorted = new ArrayList<>(mapperData);
        sorted.sort(Comparator.comparingInt(kv -> Math.abs(kv.key.hashCode()) % reducerCount));

        // 索引: 记录每个 partition 在文件中的偏移
        Map<Integer, int[]> partitionIndex = new LinkedHashMap<>();
        int offset = 0;
        for (int p = 0; p < reducerCount; p++) {
            int start = offset;
            int count = 0;
            for (KV kv : sorted) {
                if (Math.abs(kv.key.hashCode()) % reducerCount == p) {
                    count++;
                    offset++;
                }
            }
            if (count > 0) {
                partitionIndex.put(p, new int[]{start, count});
            }
        }

        System.out.println("  排序后数据(按partitionId): " + sorted);
        System.out.println("  索引文件 (partition -> [起始偏移, 长度]):");
        partitionIndex.forEach((p, idx) ->
                System.out.printf("    partition_%d -> offset=%d, length=%d%n", p, idx[0], idx[1]));
        System.out.println("  文件总数: 1数据文件 + 1索引文件 = 2 (单 Mapper)");

        System.out.println("\n对比总结:");
        System.out.println("""
                  HashShuffle (Spark 1.x)         SortShuffle (Spark 2.x+)
                ┌──────────────────────┐   ┌──────────────────────────┐
                │ M个文件*R个Reducer   │   │ 每Mapper: 1数据+1索引     │
                │ 小文件多→随机IO压力大 │   │ 内存排序→顺序写→性能优    │
                │ 无排序→Reducer需自己排│   │ Mapper已排序→Reducer合并快│
                └──────────────────────┘   └──────────────────────────┘
                """);
    }

    // ==================== 4. Shuffle Write/Read 流程 ====================

    static void shuffleWriteReadDemo() {
        System.out.println("--- 4. Shuffle Write/Read 完整流程模拟 ---");

        // 模拟 2 个 Mapper 的数据
        List<List<KV>> mapperOutputs = List.of(
                Arrays.asList(
                        new KV("spark", 10), new KV("hadoop", 20),
                        new KV("spark", 30), new KV("flink", 40)),
                Arrays.asList(
                        new KV("hadoop", 50), new KV("spark", 60),
                        new KV("flink", 70), new KV("spark", 80))
        );
        int reducerCount = 2;

        System.out.println("=== Shuffle Write (Mapper 端) ===");
        System.out.println("步骤1: 各 Mapper 处理自身分区数据");
        for (int m = 0; m < mapperOutputs.size(); m++) {
            System.out.printf("  Mapper%d 输出: %s%n", m, mapperOutputs.get(m));
        }

        System.out.println("\n步骤2: 内存缓冲区排序(按 partitionId hash), 溢写磁盘 + 归并合并:");
        for (int m = 0; m < mapperOutputs.size(); m++) {
            List<KV> data = new ArrayList<>(mapperOutputs.get(m));
            data.sort(Comparator.comparingInt(kv -> Math.abs(kv.key.hashCode()) % reducerCount));
            System.out.printf("  Mapper%d 排序后: %s%n", m, data);

            // 模拟 spill 后合并写入
            System.out.printf("    -> 写数据文件 Mapper%d_data.shuffle (含全部partition数据)%n", m);
            for (int r = 0; r < reducerCount; r++) {
                final int partitionId = r;
                List<KV> partitionData = data.stream()
                        .filter(kv -> Math.abs(kv.key.hashCode()) % reducerCount == partitionId)
                        .collect(Collectors.toList());
                if (!partitionData.isEmpty()) {
                    System.out.printf("       partition_%d: %s%n", r, partitionData);
                }
            }
            System.out.printf("    -> 写索引文件 Mapper%d_index.shuffle%n", m);
        }

        System.out.println("\n=== Shuffle Read (Reducer 端) ===");
        System.out.println("步骤3: Reducer 从各 Mapper Fetch 对应分区数据");

        // 模拟每个 Reducer 从所有 Mapper fetch 数据
        for (int r = 0; r < reducerCount; r++) {
            final int partitionId = r;
            System.out.printf("  Reducer%d 从各 Mapper fetch partition_%d 数据:%n", r, r);
            List<KV> fetchedData = new ArrayList<>();
            for (int m = 0; m < mapperOutputs.size(); m++) {
                List<KV> partitionSlice = mapperOutputs.get(m).stream()
                        .filter(kv -> Math.abs(kv.key.hashCode()) % reducerCount == partitionId)
                        .collect(Collectors.toList());
                System.out.printf("    从 Mapper%d 拉取: %s%n", m, partitionSlice);
                fetchedData.addAll(partitionSlice);
            }

            System.out.println("步骤4: 合并排序 (归并排序)");
            fetchedData.sort(Comparator.comparing(kv -> kv.key));
            System.out.printf("    合并排序后: %s%n", fetchedData);

            System.out.println("步骤5: 聚合计算 (reduceByKey)");
            Map<String, Integer> aggregated = new LinkedHashMap<>();
            for (KV kv : fetchedData) {
                aggregated.merge(kv.key, kv.value, Integer::sum);
            }
            System.out.printf("    聚合结果: %s%n%n", aggregated);
        }

        System.out.println("""
                关键流程总结:
                  1. Mapper端 内存排序 -> spill溢写磁盘 -> 归并合并(多轮spill)
                  2. Mapper端 写最终数据文件+索引文件
                  3. Reducer端 从各Mapper fetch指定分区 (连接数 = Mapper数)
                  4. Reducer端 多路归并排序 -> 内存/磁盘混合缓冲区 -> 聚合计算
                """);
    }

    // ==================== 辅助数据结构 ====================

    /** 键值对 -- 模拟 Shuffle 数据 */
    record KV(String key, int value) {}

    /** DAG 节点 -- 模拟 RDD 操作 */
    static class DAGNode {
        String operationName;
        boolean isShuffleDependency;
        int partitionCount;     // 分区数 = Task数
        List<DAGNode> children = new ArrayList<>();
        DAGNode parent;

        DAGNode(String operationName, boolean isShuffleDependency, int partitionCount) {
            this.operationName = operationName;
            this.isShuffleDependency = isShuffleDependency;
            this.partitionCount = partitionCount;
        }

        void addChild(DAGNode child) {
            children.add(child);
            child.parent = this;
        }
    }

    /** Stage -- 包含多个窄依赖操作, 以宽依赖为边界 */
    static class Stage {
        String operations;
        boolean isShuffleBoundary;
        int taskCount;

        Stage(String operations, boolean isShuffleBoundary, int taskCount) {
            this.operations = operations;
            this.isShuffleBoundary = isShuffleBoundary;
            this.taskCount = taskCount;
        }
    }

    /** 简易 DAG Scheduler -- 递归逆向划分 Stage */
    static class SimpleDAGScheduler {

        /**
         * 从 Action 节点逆向回溯, 按宽依赖划分 Stage
         * Stage0 是最先执行的(从源头开始)
         */
        List<Stage> schedule(DAGNode actionNode) {
            List<Stage> stages = new ArrayList<>();
            reverseTraverse(actionNode, stages);
            // 因为递归是从后往前收集的, 实际执行是从前到后
            Collections.reverse(stages);
            return stages;
        }

        private void reverseTraverse(DAGNode node, List<Stage> stages) {
            if (node == null) {
                return;
            }

            // 从当前节点往前, 收集连续窄依赖操作
            List<String> opsInStage = new ArrayList<>();
            DAGNode current = node;
            int taskCount = node.partitionCount;

            while (current != null) {
                opsInStage.add(0, current.operationName);  // 头插保持正向顺序
                taskCount = Math.max(taskCount, current.partitionCount);

                if (current.isShuffleDependency) {
                    // 宽依赖: 当前操作属于上一 Stage, 此处作为 Stage 边界
                    stages.add(new Stage(
                            String.join(" → ", opsInStage), true, taskCount));
                    // 继续往前追溯
                    reverseTraverse(current.parent, stages);
                    return;
                }
                current = current.parent;
            }

            // 到达源头 (无 parent), 最后一个 Stage (Stage0)
            if (!opsInStage.isEmpty()) {
                stages.add(new Stage(
                        String.join(" → ", opsInStage), false, taskCount));
            }
        }
    }
}