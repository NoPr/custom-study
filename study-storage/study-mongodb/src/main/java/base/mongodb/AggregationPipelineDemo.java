package base.mongodb;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MongoDB 聚合管道手写模拟
 * Pipeline: $match (过滤) -> $group (分组聚合) -> $sort (排序) -> $project (投影)
 *            -> $limit (限制条数) -> $skip (跳过条数) -> $lookup (左外连接)
 * 每个 stage 接收上一 stage 的输出文档列表, 处理后传给下一 stage
 */
public class AggregationPipelineDemo {

    public static void main(String[] args) {
        System.out.println("========== MongoDB 聚合管道手写模拟 ==========\n");

        pipelineBasicDemo();
        lookupJoinDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 1. 基础 Pipeline ====================

    /**
     * 模拟 db.orders.aggregate([ ... ]) 的完整流水线:
     * $match -> $group -> $sort -> $project -> $limit -> $skip
     */
    static void pipelineBasicDemo() {
        System.out.println("--- Pipeline: $match -> $group -> $sort -> $project -> $limit -> $skip ---");

        List<Map<String, Object>> orders = sampleOrders();
        System.out.println("原始订单 (" + orders.size() + " 条):");
        orders.forEach(o -> System.out.println("  " + o));

        Pipeline pipeline = new Pipeline();
        List<Map<String, Object>> result = pipeline
            .match(doc -> (int) doc.get("amount") >= 100)                              // 过滤金额 >= 100
            .group("userId", "total", docs -> docs.stream()                             // 按 userId 分组, 聚合 sum
                    .mapToInt(d -> (int) d.get("amount")).sum())
            .sort(Comparator.<Map<String, Object>>comparingInt(                         // 按 total 降序
                    d -> (int) d.get("total")).reversed())
            .project("userId", "total")                                                 // 只保留 userId + total
            .limit(2)                                                                   // 前 2 条
            .execute(orders);

        System.out.println("\nPipeline 结果 (过滤>=100 -> 分组求和 -> 降序 -> 取前2):");
        result.forEach(r -> System.out.println("  " + r));
        System.out.println();
    }

    /** 模拟订单集合 */
    static List<Map<String, Object>> sampleOrders() {
        List<Map<String, Object>> orders = new ArrayList<>();
        orders.add(mapOf("_id", 1, "userId", "u1", "amount", 50,  "item", "apple"));
        orders.add(mapOf("_id", 2, "userId", "u1", "amount", 200, "item", "book"));
        orders.add(mapOf("_id", 3, "userId", "u2", "amount", 150, "item", "pen"));
        orders.add(mapOf("_id", 4, "userId", "u2", "amount", 80,  "item", "paper"));
        orders.add(mapOf("_id", 5, "userId", "u3", "amount", 300, "item", "laptop"));
        orders.add(mapOf("_id", 6, "userId", "u1", "amount", 120, "item", "mouse"));
        orders.add(mapOf("_id", 7, "userId", "u3", "amount", 90,  "item", "usb"));
        return orders;
    }

    @SafeVarargs
    static <K, V> Map<K, V> mapOf(Object... kv) {
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) kv[i];
            @SuppressWarnings("unchecked")
            V val = (V) kv[i + 1];
            map.put(key, val);
        }
        return map;
    }

    /**
     * Pipeline 流水线: 链式构建 stages, execute() 时依次执行
     */
    static class Pipeline {
        private final List<PipelineStage> stages = new ArrayList<>();

        /** $match -- 过滤文档, 类似 WHERE */
        Pipeline match(Predicate predicate) {
            stages.add((docs) -> docs.stream().filter(predicate::test).collect(Collectors.toList()));
            return this;
        }

        /** $group -- 分组聚合, key 为分组字段, aggField 为聚合结果字段名 */
        Pipeline group(String keyField, String aggField, Aggregator aggregator) {
            stages.add((docs) -> {
                Map<Object, List<Map<String, Object>>> groups = docs.stream()
                        .collect(Collectors.groupingBy(d -> d.get(keyField)));
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map.Entry<Object, List<Map<String, Object>>> e : groups.entrySet()) {
                    Map<String, Object> grouped = new LinkedHashMap<>();
                    grouped.put("_id", e.getKey()); // MongoDB $group 的 _id 是分组键
                    grouped.put(aggField, aggregator.aggregate(e.getValue()));
                    result.add(grouped);
                }
                return result;
            });
            return this;
        }

        /** $sort -- 排序 */
        Pipeline sort(Comparator<Map<String, Object>> comparator) {
            stages.add((docs) -> docs.stream().sorted(comparator).collect(Collectors.toList()));
            return this;
        }

        /** $project -- 投影, 只保留指定字段 (类似 SELECT col1, col2) */
        Pipeline project(String... fields) {
            Set<String> keep = new HashSet<>(Arrays.asList(fields));
            stages.add((docs) -> docs.stream().map(doc -> {
                Map<String, Object> projected = new LinkedHashMap<>();
                for (String f : fields) {
                    if (doc.containsKey(f)) {
                        projected.put(f, doc.get(f));
                    }
                }
                // 保留 _id 如果没在 fields 中则也要加上 (MongoDB 默认保留 _id)
                if (!keep.contains("_id") && doc.containsKey("_id")) {
                    projected.put("_id", doc.get("_id"));
                }
                return projected;
            }).collect(Collectors.toList()));
            return this;
        }

        /** $limit -- 限制返回条数 */
        Pipeline limit(int n) {
            stages.add((docs) -> docs.stream().limit(n).collect(Collectors.toList()));
            return this;
        }

        /** $skip -- 跳过前 N 条 */
        Pipeline skip(int n) {
            stages.add((docs) -> docs.stream().skip(n).collect(Collectors.toList()));
            return this;
        }

        /** 执行所有 stage */
        List<Map<String, Object>> execute(List<Map<String, Object>> input) {
            List<Map<String, Object>> current = input;
            for (PipelineStage stage : stages) {
                current = stage.apply(current);
                System.out.printf("  [stage] 输出 %d 条文档%n", current.size());
            }
            return current;
        }
    }

    @FunctionalInterface
    interface Predicate {
        boolean test(Map<String, Object> doc);
    }

    @FunctionalInterface
    interface Aggregator {
        Object aggregate(List<Map<String, Object>> docs);
    }

    @FunctionalInterface
    interface PipelineStage {
        List<Map<String, Object>> apply(List<Map<String, Object>> docs);
    }

    // ==================== 2. $lookup 模拟 ====================

    /**
     * $lookup 模拟左外连接: 将 orders 与 users 集合按 userId / _id 关联
     * 类似 SQL: SELECT * FROM orders LEFT JOIN users ON orders.userId = users._id
     */
    static void lookupJoinDemo() {
        System.out.println("--- $lookup (Left Outer Join) 模拟 ---");

        List<Map<String, Object>> orders = Arrays.asList(
            mapOf("_id", 1, "userId", "u1", "item", "book"),
            mapOf("_id", 2, "userId", "u2", "item", "pen"),
            mapOf("_id", 3, "userId", "u1", "item", "laptop"),
            mapOf("_id", 4, "userId", "u3", "item", "mouse")
        );

        List<Map<String, Object>> users = Arrays.asList(
            mapOf("_id", "u1", "name", "zhangsan", "age", 28),
            mapOf("_id", "u2", "name", "lisi",     "age", 32),
            mapOf("_id", "u4", "name", "wangwu",   "age", 25) // u3 不在 users 中, 测试 LEFT JOIN
        );

        System.out.println("订单集合:");
        orders.forEach(o -> System.out.println("  " + o));
        System.out.println("用户集合:");
        users.forEach(u -> System.out.println("  " + u));

        // $lookup 模拟
        List<Map<String, Object>> joined = lookup(orders, users, "userId", "_id", "user_info");
        System.out.println("\n$lookup 结果 (orders LEFT JOIN users ON userId = _id):");
        joined.forEach(j -> System.out.println("  " + j));

        System.out.println("\n$lookup 要点:");
        System.out.println("  1. MongoDB 3.2+ 开始支持 $lookup (类 SQL JOIN)");
        System.out.println("  2. from: 被关联的集合, localField: 本集合字段, foreignField: 目标集合字段");
        System.out.println("  3. 结果以数组形式嵌入 (as 指定的字段名)");
        System.out.println("  4. 对大集合做 $lookup 时应配合索引, 或考虑嵌套文档方案\n");
    }

    /** 模拟 $lookup 左外连接 */
    static List<Map<String, Object>> lookup(
            List<Map<String, Object>> local,
            List<Map<String, Object>> foreign,
            String localField, String foreignField, String asField) {

        // 对外表建索引: foreignField -> 文档列表
        Map<Object, List<Map<String, Object>>> foreignIndex = foreign.stream()
                .collect(Collectors.groupingBy(f -> f.get(foreignField), LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> localDoc : local) {
            Map<String, Object> joinedDoc = new LinkedHashMap<>(localDoc);
            Object key = localDoc.get(localField);
            List<Map<String, Object>> matched = foreignIndex.getOrDefault(key, Collections.emptyList());
            joinedDoc.put(asField, matched);
            result.add(joinedDoc);
        }
        return result;
    }
}