package base.spark;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RDD 弹性分布式数据集核心原理手写模拟
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>弹性</b>：数据可内存/磁盘存储，故障自动恢复</li>
 *   <li><b>分布式</b>：数据分布在多个节点，分区并行计算</li>
 *   <li><b>数据集</b>：只读、可分区的记录集合</li>
 * </ul>
 *
 * <p>RDD 编程模型：
 * <ul>
 *   <li><b>Transformation（转换）</b>：map / filter / flatMap -- 惰性求值，构建 DAG 血缘</li>
 *   <li><b>Action（行动）</b>：reduce / collect / count -- 触发实际计算，返回结果或写存储</li>
 * </ul>
 *
 * <p>依赖关系：
 * <ul>
 *   <li><b>窄依赖（Narrow Dependency）</b>：父 RDD 的每个分区最多被一个子 RDD 分区使用
 *       -- map/filter/union（已知分区不变或简单合并）</li>
 *   <li><b>宽依赖（Wide/Shuffle Dependency）</b>：父 RDD 的每个分区被多个子 RDD 分区使用
 *       -- reduceByKey/groupByKey/join（需要跨节点数据重分布）</li>
 * </ul>
 *
 * <p>Lineage（血统）与 Checkpoint：
 * <ul>
 *   <li><b>Lineage</b>：记录 RDD 转换操作的有向无环图（DAG），用于故障时重算丢失分区</li>
 *   <li><b>Checkpoint</b>：将 RDD 物化到可靠存储（如 HDFS），截断血缘链，避免长链重算开销</li>
 *   <li>区别：Lineage 重算从头推导，Checkpoint 从断点直接恢复</li>
 * </ul>
 *
 * <p>分区计算移动而非数据移动原则：计算 Task 调度到数据所在节点执行，减少网络 I/O。
 *
 * @see DAGShuffleDemo DAG调度与Shuffle机制
 * @see DataFrameDemo DataFrame与Catalyst优化器
 */
public class RDDDemo {

    public static void main(String[] args) {
        System.out.println("========== RDD 弹性分布式数据集核心原理演示 ==========\n");

        rdTransformationDemo();
        rdActionDemo();
        rdLazyEvaluationDemo();
        rdNarrowVsWideDependencyDemo();
        rdLineageVsCheckpointDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== Transformation（转换算子）====================

    static void rdTransformationDemo() {
        System.out.println("--- 1. Transformation 转换算子 (map / filter / flatMap) ---");

        SimRDD<Integer> numbers = new SimRDD<>(Arrays.asList(1, 2, 3, 4, 5));
        System.out.println("原始数据: " + numbers.data);

        // map: 每个元素映射为新元素
        SimRDD<Integer> squares = numbers.map(x -> x * x);
        System.out.println("map(x => x*x): " + squares.data);

        // filter: 保留满足条件的元素
        SimRDD<Integer> evens = numbers.filter(x -> x % 2 == 0);
        System.out.println("filter(x % 2 == 0): " + evens.data);

        // flatMap: 每个元素映射为集合后展平
        SimRDD<String> words = new SimRDD<>(Arrays.asList("hello world", "java spark"));
        System.out.println("\n原始词组: " + words.data);
        SimRDD<String> flattened = words.flatMap(s -> Arrays.asList(s.split(" ")));
        System.out.println("flatMap(按空格拆分): " + flattened.data);

        System.out.println("  关键: Transformation 仅记录操作链(DAG), 不触发计算\n");
    }

    // ==================== Action（行动算子）====================

    static void rdActionDemo() {
        System.out.println("--- 2. Action 行动算子 (reduce / collect / count) ---");

        SimRDD<Integer> numbers = new SimRDD<>(Arrays.asList(1, 2, 3, 4, 5));
        System.out.println("数据: " + numbers.data);

        // reduce: 聚合所有元素
        int sum = numbers.reduce(Integer::sum);
        System.out.println("reduce(求和): " + sum);

        // count: 统计元素数量
        long cnt = numbers.count();
        System.out.println("count(): " + cnt);

        // collect: 收集所有元素到 Driver 端
        List<Integer> collected = numbers.collect();
        System.out.println("collect(): " + collected);

        System.out.println("  关键: Action 触发 DAG 回溯求值, 从后往前推算依赖链\n");
    }

    // ==================== 惰性求值 ====================

    static void rdLazyEvaluationDemo() {
        System.out.println("--- 3. 惰性求值 Lazy Evaluation ---");

        SimRDD<Integer> numbers = new SimRDD<>(Arrays.asList(1, 2, 3, 4, 5));

        List<String> operationLog = new ArrayList<>();

        // 连续多个 Transformation —— 此时无任何计算发生
        SimRDD<Integer> step1 = numbers.map(x -> {
            operationLog.add("map阶段: x=" + x + " -> " + (x * 2));
            return x * 2;
        });

        SimRDD<Integer> step2 = step1.filter(x -> {
            operationLog.add("filter阶段: x=" + x + " 保留=" + (x > 5));
            return x > 5;
        });

        SimRDD<Integer> step3 = step2.map(x -> {
            operationLog.add("map2阶段: x=" + x + " -> " + (x + 10));
            return x + 10;
        });

        System.out.println("连续3个 Transformation 后, 操作日志数量: " + operationLog.size()
                + " (应为0 -- 惰性求值, 未触发计算)");

        // Action 触发瀑布式求值
        List<Integer> result = step3.collect();
        System.out.println("collect() 触发后结果: " + result);
        System.out.println("操作日志 (" + operationLog.size() + " 条):");
        operationLog.forEach(log -> System.out.println("  " + log));

        System.out.println("  关键: Lazy Evaluation 避免中间结果存储, 优化整体执行计划（流水线+算子合并）\n");
    }

    // ==================== 窄依赖 vs 宽依赖 ====================

    static void rdNarrowVsWideDependencyDemo() {
        System.out.println("--- 4. 窄依赖 vs 宽依赖 ---");

        System.out.println("""
                窄依赖 (Narrow Dependency):
                ┌───────┐   map/filter   ┌───────┐
                │ Part0 │ ──────────────> │ Part0 │
                │ Part1 │ ──────────────> │ Part1 │
                │ Part2 │ ──────────────> │ Part2 │
                └───────┘   (一对一映射)   └───────┘
                 父RDD                       子RDD
                特点: 每个父分区最多被一个子分区使用, 无需Shuffle, 同节点 pipeline 执行

                宽依赖 (Wide/Shuffle Dependency):
                ┌───────┐   groupByKey     ┌───────┐
                │ Part0 │ ──┬────────────> │ Part0 │
                │ Part1 │ ──┼────────────> │ Part1 │  (数据跨节点重新分布)
                │ Part2 │ ──┘             │ Part2 │
                └───────┘                  └───────┘
                 父RDD                       子RDD
                特点: 父分区可能被多个子分区使用, 触发 Shuffle(写磁盘+网络传输)
                """);

        // 模拟窄依赖 map：分区一一对应
        List<List<Integer>> parentPartitions = List.of(
                List.of(1, 2, 3),
                List.of(4, 5, 6),
                List.of(7, 8, 9)
        );

        System.out.println("窄依赖模拟 (每个分区独立 map):");
        for (int i = 0; i < parentPartitions.size(); i++) {
            List<Integer> mapped = parentPartitions.get(i).stream()
                    .map(x -> x * 10)
                    .collect(Collectors.toList());
            System.out.printf("  分区%d: %s -> %s (无跨分区操作)%n",
                    i, parentPartitions.get(i), mapped);
        }

        // 模拟宽依赖 groupBy：需要跨分区聚合
        System.out.println("\n宽依赖模拟 (groupBy -- 需要跨分区 Shuffle):");
        // 每个分区有不同 key 的局部数据, groupBy 需要将相同 key 汇集到同一分区
        record KV(String key, int value) {}
        List<List<KV>> shufflePartitions = List.of(
                List.of(new KV("a", 1), new KV("b", 2), new KV("a", 3)),
                List.of(new KV("b", 4), new KV("c", 5), new KV("a", 6)),
                List.of(new KV("c", 7), new KV("b", 8), new KV("c", 9))
        );

        // 模拟 Shuffle: 按 key 哈希分区重分布
        Map<String, List<Integer>> shuffled = new LinkedHashMap<>();
        for (List<KV> partition : shufflePartitions) {
            for (KV kv : partition) {
                shuffled.computeIfAbsent(kv.key, _k -> new ArrayList<>()).add(kv.value);
            }
        }

        System.out.println("  Shuffle 前: 数据分散在各分区");
        for (int i = 0; i < shufflePartitions.size(); i++) {
            System.out.printf("    分区%d: %s%n", i, shufflePartitions.get(i));
        }
        System.out.println("  Shuffle 后: 相同 key 聚集到一起");
        shuffled.forEach((k, v) -> System.out.printf("    key=%s -> values=%s%n", k, v));
        System.out.println("  关键: map/filter 是窄依赖(无Shuffle), groupByKey/reduceByKey 是宽依赖(触发Shuffle)\n");
    }

    // ==================== Lineage（血统）vs Checkpoint ====================

    static void rdLineageVsCheckpointDemo() {
        System.out.println("--- 5. RDD Lineage (血统重算) vs Checkpoint (截断血统) ---");

        List<String> lineageLog = new ArrayList<>();

        // 构建一条操作链作为血统
        SimRDD<Integer> rdd1 = new SimRDD<>(Arrays.asList(1, 2, 3));
        rdd1.setName("rdd1_源数据", lineageLog);

        SimRDD<Integer> rdd2 = rdd1.map(x -> x * 2);
        rdd2.setName("rdd2_map(*2)", lineageLog);

        SimRDD<Integer> rdd3 = rdd2.filter(x -> x > 3);
        rdd3.setName("rdd3_filter(>3)", lineageLog);

        SimRDD<Integer> rdd4 = rdd3.map(x -> x + 10);
        rdd4.setName("rdd4_map(+10)", lineageLog);

        // 打印完整血统链
        System.out.println("RDD Lineage (血统链):");
        for (String entry : lineageLog) {
            System.out.println("  " + entry);
        }

        System.out.println("\n故障恢复策略对比:");
        System.out.println("""
                Lineage 重算（无 Checkpoint）:
                  若 rdd4:Part2 丢失
                    -> 回溯 Lineage: rdd4.Part2 = rdd3.Part2.map(+10)
                    -> rdd3.Part2 = rdd2.Part2.filter(>3)
                    -> rdd2.Part2 = rdd1.Part2.map(*2)
                    -> 从头重新计算 -- 链越长, 重算代价越大

                Checkpoint 截断血统:
                  对 rdd3 执行 checkpoint 物化到 HDFS
                    -> 截断 rdd3 之前的所有依赖, rdd3 父依赖变为 CheckpointRDD
                    -> 若 rdd4:Part2 丢失, 只需从 rdd3(已物化) 开始重算
                    -> 避免长链回溯, 减少重算开销
                """);

        System.out.println("  关键: Lineage 是「低成本重算」, Checkpoint 是「昂贵的物化+截断血统」");
        System.out.println("       RDD 默认用 Lineage 容错, 对长链/Shuffle 后建议 Checkpoint\n");
    }

    // ==================== 简易 RDD 模拟类 ====================

    /**
     * 简易 RDD 模拟类 -- 模拟 Spark RDD 的核心行为：惰性求值、转换、行动
     */
    static class SimRDD<T> {
        List<T> data;
        String lineageName;

        SimRDD(List<T> data) {
            this.data = new ArrayList<>(data);
        }

        /** 为血统演示设置名称 */
        void setName(String name, List<String> lineageLog) {
            this.lineageName = name;
            lineageLog.add("  " + name + " -> 数据: " + data);
        }

        /** Transformation -- map, 惰性, 不触发计算 */
        <R> SimRDD<R> map(java.util.function.Function<T, R> mapper) {
            List<R> mapped = data.stream().map(mapper).collect(Collectors.toList());
            return new SimRDD<>(mapped);
        }

        /** Transformation -- filter, 惰性, 不触发计算 */
        SimRDD<T> filter(java.util.function.Predicate<T> predicate) {
            List<T> filtered = data.stream().filter(predicate).collect(Collectors.toList());
            return new SimRDD<>(filtered);
        }

        /** Transformation -- flatMap, 惰性, 不触发计算 */
        <R> SimRDD<R> flatMap(java.util.function.Function<T, List<R>> flatMapper) {
            List<R> flattened = data.stream()
                    .flatMap(e -> flatMapper.apply(e).stream())
                    .collect(Collectors.toList());
            return new SimRDD<>(flattened);
        }

        /** Action -- reduce, 触发计算 */
        T reduce(java.util.function.BinaryOperator<T> accumulator) {
            return data.stream().reduce(accumulator).orElseThrow();
        }

        /** Action -- count, 触发计算 */
        long count() {
            return data.size();
        }

        /** Action -- collect, 触发计算, 收集到 Driver 端 */
        List<T> collect() {
            return new ArrayList<>(data);
        }
    }
}