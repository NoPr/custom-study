package base.elasticsearch;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 聚合分析演示 —— Bucket(Metric)聚合 vs Metrics(统计)聚合 +
 * 手写 Min/Max/Avg/Sum/Count/Cardinality + 嵌套聚合(Parent/Children/Sub-aggregation)。
 *
 * <p>ES 聚合(Aggregation)是数据分析的核心能力，分为三大类：
 * <ul>
 *   <li>Bucket 聚合：将文档分桶，如 terms(按值分组)、range(按范围分组)、
 *       date_histogram(按时间间隔分组)</li>
 *   <li>Metrics 聚合：对桶内文档计算统计指标，如 min/max/avg/sum/count/cardinality</li>
 *   <li>Pipeline 聚合：对其他聚合结果进行二次加工，如 moving_avg、derivative</li>
 * </ul>
 *
 * <p>嵌套聚合(Sub-aggregation)：在一个 Bucket 聚合内部再嵌套其他聚合，
 * 形成 Parent → Children 的层级结构，如先按地区分组，再按年龄范围分组。</p>
 *
 * @author study-tuling
 */
public class AggregationDemo {

    // ======================== 1. 模拟文档 ========================

    /** 电商订单文档 */
    record Order(
            String product,    // 商品名
            String category,   // 品类
            String region,     // 地区
            double price,      // 价格
            int quantity,      // 数量
            String month       // 月份
    ) {}

    // ======================== 2. Metrics 聚合：手写统计 ========================

    /**
     * Metrics 聚合器：手写实现 Min/Max/Avg/Sum/Count/Cardinality。
     * 对应 ES 的 stats 聚合。
     */
    static class MetricsAggregator {

        /** 对数值列表计算 Min */
        static double min(List<Double> values) {
            if (values.isEmpty()) return 0;
            double result = values.get(0);
            for (double v : values) {
                if (v < result) result = v;
            }
            return result;
        }

        /** 对数值列表计算 Max */
        static double max(List<Double> values) {
            if (values.isEmpty()) return 0;
            double result = values.get(0);
            for (double v : values) {
                if (v > result) result = v;
            }
            return result;
        }

        /** 对数值列表计算 Avg */
        static double avg(List<Double> values) {
            if (values.isEmpty()) return 0;
            return sum(values) / values.size();
        }

        /** 对数值列表计算 Sum */
        static double sum(List<Double> values) {
            double result = 0;
            for (double v : values) result += v;
            return result;
        }

        /** 对数值列表计算 Count */
        static long count(List<Double> values) {
            return values.size();
        }

        /**
         * Cardinality(基数估算)：使用 HyperLogLog 的简化版 —— 集合去重后计数。
         * ES 使用 HyperLogLog++ 算法，可以在 O(1) 空间复杂度下估算基数，
         * 误差约 5%。
         */
        static long cardinality(List<?> values) {
            return new HashSet<>(values).size();
        }

        /** 一站式 stats 统计 */
        record StatsResult(double min, double max, double avg, double sum, long count) {
            @Override
            public String toString() {
                return String.format("Stats{min=%.2f, max=%.2f, avg=%.2f, sum=%.2f, count=%d}",
                        min, max, avg, sum, count);
            }
        }

        static StatsResult stats(List<Double> values) {
            return new StatsResult(min(values), max(values), avg(values), sum(values), count(values));
        }
    }

    // ======================== 3. Bucket 聚合 ========================

    /**
     * Bucket 聚合器：terms(按值分组)、range(按范围分组)。
     */
    static class BucketAggregator {

        /**
         * Terms 聚合：按字段值分组，统计每个桶的文档数和子聚合。
         * 对应 ES 的 terms aggregation。
         */
        static <T> Map<T, List<Integer>> terms(List<T> fieldValues, List<Integer> docIds) {
            Map<T, List<Integer>> buckets = new LinkedHashMap<>();
            for (int i = 0; i < fieldValues.size(); i++) {
                T key = fieldValues.get(i);
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(docIds.get(i));
            }
            return buckets;
        }

        /**
         * Range 聚合：按数值范围分组。
         * 对应 ES 的 range aggregation。
         */
        static class RangeBucket {
            final String label;
            final double from, to;
            final List<Integer> docIds = new ArrayList<>();

            RangeBucket(String label, double from, double to) {
                this.label = label;
                this.from = from;
                this.to = to;
            }

            @Override
            public String toString() {
                return String.format("  %s [%.0f, %.0f): %d 条", label, from, to, docIds.size());
            }
        }

        static List<RangeBucket> range(List<Double> values, List<Integer> docIds,
                                        double[] boundaries, String[] labels) {
            List<RangeBucket> buckets = new ArrayList<>();
            for (int i = 0; i < labels.length; i++) {
                buckets.add(new RangeBucket(labels[i], boundaries[i], boundaries[i + 1]));
            }
            for (int i = 0; i < values.size(); i++) {
                double v = values.get(i);
                for (int j = 0; j < boundaries.length - 1; j++) {
                    if (v >= boundaries[j] && v < boundaries[j + 1]) {
                        buckets.get(j).docIds.add(docIds.get(i));
                        break;
                    }
                }
            }
            return buckets;
        }
    }

    // ======================== 4. 嵌套聚合 ========================

    /**
     * 嵌套聚合(Sub-aggregation)：
     * 先按品类分桶，每个品类桶内再按价格范围分桶(子聚合)。
     *
     * <p>对应 ES DSL：
     * <pre>{@code
     * { aggs: { by_category: { terms: { field: "category" },
     *     aggs: { by_price_range: { range: { field: "price", ranges: [...] } } } } } }
     * }</pre>
     */
    static class NestedAggregation {

        record SubAggResult(String bucketKey, Map<String, MetricsAggregator.StatsResult> subBuckets) {}

        static List<SubAggResult> execute(List<Order> orders) {
            // 第一层：按 category 分组
            Map<String, List<Order>> byCategory = orders.stream()
                    .collect(Collectors.groupingBy(Order::category, LinkedHashMap::new, Collectors.toList()));

            List<SubAggResult> results = new ArrayList<>();
            for (Map.Entry<String, List<Order>> entry : byCategory.entrySet()) {
                String category = entry.getKey();
                List<Order> categoryOrders = entry.getValue();

                // 第二层(子聚合)：每个品类内按 price 范围分组 → 统计 stats
                Map<String, List<Double>> subBuckets = new LinkedHashMap<>();
                for (Order o : categoryOrders) {
                    String rangeLabel = o.price() < 50 ? "低价(<50)" : o.price() < 100 ? "中价(50-100)" : "高价(>=100)";
                    subBuckets.computeIfAbsent(rangeLabel, k -> new ArrayList<>()).add(o.price());
                }

                Map<String, MetricsAggregator.StatsResult> subStats = new LinkedHashMap<>();
                for (Map.Entry<String, List<Double>> subEntry : subBuckets.entrySet()) {
                    subStats.put(subEntry.getKey(), MetricsAggregator.stats(subEntry.getValue()));
                }
                results.add(new SubAggResult(category, subStats));
            }
            return results;
        }
    }

    // ======================== 5. 演示入口 ========================

    static List<Order> buildSampleOrders() {
        return List.of(
                new Order("iPhone 15",    "电子产品", "华东", 8999, 2, "2024-01"),
                new Order("MacBook Pro",  "电子产品", "华东", 14999, 1, "2024-01"),
                new Order("AirPods",      "电子产品", "华北", 1299, 3, "2024-02"),
                new Order("牛仔裤",       "服装",     "华东", 299, 5, "2024-01"),
                new Order("T恤",          "服装",     "华北", 99, 10, "2024-02"),
                new Order("运动鞋",       "服装",     "华南", 599, 2, "2024-03"),
                new Order("Python编程",   "图书",     "华东", 79, 8, "2024-02"),
                new Order("Java核心",     "图书",     "华北", 89, 6, "2024-03"),
                new Order("算法导论",     "图书",     "华南", 129, 3, "2024-01"),
                new Order("哑铃",         "运动",     "华东", 199, 4, "2024-03"),
                new Order("瑜伽垫",       "运动",     "华北", 89, 7, "2024-02"),
                new Order("跑步机",       "运动",     "华南", 2999, 1, "2024-01")
        );
    }

    static void demoMetricsAggregation() {
        System.out.println("=== 1. Metrics 聚合：手写 Min/Max/Avg/Sum/Count/Cardinality ===");
        List<Order> orders = buildSampleOrders();
        List<Double> prices = orders.stream().map(Order::price).collect(Collectors.toList());
        List<Double> quantities = orders.stream().map(o -> (double) o.quantity()).collect(Collectors.toList());
        List<String> categories = orders.stream().map(Order::category).collect(Collectors.toList());

        System.out.println("price stats:  " + MetricsAggregator.stats(prices));
        System.out.println("quantity stats: " + MetricsAggregator.stats(quantities));
        System.out.println("category cardinality(品类数): " + MetricsAggregator.cardinality(categories));
        System.out.println("region cardinality(地区数): "
                + MetricsAggregator.cardinality(orders.stream().map(Order::region).collect(Collectors.toList())));
    }

    static void demoBucketAggregation() {
        System.out.println("\n=== 2. Bucket 聚合：terms + range ===");

        List<Order> orders = buildSampleOrders();
        List<Integer> docIds = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) docIds.add(i + 1);

        // Terms 聚合：按品类分组
        List<String> categoryValues = orders.stream().map(Order::category).collect(Collectors.toList());
        Map<String, List<Integer>> byCategory = BucketAggregator.terms(categoryValues, docIds);
        System.out.println("--- Terms 聚合: 按 category 分组 ---");
        for (Map.Entry<String, List<Integer>> entry : byCategory.entrySet()) {
            System.out.printf("  %s: %d 条, DocId=%s%n",
                    entry.getKey(), entry.getValue().size(), entry.getValue());
        }

        // Range 聚合：按价格范围分组
        List<Double> priceValues = orders.stream().map(Order::price).collect(Collectors.toList());
        double[] boundaries = {0, 100, 500, 2000, 20000};
        String[] labels = {"低价", "中价", "高价", "奢侈品"};
        List<BucketAggregator.RangeBucket> priceRange = BucketAggregator.range(priceValues, docIds, boundaries, labels);
        System.out.println("\n--- Range 聚合: 按 price 范围分组 ---");
        for (BucketAggregator.RangeBucket bucket : priceRange) {
            System.out.println(bucket);
        }
    }

    static void demoNestedAggregation() {
        System.out.println("\n=== 3. 嵌套聚合: Category → PriceRange → Stats ===");
        List<Order> orders = buildSampleOrders();

        List<NestedAggregation.SubAggResult> results = NestedAggregation.execute(orders);
        for (NestedAggregation.SubAggResult result : results) {
            System.out.println("┌─ Category: " + result.bucketKey());
            for (Map.Entry<String, MetricsAggregator.StatsResult> subEntry : result.subBuckets().entrySet()) {
                System.out.printf("│  ├─ %s: %s%n", subEntry.getKey(), subEntry.getValue());
            }
            System.out.println("│");
            // 汇总每个品类的总销售额
            double totalRevenue = orders.stream()
                    .filter(o -> o.category().equals(result.bucketKey()))
                    .mapToDouble(o -> o.price() * o.quantity())
                    .sum();
            System.out.printf("│  └─ 总销售额: ¥%.2f%n", totalRevenue);
            System.out.println();
        }
    }

    static void demoCardinalityExplain() {
        System.out.println("=== 4. Cardinality 基数估算原理 ===");
        System.out.println("ES 使用 HyperLogLog++ 算法进行基数估算：");
        System.out.println("┌─────────────────────────────────────────────┐");
        System.out.println("│ 1. 对每个值做 Hash → 64位二进制             │");
        System.out.println("│ 2. 取低 14 位作为桶编号(16384个桶)          │");
        System.out.println("│ 3. 取剩余 50 位的「前导零个数 + 1」作为估计  │");
        System.out.println("│ 4. 每个桶保留见过的最大前导零数              │");
        System.out.println("│ 5. 所有桶的调和平均数 × 桶数 ≈ 基数估算     │");
        System.out.println("│                                              │");
        System.out.println("│ 空间: 16384 × 6bit ≈ 12KB 固定内存           │");
        System.out.println("│ 误差: 标准误差约 0.81%(可调precision参数)    │");
        System.out.println("└─────────────────────────────────────────────┘");
    }

    static void demoAggregationWorkflow() {
        System.out.println("\n=== 5. ES 聚合完整工作流 ===");
        System.out.println("┌──────────────────────────────────────────────────────┐");
        System.out.println("│ 1. Coordinating Node 收到聚合请求                     │");
        System.out.println("│                          ↓                           │");
        System.out.println("│ 2. 转发给所有相关 Shard                              │");
        System.out.println("│                          ↓                           │");
        System.out.println("│ 3. 每个 Shard 本地执行聚合(Bucket+Metrics)            │");
        System.out.println("│    - Bucket: 分桶，生成桶 key → 文档列表              │");
        System.out.println("│    - Metrics: 对每个桶计算 stats                      │");
        System.out.println("│                          ↓                           │");
        System.out.println("│ 4. 各 Shard 返回部分聚合结果给 Coordinating Node      │");
        System.out.println("│                          ↓                           │");
        System.out.println("│ 5. Coordinating Node 合并(Merge):                     │");
        System.out.println("│    - Bucket: 同名桶合并(如 '电子产品' 桶合并)         │");
        System.out.println("│    - Metrics: 部分 sum 累加, avg 需要 sum+count 再除  │");
        System.out.println("│    - Cardinality: HyperLogLog 合并(取每个桶最大值)    │");
        System.out.println("│                          ↓                           │");
        System.out.println("│ 6. 返回最终聚合结果                                   │");
        System.out.println("└──────────────────────────────────────────────────────┘");
    }

    public static void main(String[] args) {
        demoMetricsAggregation();
        demoBucketAggregation();
        demoNestedAggregation();
        demoCardinalityExplain();
        demoAggregationWorkflow();
    }
}