package base.mongodb;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MongoDB 索引类型手写模拟
 * 单字段索引: 最简单, 在单个字段上建 B-Tree 索引加速等值/范围查询
 * 复合索引: 多字段组合, 遵循 ESR 规则 (Equality-Sort-Range), 有前缀匹配特性
 * 多键索引: 针对数组字段, MongoDB 为每个数组元素创建索引条目
 * 文本索引: 倒排索引, 对字符串字段分词后建立
 * 覆盖索引: 查询返回字段全部在索引中, 无需回查文档 (类似 MySQL 的 Using index)
 * explain(): 输出查询执行的详细信息 (扫描方式/索引命中/执行时间)
 */
public class IndexDemo {

    public static void main(String[] args) {
        System.out.println("========== MongoDB 索引类型手写模拟 ==========\n");

        singleFieldIndexDemo();
        compoundIndexDemo();
        multiKeyIndexDemo();
        textIndexDemo();
        coveringIndexDemo();
        explainDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 1. 单字段索引 -- B-Tree 查询 ====================

    /**
     * 手写 B-Tree 实现单字段索引查询
     * 索引存储: fieldValue -> List<documentId>
     */
    static void singleFieldIndexDemo() {
        System.out.println("--- 1. 单字段索引 (B-Tree 模拟) ---");

        // 原始文档
        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(mapOf("_id", 1, "name", "zhangsan", "age", 25));
        docs.add(mapOf("_id", 2, "name", "lisi",     "age", 30));
        docs.add(mapOf("_id", 3, "name", "wangwu",   "age", 25));
        docs.add(mapOf("_id", 4, "name", "zhaoliu",  "age", 35));
        docs.add(mapOf("_id", 5, "name", "sunqi",    "age", 30));

        // 在 age 字段上建 B-Tree 索引
        BTreeIndex ageIndex = new BTreeIndex();
        for (Map<String, Object> doc : docs) {
            ageIndex.insert((int) doc.get("age"), (int) doc.get("_id"));
        }
        System.out.println("在 age 上建索引: " + ageIndex);

        // 等值查询: db.users.find({age: 30})
        List<Integer> resultIds = ageIndex.search(30);
        System.out.println("等值查询 age=30 -> 文档 _id: " + resultIds);

        // 范围查询: db.users.find({age: {$gte: 26, $lte: 35}})
        List<Integer> rangeIds = ageIndex.rangeSearch(26, 35);
        System.out.println("范围查询 age 26-35 -> 文档 _id: " + rangeIds);

        System.out.println("索引原理: B-Tree 按 age 排序, 叶子存 _id 列表, 等值 O(logN), 范围 O(logN+M)\n");
    }

    /** 简化的 B-Tree 索引: TreeMap 模拟 */
    static class BTreeIndex {
        private final TreeMap<Integer, List<Integer>> tree = new TreeMap<>();

        void insert(int key, int docId) {
            tree.computeIfAbsent(key, k -> new ArrayList<>()).add(docId);
        }

        List<Integer> search(int key) {
            return tree.getOrDefault(key, Collections.emptyList());
        }

        List<Integer> rangeSearch(int from, int to) {
            List<Integer> result = new ArrayList<>();
            for (List<Integer> ids : tree.subMap(from, true, to, true).values()) {
                result.addAll(ids);
            }
            return result;
        }

        @Override
        public String toString() {
            return tree.entrySet().stream()
                    .map(e -> e.getKey() + "->ids" + e.getValue())
                    .collect(Collectors.joining(", "));
        }
    }

    // ==================== 2. 复合索引 + ESR 规则 ====================

    /**
     * 复合索引与 ESR 规则: Equality -> Sort -> Range
     * 索引字段顺序影响查询能否使用索引, 最左前缀匹配
     */
    static void compoundIndexDemo() {
        System.out.println("--- 2. 复合索引 + ESR 规则 ---");

        System.out.println("索引: {category: 1, price: 1, create_time: 1}");
        System.out.println("  ESR 规则: Equality(等值)在前 -> Sort(排序)其次 -> Range(范围)最后");
        System.out.println("  category = ... AND price > ... AND ORDER BY create_time -> 全命中");
        System.out.println("  price > ... AND ORDER BY create_time -> 只用不上 category, 索引部分命中");
        System.out.println("  create_time > ... -> 跳过了 category 和 price, 索引不命中 (全表扫描)\n");

        // 复合索引模拟: 按 (category, price) 两级索引
        CompoundIndex ci = new CompoundIndex();
        ci.insert("book", 50, 1);
        ci.insert("book", 100, 2);
        ci.insert("book", 150, 3);
        ci.insert("pen", 10, 4);
        ci.insert("pen", 20, 5);
        ci.insert("pen", 30, 6);

        System.out.println("复合索引 (category, price):");
        ci.print();

        // 等值 category + 范围 price -> 全命中
        System.out.println("查询 category=book AND price>=100 -> 全命中:");
        System.out.println("  _id: " + ci.search("book", 100, Integer.MAX_VALUE));

        System.out.println("最左前缀含义: 跳过 category 直接查 price 则索引失效 (无法利用 B-Tree 有序性)\n");
    }

    /** 复合索引: 两级 Map */
    static class CompoundIndex {
        private final Map<String, TreeMap<Integer, List<Integer>>> index = new LinkedHashMap<>();

        void insert(String category, int price, int docId) {
            index.computeIfAbsent(category, k -> new TreeMap<>())
                  .computeIfAbsent(price, k -> new ArrayList<>())
                  .add(docId);
        }

        List<Integer> search(String category, int priceFrom, int priceTo) {
            TreeMap<Integer, List<Integer>> priceTree = index.get(category);
            if (priceTree == null) return Collections.emptyList();
            List<Integer> result = new ArrayList<>();
            for (List<Integer> ids : priceTree.subMap(priceFrom, true, priceTo, true).values()) {
                result.addAll(ids);
            }
            return result;
        }

        void print() {
            index.forEach((cat, pt) ->
                pt.forEach((price, ids) ->
                    System.out.printf("  [%s, %d] -> _id: %s%n", cat, price, ids)));
        }
    }

    // ==================== 3. 多键索引 (数组) ====================

    /**
     * 多键索引: 字段值为数组时, MongoDB 为每个数组元素创建一条索引
     * 例: tags: ["java", "mongodb"] -> 两条索引条目
     */
    static void multiKeyIndexDemo() {
        System.out.println("--- 3. 多键索引 (数组字段) ---");

        MultiKeyIndex mki = new MultiKeyIndex();
        mki.insert(1, Arrays.asList("java", "mongodb", "spring"));
        mki.insert(2, Arrays.asList("java", "redis"));
        mki.insert(3, Arrays.asList("mongodb", "kafka"));
        mki.insert(4, Arrays.asList("spring", "docker"));

        System.out.println("索引 db.articles.createIndex({tags: 1})");
        System.out.println(mki);

        System.out.println("查询 tags = 'java' -> _id: " + mki.search("java"));
        System.out.println("查询 tags = 'mongodb' -> _id: " + mki.search("mongodb"));

        System.out.println("特点: 一个数组文档拆成多条索引, 查询时遍历匹配");
        System.out.println("限制: 一个文档最多 1 个多键索引字段 (防止笛卡尔积爆炸)\n");
    }

    /** 多键索引: tag -> docId 列表 */
    static class MultiKeyIndex {
        private final Map<String, List<Integer>> index = new LinkedHashMap<>();

        void insert(int docId, List<String> tags) {
            for (String tag : tags) {
                index.computeIfAbsent(tag, k -> new ArrayList<>()).add(docId);
            }
        }

        List<Integer> search(String tag) {
            return index.getOrDefault(tag, Collections.emptyList());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            index.forEach((tag, ids) -> sb.append(String.format("  %s -> _id: %s%n", tag, ids)));
            return sb.toString();
        }
    }

    // ==================== 4. 文本索引 (倒排索引) ====================

    /**
     * 文本索引: 倒排索引, 对字符串内容分词后建立
     * 中文需配合分词器, 英文按空格+标点分词
     */
    static void textIndexDemo() {
        System.out.println("--- 4. 文本索引 (倒排索引) ---");

        TextIndex ti = new TextIndex();
        ti.index(1, "MongoDB is a NoSQL database");
        ti.index(2, "Java Spring Boot framework");
        ti.index(3, "MongoDB aggregation pipeline tutorial");
        ti.index(4, "Spring Boot and MongoDB integration");

        System.out.println(ti);

        // 文本搜索
        System.out.println("搜索 'mongodb' -> _id: " + ti.search("mongodb"));
        System.out.println("搜索 'spring' -> _id: " + ti.search("spring"));

        System.out.println("倒排索引原理: 词 -> 文档列表, 支持 $text 搜索和权重评分\n");
    }

    /** 简易倒排索引 */
    static class TextIndex {
        private final Map<String, List<Integer>> inverted = new LinkedHashMap<>();

        void index(int docId, String text) {
            for (String word : text.toLowerCase().split("[^a-zA-Z]+")) {
                if (word.isEmpty()) continue;
                inverted.computeIfAbsent(word, k -> new ArrayList<>()).add(docId);
            }
        }

        List<Integer> search(String word) {
            return inverted.getOrDefault(word.toLowerCase(), Collections.emptyList());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("文本倒排索引:\n");
            inverted.forEach((word, ids) -> sb.append(String.format("  %s -> _id: %s%n", word, ids)));
            return sb.toString();
        }
    }

    // ==================== 5. 覆盖索引 ====================

    /**
     * 覆盖索引: 查询返回的字段全部包含在索引中, 无需回表查文档
     * db.users.find({age: 30}, {age: 1, _id: 0}) 且 {age: 1} 上有索引 -> 覆盖
     */
    static void coveringIndexDemo() {
        System.out.println("--- 5. 覆盖索引 vs 回表 ---");

        System.out.println("假设集合 users 拥有字段: _id, name, age, email");
        System.out.println("索引: {age: 1, name: 1}\n");

        // 场景 1: 覆盖索引
        System.out.println("查询 1: db.users.find({age: 30}, {age: 1, name: 1, _id: 0})");
        System.out.println("  -> 索引包含 age+name, 返回字段全在索引中, 无需回表查文档 = 覆盖索引");
        System.out.println("  -> explain() 输出: totalDocsExamined=0 (未扫描文档, 全从索引获取)\n");

        // 场景 2: 非覆盖索引
        System.out.println("查询 2: db.users.find({age: 30}, {age: 1, name: 1, email: 1, _id: 0})");
        System.out.println("  -> 索引不含 email, 需要回表查完整文档获取 email = 非覆盖索引");
        System.out.println("  -> explain() 输出: totalDocsExamined>0 (需要扫描文档)\n");

        // 模拟
        System.out.println("覆盖索引模拟:");
        Map<String, Object> indexEntry = mapOf("age", 30, "name", "zhangsan");
        System.out.println("  索引条目 (含 age+name): " + indexEntry);
        System.out.println("  直接返回, 无需查文档 (覆盖索引)");

        Map<String, Object> doc = mapOf("_id", 1, "name", "zhangsan", "age", 30, "email", "zs@test.com");
        System.out.println("  完整文档 (含 email): " + doc);
        System.out.println("  从索引找到 age+name 后, 再回表取 email (非覆盖索引)\n");
    }

    // ==================== 6. explain() 输出模拟 ====================

    /**
     * explain() 输出模拟: 展示查询执行的详细信息
     * winningPlan: 命中索引的名称
     * indexBounds: 索引扫描范围
     * totalDocsExamined: 实际扫描的文档数
     * totalKeysExamined: 扫描的索引键数
     * executionTimeMillis: 执行耗时
     */
    static void explainDemo() {
        System.out.println("--- 6. explain() 输出模拟 ---");

        System.out.println("db.users.find({age: {$gte: 25, $lte: 35}}).explain(\"executionStats\")");
        System.out.println("{");
        System.out.println("  \"queryPlanner\": {");
        System.out.println("    \"winningPlan\": {");
        System.out.println("      \"stage\": \"FETCH\",");
        System.out.println("      \"inputStage\": {");
        System.out.println("        \"stage\": \"IXSCAN\",                       -- 索引扫描");
        System.out.println("        \"keyPattern\": { \"age\": 1 },");
        System.out.println("        \"indexName\": \"age_1\",");
        System.out.println("        \"indexBounds\": { \"age\": [\"[25, 35]\"] }  -- 索引边界");
        System.out.println("      }");
        System.out.println("    }");
        System.out.println("  },");
        System.out.println("  \"executionStats\": {");
        System.out.println("    \"nReturned\": 3,                               -- 返回文档数");
        System.out.println("    \"totalKeysExamined\": 3,                       -- 扫描索引键数");
        System.out.println("    \"totalDocsExamined\": 3,                       -- 扫描文档数 (= keys 则全命中)");
        System.out.println("    \"executionTimeMillis\": 2                      -- 执行耗时");
        System.out.println("  }");
        System.out.println("}");

        System.out.println("\n良好 explain: totalKeysExamined == nReturned (精准索引命中)");
        System.out.println("问题 explain: totalDocsExamined >> nReturned (扫描了大量不相关文档)\n");
    }

    // ==================== 工具方法 ====================

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
}