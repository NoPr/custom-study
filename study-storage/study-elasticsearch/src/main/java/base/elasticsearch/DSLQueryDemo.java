package base.elasticsearch;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DSL 查询模拟 —— match/term/range/bool/must/should/must_not 语法解析 +
 * QueryBuilder → 倒排索引查询 + 相关性评分(TF-IDF 简易版)。
 *
 * <p>ES 的核心查询能力基于 JSON DSL(领域特定语言)，将查询意图转化为对倒排索引的操作。
 * 本类模拟了 DSL 的解析、执行、评分全流程：</p>
 *
 * <ul>
 *   <li>match: 全文匹配，对查询词分词后再查倒排索引</li>
 *   <li>term: 精确匹配，不分词，直接查倒排索引</li>
 *   <li>range: 范围查询，gt/gte/lt/lte</li>
 *   <li>bool: 组合查询，must(AND)、should(OR)、must_not(NOT)、filter(无评分过滤)</li>
 *   <li>TF-IDF: 简易相关性评分公式</li>
 * </ul>
 *
 * @author study-tuling
 */
public class DSLQueryDemo {

    // ======================== 1. 文档与倒排索引 ========================

    /** 模拟文档存储：DocId → 文档各字段的 Map */
    static class DocumentStore {
        /** DocId → (字段名 → 字段值) */
        final Map<Integer, Map<String, String>> docs = new LinkedHashMap<>();
        /** 字段名 → (词条 → DocId列表) 的倒排索引 */
        final Map<String, Map<String, List<Integer>>> invertedIndex = new HashMap<>();
        /** 分词器 */
        final InvertedIndexDemo.SimpleTokenizer tokenizer = new InvertedIndexDemo.SimpleTokenizer();

        int addDoc(Map<String, String> fields) {
            int docId = docs.size() + 1;
            docs.put(docId, new HashMap<>(fields));
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                String value = entry.getValue();
                invertedIndex.putIfAbsent(fieldName, new HashMap<>());
                Map<String, List<Integer>> fieldIndex = invertedIndex.get(fieldName);

                // 为所有字段建倒排索引（term查询用原始值，match查询用分词后的值）
                for (String token : tokenizer.tokenize(value)) {
                    fieldIndex.computeIfAbsent(token, k -> new ArrayList<>()).add(docId);
                }
                // 也保留原始值作为 term 精确查询
                fieldIndex.computeIfAbsent(value.toLowerCase(), k -> new ArrayList<>()).add(docId);
            }
            return docId;
        }

        Map<String, String> getDoc(int docId) {
            return docs.get(docId);
        }
    }

    // ======================== 2. DSL 语法定义 ========================

    /** 查询节点抽象基类 */
    abstract static class QueryNode {
        abstract List<Integer> execute(DocumentStore store);
        abstract double score(int docId, DocumentStore store);
        abstract String explain(int docId, DocumentStore store);
    }

    /** term 查询：精确匹配，不分词 */
    static class TermQuery extends QueryNode {
        private final String field;
        private final String value;

        TermQuery(String field, String value) {
            this.field = field;
            this.value = value.toLowerCase();
        }

        @Override
        List<Integer> execute(DocumentStore store) {
            Map<String, List<Integer>> fieldIndex = store.invertedIndex.get(field);
            if (fieldIndex == null) return Collections.emptyList();
            return fieldIndex.getOrDefault(value, Collections.emptyList());
        }

        @Override
        double score(int docId, DocumentStore store) {
            // term 精确匹配：命中即满分
            List<Integer> docs = execute(store);
            return docs.contains(docId) ? 1.0 : 0.0;
        }

        @Override
        String explain(int docId, DocumentStore store) {
            return String.format("term(%s='%s') -> %s", field, value,
                    execute(store).contains(docId) ? "MATCH" : "NO_MATCH");
        }
    }

    /** match 查询：全文匹配，对查询词分词 */
    static class MatchQuery extends QueryNode {
        private final String field;
        private final String text;

        MatchQuery(String field, String text) {
            this.field = field;
            this.text = text;
        }

        @Override
        List<Integer> execute(DocumentStore store) {
            InvertedIndexDemo.SimpleTokenizer tokenizer = new InvertedIndexDemo.SimpleTokenizer();
            List<String> tokens = tokenizer.tokenize(text);
            Map<String, List<Integer>> fieldIndex = store.invertedIndex.get(field);
            if (fieldIndex == null) return Collections.emptyList();

            // OR 语义：任意 token 命中即可
            Set<Integer> result = new HashSet<>();
            for (String token : tokens) {
                List<Integer> docs = fieldIndex.get(token);
                if (docs != null) {
                    result.addAll(docs);
                }
            }
            return new ArrayList<>(result);
        }

        @Override
        double score(int docId, DocumentStore store) {
            return TFIDFScorer.score(docId, field, text, store);
        }

        @Override
        String explain(int docId, DocumentStore store) {
            return String.format("match(%s='%s') -> score=%.4f", field, text, score(docId, store));
        }
    }

    /** range 查询：范围匹配 */
    static class RangeQuery extends QueryNode {
        private final String field;
        private final Double gt, gte, lt, lte;

        RangeQuery(String field, Double gt, Double gte, Double lt, Double lte) {
            this.field = field;
            this.gt = gt;
            this.gte = gte;
            this.lt = lt;
            this.lte = lte;
        }

        @Override
        List<Integer> execute(DocumentStore store) {
            List<Integer> result = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, String>> entry : store.docs.entrySet()) {
                String valStr = entry.getValue().get(field);
                if (valStr == null) continue;
                try {
                    double val = Double.parseDouble(valStr);
                    if ((gt == null || val > gt) && (gte == null || val >= gte)
                            && (lt == null || val < lt) && (lte == null || val <= lte)) {
                        result.add(entry.getKey());
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        }

        @Override
        double score(int docId, DocumentStore store) {
            return execute(store).contains(docId) ? 1.0 : 0.0;
        }

        @Override
        String explain(int docId, DocumentStore store) {
            return String.format("range(%s: %s%s%s%s) -> %s", field,
                    gt != null ? ">" + gt + " " : "",
                    gte != null ? ">=" + gte + " " : "",
                    lt != null ? "<" + lt + " " : "",
                    lte != null ? "<=" + lte : "",
                    execute(store).contains(docId) ? "MATCH" : "NO_MATCH");
        }
    }

    /** bool 查询：复合查询 */
    static class BoolQuery extends QueryNode {
        private final List<QueryNode> must = new ArrayList<>();     // AND：必须匹配
        private final List<QueryNode> should = new ArrayList<>();   // OR：至少匹配一个
        private final List<QueryNode> mustNot = new ArrayList<>();  // NOT：必须不匹配
        private final List<QueryNode> filter = new ArrayList<>();   // 过滤（不计分）

        BoolQuery must(QueryNode q) { must.add(q); return this; }
        BoolQuery should(QueryNode q) { should.add(q); return this; }
        BoolQuery mustNot(QueryNode q) { mustNot.add(q); return this; }
        BoolQuery filter(QueryNode q) { filter.add(q); return this; }

        @Override
        List<Integer> execute(DocumentStore store) {
            Set<Integer> result = null;

            // must: 取交集
            for (QueryNode q : must) {
                Set<Integer> docs = new HashSet<>(q.execute(store));
                if (docs.isEmpty()) return Collections.emptyList();
                if (result == null) {
                    result = new HashSet<>(docs);
                } else {
                    result.retainAll(docs);
                }
            }

            // filter: 取交集（不计分）
            for (QueryNode q : filter) {
                Set<Integer> docs = new HashSet<>(q.execute(store));
                if (docs.isEmpty()) return Collections.emptyList();
                if (result == null) {
                    result = new HashSet<>(docs);
                } else {
                    result.retainAll(docs);
                }
            }

            // should: 取并集
            if (!should.isEmpty()) {
                Set<Integer> shouldDocs = new HashSet<>();
                for (QueryNode q : should) {
                    shouldDocs.addAll(q.execute(store));
                }
                if (result == null) {
                    result = shouldDocs;
                } else {
                    result.retainAll(shouldDocs);
                }
            }

            // must_not: 排除
            if (!mustNot.isEmpty()) {
                for (QueryNode q : mustNot) {
                    if (result == null) {
                        result = new HashSet<>(store.docs.keySet());
                    }
                    result.removeAll(q.execute(store));
                }
            }

            // 如果全是 must_not，返回全集排除后的结果
            if (result == null && !mustNot.isEmpty()) {
                result = new HashSet<>(store.docs.keySet());
                for (QueryNode q : mustNot) {
                    result.removeAll(q.execute(store));
                }
            }

            return result != null ? new ArrayList<>(result) : Collections.emptyList();
        }

        @Override
        double score(int docId, DocumentStore store) {
            // must 子句的评分求和
            double totalScore = 0;
            for (QueryNode q : must) {
                totalScore += q.score(docId, store);
            }
            for (QueryNode q : should) {
                totalScore += q.score(docId, store);
            }
            return totalScore;
        }

        @Override
        String explain(int docId, DocumentStore store) {
            StringBuilder sb = new StringBuilder("bool(");
            sb.append("must=").append(must.size());
            sb.append(", should=").append(should.size());
            sb.append(", must_not=").append(mustNot.size());
            sb.append(", filter=").append(filter.size());
            sb.append(") -> score=").append(String.format("%.4f", score(docId, store)));
            return sb.toString();
        }
    }

    // ======================== 3. TF-IDF 简易评分 ========================

    /**
     * 简易 TF-IDF 评分器。
     *
     * <p>TF(Term Frequency): 词在文档中出现的频率 = 词出现次数 / 文档总词数</p>
     * <p>IDF(Inverse Document Frequency): 逆文档频率 = log(总文档数 / 包含该词的文档数)</p>
     */
    static class TFIDFScorer {
        static double score(int docId, String field, String queryText, DocumentStore store) {
            InvertedIndexDemo.SimpleTokenizer tokenizer = new InvertedIndexDemo.SimpleTokenizer();
            List<String> queryTokens = tokenizer.tokenize(queryText);
            Map<String, List<Integer>> fieldIndex = store.invertedIndex.get(field);
            if (fieldIndex == null) return 0.0;

            double totalScore = 0.0;
            int totalDocs = store.docs.size();

            for (String token : queryTokens) {
                // TF: 该 token 在文档字段值中出现的比例（简化）
                double tf = computeTF(docId, field, token, store);
                // IDF: 逆文档频率
                double idf = computeIDF(token, fieldIndex, totalDocs);
                totalScore += tf * idf;
            }
            return totalScore;
        }

        private static double computeTF(int docId, String field, String token, DocumentStore store) {
            Map<String, String> doc = store.docs.get(docId);
            if (doc == null) return 0.0;
            String fieldValue = doc.get(field);
            if (fieldValue == null) return 0.0;
            InvertedIndexDemo.SimpleTokenizer tokenizer = new InvertedIndexDemo.SimpleTokenizer();
            List<String> allTokens = tokenizer.tokenize(fieldValue);
            long tokenCount = allTokens.stream().filter(t -> t.equals(token)).count();
            return allTokens.isEmpty() ? 0.0 : (double) tokenCount / allTokens.size();
        }

        private static double computeIDF(String token, Map<String, List<Integer>> fieldIndex, int totalDocs) {
            List<Integer> docs = fieldIndex.get(token);
            if (docs == null || docs.isEmpty()) return 0.0;
            return Math.log((double) totalDocs / docs.size());
        }
    }

    // ======================== 4. 演示入口 ========================

    static DocumentStore buildSampleDocs() {
        DocumentStore store = new DocumentStore();
        store.addDoc(Map.of("title", "Elasticsearch Guide", "content", "Elasticsearch is a distributed search and analytics engine", "price", "29"));
        store.addDoc(Map.of("title", "Lucene in Action", "content", "Lucene is the core search library used by Elasticsearch", "price", "45"));
        store.addDoc(Map.of("title", "Distributed Systems", "content", "Distributed systems need coordination and consensus algorithms", "price", "55"));
        store.addDoc(Map.of("title", "Data Science Handbook", "content", "Data science combines statistics programming and domain knowledge", "price", "35"));
        store.addDoc(Map.of("title", "Search Engine Architecture", "content", "Modern search engines use inverted index and relevance ranking", "price", "50"));
        return store;
    }

    static void demoTermQuery() {
        System.out.println("=== 1. term 精确查询 ===");
        DocumentStore store = buildSampleDocs();
        TermQuery q = new TermQuery("title", "Elasticsearch Guide");
        List<Integer> result = q.execute(store);
        System.out.println("DSL: { term: { title: 'Elasticsearch Guide' } }");
        for (int docId : result) {
            System.out.printf("  DocId=%d: %s | score: %.4f | %s%n",
                    docId, store.getDoc(docId).get("title"),
                    q.score(docId, store), q.explain(docId, store));
        }
    }

    static void demoMatchQuery() {
        System.out.println("\n=== 2. match 全文匹配 + TF-IDF 评分 ===");
        DocumentStore store = buildSampleDocs();
        MatchQuery q = new MatchQuery("content", "search engine");
        List<Integer> result = q.execute(store);
        System.out.println("DSL: { match: { content: 'search engine' } }");

        // 按评分排序输出
        result.sort((a, b) -> Double.compare(q.score(b, store), q.score(a, store)));
        for (int docId : result) {
            System.out.printf("  DocId=%d: %s | TF-IDF=%.4f | content='%s'%n",
                    docId, store.getDoc(docId).get("title"),
                    q.score(docId, store),
                    store.getDoc(docId).get("content"));
        }
    }

    static void demoRangeQuery() {
        System.out.println("\n=== 3. range 范围查询 ===");
        DocumentStore store = buildSampleDocs();
        RangeQuery q = new RangeQuery("price", null, 30.0, 50.0, null);
        List<Integer> result = q.execute(store);
        System.out.println("DSL: { range: { price: { gte: 30, lt: 50 } } }");
        for (int docId : result) {
            Map<String, String> doc = store.getDoc(docId);
            System.out.printf("  DocId=%d: %s | price=%s%n",
                    docId, doc.get("title"), doc.get("price"));
        }
    }

    static void demoBoolQuery() {
        System.out.println("\n=== 4. bool 复合查询(must+should+must_not+filter) ===");
        DocumentStore store = buildSampleDocs();

        BoolQuery boolQ = new BoolQuery()
                .must(new MatchQuery("content", "search"))
                .filter(new RangeQuery("price", null, 30.0, null, null))
                .mustNot(new TermQuery("title", "Lucene in Action"));

        System.out.println("DSL: { bool: { must: [match(content='search')], filter: [range(price gte 30)], must_not: [term(title='Lucene in Action')] } }");
        List<Integer> result = boolQ.execute(store);
        result.sort((a, b) -> Double.compare(boolQ.score(b, store), boolQ.score(a, store)));

        for (int docId : result) {
            Map<String, String> doc = store.getDoc(docId);
            System.out.printf("  DocId=%d: %s | price=%s | score=%.4f | %s%n",
                    docId, doc.get("title"), doc.get("price"),
                    boolQ.score(docId, store), boolQ.explain(docId, store));
        }
    }

    static void demoTFIDFExplain() {
        System.out.println("\n=== 5. TF-IDF 评分详解 ===");
        DocumentStore store = buildSampleDocs();
        System.out.println("查询: match(content='distributed search')");

        String[][] terms = {{"distributed", "search"}};
        for (String[] pair : terms) {
            for (String token : pair) {
                Map<String, List<Integer>> fieldIndex = store.invertedIndex.get("content");
                List<Integer> docs = fieldIndex != null ? fieldIndex.get(token) : Collections.emptyList();
                double idf = Math.log((double) store.docs.size() / Math.max(docs.size(), 0.5));
                System.out.printf("  Token='%s': 出现文档数=%d, IDF=%.4f%n",
                        token, docs != null ? docs.size() : 0, idf);
            }
        }
        System.out.println("\n  TF-IDF = TF(词频/文档总词数) × IDF(log(总文档/出现文档))");
        System.out.println("  评分高 = 词在文档中出现多 AND 词在总库中出现少");
    }

    static void demoFullDSLWorkflow() {
        System.out.println("\n=== 6. DSL 查询完整工作流 ===");
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│ 1. 用户发送 JSON DSL                         │");
        System.out.println("│    { match: { content: 'search engine' } }   │");
        System.out.println("│                     ↓                        │");
        System.out.println("│ 2. QueryBuilder 解析 → MatchQuery 对象       │");
        System.out.println("│                     ↓                        │");
        System.out.println("│ 3. 对查询词分词: 'search engine' → [search, engine]│");
        System.out.println("│                     ↓                        │");
        System.out.println("│ 4. 查倒排索引: term→docId 列表               │");
        System.out.println("│    search  → [1,5]                          │");
        System.out.println("│    engine  → [1,5]                          │");
        System.out.println("│                     ↓                        │");
        System.out.println("│ 5. Posting List 合并: [1,5] ∩ [1,5] = [1,5] │");
        System.out.println("│                     ↓                        │");
        System.out.println("│ 6. TF-IDF 评分排序: doc1=0.52, doc5=0.48    │");
        System.out.println("│                     ↓                        │");
        System.out.println("│ 7. 返回 Top-N 结果给用户                     │");
        System.out.println("└──────────────────────────────────────────────┘");
    }

    public static void main(String[] args) {
        demoTermQuery();
        demoMatchQuery();
        demoRangeQuery();
        demoBoolQuery();
        demoTFIDFExplain();
        demoFullDSLWorkflow();
    }
}