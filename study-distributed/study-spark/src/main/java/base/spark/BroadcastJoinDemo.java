package base.spark;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Broadcast 广播变量 / Accumulator 累加器 / Join 策略手写模拟
 *
 * <p>共享变量机制：
 * <ul>
 *   <li><b>Broadcast 广播变量</b>：只读的共享变量，每 Executor 节点缓存一份
 *       -- 用于大表 Join 中的小表广播、配置分发等场景
 *       -- 避免每个 Task 都序列化一次副本，减少网络和内存开销</li>
 *   <li><b>Accumulator 累加器</b>：只写的共享变量，各 Task 增加值，只有 Driver 可读取
 *       -- 用于计数器、求和等聚合场景（如脏数据计数、自定义指标）
 *       -- 支持 LongAccumulator、DoubleAccumulator、CollectionAccumulator</li>
 * </ul>
 *
 * <p>Join 策略（三种核心实现）：
 * <ul>
 *   <li><b>Map Join (Broadcast Hash Join)</b>：小表广播到各节点构建 HashMap，大表逐行查 HashMap
 *       -- 条件：小表数据量 &lt; spark.sql.autoBroadcastJoinThreshold (默认 10MB)</li>
 *   <li><b>SortMerge Join</b>：两表按 Join Key 分别排序，再归并匹配
 *       -- 默认 Join 策略，两表都较大的情况</li>
 *   <li><b>Bucket Join (Shuffled Hash Join)</b>：预先按相同 Key 分桶到相同节点，无需 Shuffle
 *       -- 需要两表预先按相同 Key 分桶（bucketBy）且桶数成倍数关系</li>
 * </ul>
 *
 * @see RDDDemo RDD弹性分布式数据集
 * @see DAGShuffleDemo DAG调度与Shuffle机制
 */
public class BroadcastJoinDemo {

    public static void main(String[] args) {
        System.out.println("========== Broadcast / Accumulator / Join 策略演示 ==========\n");

        broadcastVsAccumulator();
        mapJoinDemo();
        sortMergeJoinDemo();
        bucketJoinDemo();
        threeJoinComparison();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 1. Broadcast vs Accumulator ====================

    static void broadcastVsAccumulator() {
        System.out.println("--- 1. Broadcast 广播变量 vs Accumulator 累加器 ---");

        // === Broadcast 演示 ===
        System.out.println("[Broadcast 广播变量 -- 只读, 每节点缓存一份]");
        System.out.println("""
                使用场景: 小维度表广播到各Executor, 避免Shuffle
                  val broadcastDict = spark.sparkContext.broadcast(Map("CN"->"中国","US"->"美国"))
                  val result = bigRDD.map(row => (row.countryCode, broadcastDict.value(row.code)))
                """);

        // 模拟广播变量
        Map<String, String> dict = Map.of("CN", "中国", "US", "美国", "JP", "日本");
        BroadcastVar<Map<String, String>> broadcastDict = new BroadcastVar<>(dict);

        List<Row> bigTable = List.of(
                new Row("user1", "CN", 100),
                new Row("user2", "US", 200),
                new Row("user3", "JP", 300),
                new Row("user4", "CN", 400)
        );

        System.out.println("大表数据: " + bigTable);
        System.out.println("广播小表(维度字典): " + dict);

        // 模拟 3 个 Executor (节点) 各自使用同一份广播变量
        System.out.println("\n模拟3个节点各自使用广播变量:");
        for (int node = 1; node <= 3; node++) {
            Map<String, String> localDict = broadcastDict.getValue();
            System.out.printf("  节点%d 本地缓存: %s (所有Task共享此副本, 不从Driver序列化)%n",
                    node, localDict.keySet());
        }

        // 实际使用广播变量进行映射
        System.out.println("\n广播 Join 结果:");
        for (Row row : bigTable) {
            String countryName = broadcastDict.getValue().getOrDefault(row.countryCode, "未知");
            System.out.printf("  %s (%s → %s, amount=%d)%n",
                    row.userId, row.countryCode, countryName, row.amount);
        }

        // === Accumulator 演示 ===
        System.out.println("\n[Accumulator 累加器 -- 只写, 只有 Driver 可读]");
        System.out.println("""
                使用场景: 统计脏数据条数, 各Task分布式累加, Driver端汇总
                  val dirtyCount = spark.sparkContext.longAccumulator("dirtyRecords")
                  bigRDD.foreach(row => if(isInvalid(row)) dirtyCount.add(1))
                  println(s"脏数据: ${dirtyCount.value} 条")
                """);

        // 模拟累加器
        AccumulatorLong dirtyCount = new AccumulatorLong("dirtyRecords");
        AccumulatorLong totalAmount = new AccumulatorLong("totalAmount");

        System.out.println("模拟3个Executor并行处理数据并累加:");
        List<List<Row>> partitions = List.of(
                List.of(new Row("u1", "CN", 100), new Row("u5", "XX", -1)),    // XX是脏数据
                List.of(new Row("u2", "US", 200), new Row("u6", "??", 300)),    // ??是脏数据
                List.of(new Row("u3", "JP", 999), new Row("u4", "CN", 400))
        );

        for (int p = 0; p < partitions.size(); p++) {
            System.out.printf("  分区%d (Executor %d) 处理:%n", p, p);
            for (Row row : partitions.get(p)) {
                totalAmount.add(row.amount);
                if ("XX".equals(row.countryCode) || "??".equals(row.countryCode)) {
                    dirtyCount.add(1);
                    System.out.printf("    脏数据发现: %s, dirtyCount+=1%n", row);
                } else {
                    System.out.printf("    正常数据: %s, totalAmount+=%d%n", row, row.amount);
                }
            }
        }

        System.out.printf("%n  Driver端汇总: 脏数据=%d条, 总金额=%d%n",
                dirtyCount.getValue(), totalAmount.getValue());

        System.out.println("\n对比总结:");
        System.out.println("""
                  Broadcast (广播变量)              Accumulator (累加器)
                ┌────────────────────────┐   ┌────────────────────────┐
                │ 方向: Driver → Executor│   │ 方向: Executor → Driver│
                │ 读写: 只读             │   │ 读写: Driver读, Task写  │
                │ 用途: 分发配置/小表    │   │ 用途: 计数器/指标收集    │
                │ 缓存: 每Executor一份   │   │ 特性: 幂等非精确(Mapper) │
                │ 序列化: 一次广播       │   │ 容错: 重试可能重复累加    │
                └────────────────────────┘   └────────────────────────┘
                """);
    }

    // ==================== 2. Map Join (Broadcast Hash Join) ====================

    static void mapJoinDemo() {
        System.out.println("--- 2. Map Join (Broadcast Hash Join) 手写模拟 ---");

        // 小表: 维度数据 (会被广播)
        List<KV> dimensionTable = List.of(
                new KV("CN", 1), new KV("US", 2), new KV("JP", 3), new KV("UK", 4)
        );

        // 大表: 事实数据
        List<KV> factTable = List.of(
                new KV("CN", 100), new KV("US", 200), new KV("JP", 300),
                new KV("CN", 400), new KV("US", 500), new KV("CN", 600)
        );

        System.out.println("小表(维度, 可广播): " + dimensionTable);
        System.out.println("大表(事实):         " + factTable);

        // Step1: 广播小表 -- 构建 HashMap
        Map<String, Integer> broadcastMap = new HashMap<>();
        for (KV kv : dimensionTable) {
            broadcastMap.put(kv.key, kv.value);
        }
        System.out.println("\nStep1: 广播小表 → 构建 HashMap: " + broadcastMap);

        // Step2: 大表逐行 Probe HashMap (无 Shuffle)
        System.out.println("Step2: 大表逐行 Probe HashMap (Map Side Join, 无Shuffle):");
        List<JoinResult> results = new ArrayList<>();
        for (KV fact : factTable) {
            Integer dimValue = broadcastMap.get(fact.key);
            if (dimValue != null) {
                results.add(new JoinResult(fact.key, fact.value, dimValue));
            }
        }

        System.out.println("Join 结果:");
        for (JoinResult r : results) {
            System.out.printf("  key=%s, factVal=%d, dimVal=%d → mergedVal=%d%n",
                    r.key, r.factValue, r.dimValue, r.factValue + r.dimValue);
        }

        System.out.println("\n优点: 无Shuffle, 只需一次全表扫描大表 + HashMap Probe");
        System.out.println("条件: 小表 < spark.sql.autoBroadcastJoinThreshold (默认10MB)");
        System.out.println("触发: 自动(数据量达标) 或 hint: /*+ BROADCAST(smallTable) */\n");
    }

    // ==================== 3. SortMerge Join ====================

    static void sortMergeJoinDemo() {
        System.out.println("--- 3. SortMerge Join 手写模拟 ---");

        List<KV> leftTable = Arrays.asList(
                new KV("A", 10), new KV("C", 30), new KV("B", 20), new KV("A", 40), new KV("D", 50)
        );
        List<KV> rightTable = Arrays.asList(
                new KV("B", 200), new KV("A", 100), new KV("C", 300), new KV("A", 400)
        );

        System.out.println("左表(原始):   " + leftTable);
        System.out.println("右表(原始):   " + rightTable);

        // Step1: 两表分别按 Join Key 排序 (可能 Shuffle + Sort)
        List<KV> leftSorted = new ArrayList<>(leftTable);
        leftSorted.sort(Comparator.comparing(kv -> kv.key));
        List<KV> rightSorted = new ArrayList<>(rightTable);
        rightSorted.sort(Comparator.comparing(kv -> kv.key));

        System.out.println("\nStep1 排序后:");
        System.out.println("  左表: " + leftSorted);
        System.out.println("  右表: " + rightSorted);

        // Step2: 双指针归并匹配 (Merge Join)
        System.out.println("\nStep2 归并匹配 (双指针):");
        List<JoinResult> results = new ArrayList<>();
        int li = 0, ri = 0;

        while (li < leftSorted.size() && ri < rightSorted.size()) {
            KV left = leftSorted.get(li);
            KV right = rightSorted.get(ri);
            int cmp = left.key.compareTo(right.key);

            if (cmp == 0) {
                // Key 匹配: 处理所有相同的 key
                String matchKey = left.key;
                // 收集左表所有相同 key
                List<KV> leftGroup = new ArrayList<>();
                while (li < leftSorted.size() && leftSorted.get(li).key.equals(matchKey)) {
                    leftGroup.add(leftSorted.get(li));
                    li++;
                }
                // 收集右表所有相同 key
                List<KV> rightGroup = new ArrayList<>();
                while (ri < rightSorted.size() && rightSorted.get(ri).key.equals(matchKey)) {
                    rightGroup.add(rightSorted.get(ri));
                    ri++;
                }
                // 笛卡尔积
                for (KV l : leftGroup) {
                    for (KV r : rightGroup) {
                        results.add(new JoinResult(l.key, l.value, r.value));
                    }
                }
                System.out.printf("  匹配 key=%s: 左%d条 × 右%d条 = %d条结果%n",
                        matchKey, leftGroup.size(), rightGroup.size(),
                        leftGroup.size() * rightGroup.size());
            } else if (cmp < 0) {
                li++;  // 左表 key 小, 左指针前进
            } else {
                ri++;  // 右表 key 小, 右指针前进
            }
        }

        System.out.println("\nJoin 结果 (" + results.size() + " 条):");
        for (JoinResult r : results) {
            System.out.printf("  key=%s, leftVal=%d, rightVal=%d → sum=%d%n",
                    r.key, r.factValue, r.dimValue, r.factValue + r.dimValue);
        }

        System.out.println("\n特点: 两表较大时默认策略, 需要Shuffle+Sort, 但内存占用可控");
        System.out.println("复杂度: O(MlogM + NlogN + M+N) -- 排序+归并\n");
    }

    // ==================== 4. Bucket Join ====================

    static void bucketJoinDemo() {
        System.out.println("--- 4. Bucket Join (预分桶 Join) 手写模拟 ---");

        System.out.println("""
                Bucket Join 原理:
                  两表预先按相同 Key 分桶到相同节点 (bucketBy("key", N))
                     节点0: 左表bucket0 + 右表bucket0 → 本地 Join (无需 Shuffle)
                     节点1: 左表bucket1 + 右表bucket1 → 本地 Join
                     节点2: 左表bucket2 + 右表bucket2 → 本地 Join
                  同一个 Key 的数据必然落在同一个节点上!
                """);

        int bucketCount = 3;

        List<KV> leftTable = Arrays.asList(
                new KV("A", 10), new KV("B", 20), new KV("C", 30),
                new KV("A", 40), new KV("D", 50), new KV("B", 60)
        );
        List<KV> rightTable = Arrays.asList(
                new KV("A", 100), new KV("C", 300), new KV("B", 200),
                new KV("A", 400), new KV("D", 500)
        );

        System.out.println("左表: " + leftTable);
        System.out.println("右表: " + rightTable);

        // 按 Key 哈希分桶 (模拟预分桶)
        Map<Integer, List<KV>> leftBuckets = new HashMap<>();
        Map<Integer, List<KV>> rightBuckets = new HashMap<>();
        for (int i = 0; i < bucketCount; i++) {
            leftBuckets.put(i, new ArrayList<>());
            rightBuckets.put(i, new ArrayList<>());
        }

        for (KV kv : leftTable) {
            int bucketId = Math.abs(kv.key.hashCode()) % bucketCount;
            leftBuckets.get(bucketId).add(kv);
        }
        for (KV kv : rightTable) {
            int bucketId = Math.abs(kv.key.hashCode()) % bucketCount;
            rightBuckets.get(bucketId).add(kv);
        }

        System.out.println("\n按 Key 哈希分桶结果 (相同 Key 必然在同一 Bucket):");
        for (int i = 0; i < bucketCount; i++) {
            System.out.printf("  Bucket%d: 左=%s  | 右=%s%n",
                    i, leftBuckets.get(i), rightBuckets.get(i));
        }

        // 每个 Bucket 在本地执行 SortMerge Join (无需跨节点 Shuffle!)
        System.out.println("\n各 Bucket 本地 Join (无需 Shuffle, 直接 SortMerge):");
        List<JoinResult> allResults = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            List<KV> left = leftBuckets.get(i);
            List<KV> right = rightBuckets.get(i);

            // 排序 + 归并 (同 SortMergeJoin)
            left.sort(Comparator.comparing(kv -> kv.key));
            right.sort(Comparator.comparing(kv -> kv.key));

            int li = 0, ri = 0;
            while (li < left.size() && ri < right.size()) {
                int cmp = left.get(li).key.compareTo(right.get(ri).key);
                if (cmp == 0) {
                    String key = left.get(li).key;
                    allResults.add(new JoinResult(key, left.get(li).value, right.get(ri).value));
                    li++;
                    ri++;
                } else if (cmp < 0) li++;
                else ri++;
            }
        }

        System.out.println("Bucket Join 结果 (" + allResults.size() + " 条):");
        for (JoinResult r : allResults) {
            System.out.printf("  key=%s, leftVal=%d, rightVal=%d%n",
                    r.key, r.factValue, r.dimValue);
        }

        System.out.println("\n优点: 完全消除 Shuffle, 只需本地 Join, 大幅提升性能");
        System.out.println("条件: 两表需预分桶(bucketBy+sortBy), 桶数需成倍数关系");
        System.out.println("适合: 事实表+维度表 经常 Join 的场景, 写入时多做一次分桶换取查询时 Shuffle 消除\n");
    }

    // ==================== 5. 三种 Join 对比总结 ====================

    static void threeJoinComparison() {
        System.out.println("--- 5. 三种 Join 策略对比总结 ---");

        System.out.println("""
                ┌──────────────────────────────────────────────────────────────────┐
                │                    Spark Join 策略全景对比                         │
                ├──────────┬───────────────┬───────────────┬───────────────────────┤
                │ 策略     │ Map Join      │ SortMerge Join│ Bucket Join           │
                │          │ (BroadcastHJ) │               │                       │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ 条件     │ 小表<10MB     │ 两表都大       │ 预分桶(写入时)         │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ Shuffle  │ 无            │ 有(全量)      │ 无                    │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ 排序     │ 无            │ 需要          │ 预排序(写入时)         │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ 内存     │ 小表需全放内存 │ 可控(排序+缓存)│ 全在内存(桶内)       │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ 网络I/O  │ 广播小表(一次)│ 全量Shuffle   │ 无                    │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ 适用     │ 维度表Join     │ 大表Join      │ 经常Join的固定表     │
                │          │ 事实表         │               │                       │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ CPU开销  │ 低(哈希查找)  │ 中(排序+归并) │ 低(哈希/归并)        │
                ├──────────┼───────────────┼───────────────┼───────────────────────┤
                │ 默认策略 │ 自动选择       │ 默认          │ 需显式配置             │
                └──────────┴───────────────┴───────────────┴───────────────────────┘

                Spark SQL 自动选择逻辑:
                  1. 优先尝试 Map Join (小表<阈值)
                  2. 否则使用 SortMerge Join (默认)
                  3. Bucket Join 需显式配置 (写入时 bucketBy + 查询时相同列 Join)

                Hint 语法:
                  SELECT /*+ BROADCAST(small) */ * FROM big JOIN small ON ...
                  SELECT /*+ MERGE(big) */ * FROM big JOIN other ON ...
                """);
    }

    // ==================== 辅助数据结构 ====================

    /** 键值对 */
    record KV(String key, int value) {}

    /** 数据行 */
    record Row(String userId, String countryCode, int amount) {}

    /** Join 结果 */
    record JoinResult(String key, int factValue, int dimValue) {}

    /** 广播变量 -- 只读, 每 Executor 一个副本 */
    static class BroadcastVar<T> {
        private final T value;

        BroadcastVar(T value) {
            this.value = value;
        }

        T getValue() {
            return value;
        }
    }

    /** 累加器 -- 只写, 只有 Driver 可读取 */
    static class AccumulatorLong {
        private final String name;
        private long value = 0;

        AccumulatorLong(String name) {
            this.name = name;
        }

        synchronized void add(long delta) {
            value += delta;
        }

        long getValue() {
            return value;
        }
    }
}