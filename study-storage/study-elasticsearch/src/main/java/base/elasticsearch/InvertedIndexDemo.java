package base.elasticsearch;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 倒排索引原理演示 —— 手写简易分词器(Tokenizer) + Term-DocId 映射(BloomFilter加速) +
 * 词典(FST模拟) + Posting List 压缩(FOR/RBM)。
 *
 * <p>倒排索引是 ES 搜索引擎的核心数据结构：将"文档→词"的正向关系
 * 反转为"词→文档列表"的映射，从而实现海量数据的毫秒级全文检索。</p>
 *
 * <p>核心组件：
 * <ul>
 *   <li>Tokenizer：将文档拆分为可索引的词条(Term)</li>
 *   <li>Dictionary(FST)：存储所有 Term → TermId 的映射，支持前缀压缩</li>
 *   <li>Posting List：每个 Term 对应的文档 ID 列表，使用 FOR/RBM 压缩</li>
 *   <li>BloomFilter：快速判断某个 Term 是否存在于索引中，避免无效磁盘读取</li>
 * </ul>
 * </p>
 *
 * @author study-tuling
 */
public class InvertedIndexDemo {

    // ======================== 1. 简易分词器 ========================

    /**
     * 简易分词器：将文本按空格、标点拆分，转小写后过滤停用词。
     * ES 默认使用 Standard Analyzer，核心是：char_filter → tokenizer → token_filter。
     */
    static class SimpleTokenizer {
        /** 英文停用词集合 */
        private static final Set<String> STOP_WORDS = Set.of(
                "the", "a", "an", "is", "are", "was", "were", "of", "in",
                "on", "at", "to", "for", "and", "or", "not", "it", "this", "that"
        );

        /**
         * 对文本分词并过滤停用词
         *
         * @param text 原始文本
         * @return 词条列表（已去重）
         */
        List<String> tokenize(String text) {
            if (text == null || text.isEmpty()) {
                return Collections.emptyList();
            }
            // 按非字母字符拆分 → 转小写 → 过滤停用词 → 去重
            return Arrays.stream(text.toLowerCase().split("[^a-z]+"))
                    .filter(word -> word.length() > 1)
                    .filter(word -> !STOP_WORDS.contains(word))
                    .distinct()
                    .collect(Collectors.toList());
        }
    }

    // ======================== 2. BloomFilter 加速 ========================

    /**
     * 简易布隆过滤器：用于快速判断某个 Term 是否"可能"存在于索引中。
     * 如果布隆过滤器说"不存在"，则一定不存在，避免磁盘 IO；
     * 如果布隆过滤器说"可能存在"，则需进一步查询确认。
     */
    static class SimpleBloomFilter {
        private final BitSet bitSet;
        private final int size;
        private final int[] hashSeeds;

        SimpleBloomFilter(int expectedElements, double falsePositiveRate) {
            // 根据预期元素数量和误判率计算位数组大小
            this.size = (int) (-expectedElements * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
            this.bitSet = new BitSet(size);
            // 使用3个不同的哈希种子
            this.hashSeeds = new int[]{31, 37, 41};
        }

        /** 添加元素到布隆过滤器 */
        void add(String term) {
            for (int seed : hashSeeds) {
                int hash = hash(term, seed);
                bitSet.set(Math.abs(hash % size));
            }
        }

        /** 判断元素是否可能存在 */
        boolean mightContain(String term) {
            for (int seed : hashSeeds) {
                int hash = hash(term, seed);
                if (!bitSet.get(Math.abs(hash % size))) {
                    return false;
                }
            }
            return true; // 可能存在（存在误判率）
        }

        private int hash(String s, int seed) {
            int hash = 0;
            for (char c : s.toCharArray()) {
                hash = seed * hash + c;
            }
            return hash;
        }
    }

    // ======================== 3. FST 词典模拟 ========================

    /**
     * FST(Finite State Transducer) 简易模拟。
     * ES 使用 FST 存储 Term Dictionary，将大量 Term 的前缀共享，
     * 极大压缩内存占用 —— 例如 "mon:1", "monday:2", "month:3" 共享 "mon" 前缀。
     *
     * <p>此处使用 Trie 树模拟 FST 的前缀压缩思想。</p>
     */
    static class FSTDictionary {
        static class Node {
            Map<Character, Node> children = new HashMap<>();
            Integer termId; // 叶子节点存储 TermId
        }

        private final Node root = new Node();
        private int nextId = 1;

        /** 向词典中添加 Term，返回 TermId */
        int put(String term) {
            Node current = root;
            for (char c : term.toCharArray()) {
                current = current.children.computeIfAbsent(c, k -> new Node());
            }
            if (current.termId == null) {
                current.termId = nextId++;
            }
            return current.termId;
        }

        /** 查找 Term 对应的 TermId */
        Integer get(String term) {
            Node current = root;
            for (char c : term.toCharArray()) {
                current = current.children.get(c);
                if (current == null) return null;
            }
            return current.termId;
        }

        /** 前缀搜索：返回所有以 prefix 开头的 Term */
        List<String> prefixSearch(String prefix) {
            List<String> result = new ArrayList<>();
            Node current = root;
            for (char c : prefix.toCharArray()) {
                current = current.children.get(c);
                if (current == null) return result;
            }
            collectTerms(current, new StringBuilder(prefix), result);
            return result;
        }

        private void collectTerms(Node node, StringBuilder prefix, List<String> result) {
            if (node.termId != null) {
                result.add(prefix.toString());
            }
            for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
                prefix.append(entry.getKey());
                collectTerms(entry.getValue(), prefix, result);
                prefix.deleteCharAt(prefix.length() - 1);
            }
        }
    }

    // ======================== 4. Posting List 压缩 ========================

    /**
     * Posting List 压缩器：包含 FOR(Frame Of Reference) 和 RBM(Roaring Bitmap)。
     *
     * <p>FOR 编码：先对文档 ID 做差值编码(Delta)，再按块(block)存储，
     * 每个块内用最小位数表示，减少存储空间。
     * 例如 [108, 115, 120] → delta [108, 7, 5] → 块内最大 108 需要 7 位。</p>
     *
     * <p>RBM 编码：将整数按高 16 位分桶，每个桶内根据基数选择使用
     * 数组容器(≤4096)或位图容器(>4096)，兼顾空间与效率。</p>
     */
    static class PostingListCompressor {

        // ---------- FOR 编码 ----------

        /** FOR 增量编码：先做差值编码，再记录每个块的位数 */
        static class FOREncoder {
            int[] encode(List<Integer> docIds) {
                if (docIds.isEmpty()) return new int[0];
                List<Integer> sorted = new ArrayList<>(docIds);
                Collections.sort(sorted);

                int[] deltas = new int[sorted.size()];
                deltas[0] = sorted.get(0);
                for (int i = 1; i < sorted.size(); i++) {
                    deltas[i] = sorted.get(i) - sorted.get(i - 1);
                }
                return deltas;
            }

            int[] decode(int[] deltas) {
                int[] original = new int[deltas.length];
                original[0] = deltas[0];
                for (int i = 1; i < deltas.length; i++) {
                    original[i] = original[i - 1] + deltas[i];
                }
                return original;
            }

            /** 计算按 FOR 编码后的节省比例（简化版：直接比较 delta 值域范围） */
            double savingsRatio(List<Integer> docIds) {
                if (docIds.size() <= 1) return 0;
                int[] deltas = encode(docIds);
                int maxDelta = Arrays.stream(deltas).max().orElse(0);
                int bitsNeeded = 32 - Integer.numberOfLeadingZeros(maxDelta);
                int maxOriginal = Collections.max(docIds);
                int bitsOriginal = 32 - Integer.numberOfLeadingZeros(maxOriginal);
                return (1.0 - (double) bitsNeeded / bitsOriginal) * 100;
            }
        }

        // ---------- RBM 编码 ----------

        /**
         * RBM(Roaring Bitmap) 简易模拟：按高 16 位分桶。
         * 桶内元素 ≤ 4096 时用有序数组，> 4096 时用 BitSet。
         */
        static class RoaringBitmap {
            private final Map<Integer, Object> containers = new HashMap<>();

            /** 添加文档 ID */
            void add(int docId) {
                int bucketKey = docId >>> 16; // 高 16 位作为桶 key
                if (!containers.containsKey(bucketKey)) {
                    containers.put(bucketKey, new ArrayList<Integer>());
                }
                @SuppressWarnings("unchecked")
                List<Integer> bucket = (List<Integer>) containers.get(bucketKey);
                int low16 = docId & 0xFFFF; // 低 16 位
                int pos = Collections.binarySearch(bucket, low16);
                if (pos < 0) {
                    bucket.add(-pos - 1, low16);
                }
                // 超过 4096 个元素时转为 BitSet 容器（简化：不转换，仅示意）
            }

            boolean contains(int docId) {
                int bucketKey = docId >>> 16;
                Object container = containers.get(bucketKey);
                if (container == null) return false;
                @SuppressWarnings("unchecked")
                List<Integer> bucket = (List<Integer>) container;
                return Collections.binarySearch(bucket, docId & 0xFFFF) >= 0;
            }

            int size() {
                return containers.values().stream()
                        .mapToInt(b -> ((List<?>) b).size())
                        .sum();
            }
        }
    }

    // ======================== 5. 完整倒排索引 ========================

    /**
     * 完整倒排索引：整合分词器、布隆过滤器、FST 词典、Posting List。
     */
    static class InvertedIndex {
        private final SimpleTokenizer tokenizer = new SimpleTokenizer();
        private final SimpleBloomFilter bloomFilter = new SimpleBloomFilter(1000, 0.01);
        private final FSTDictionary dictionary = new FSTDictionary();
        private final Map<Integer, List<Integer>> postingList = new HashMap<>(); // TermId → DocId 列表
        private final PostingListCompressor.FOREncoder forEncoder = new PostingListCompressor.FOREncoder();
        private final Map<Integer, String> docStore = new HashMap<>(); // DocId → 原始文档

        /** 索引一篇文档 */
        void index(int docId, String content) {
            docStore.put(docId, content);
            List<String> terms = tokenizer.tokenize(content);
            for (String term : terms) {
                bloomFilter.add(term);
                int termId = dictionary.put(term);
                postingList.computeIfAbsent(termId, k -> new ArrayList<>()).add(docId);
            }
        }

        /** 单 Term 查询 */
        List<Integer> search(String term) {
            // 1. 布隆过滤：快速排除不存在的 Term
            if (!bloomFilter.mightContain(term)) {
                return Collections.emptyList();
            }
            // 2. 词典查找 TermId
            Integer termId = dictionary.get(term);
            if (termId == null) {
                return Collections.emptyList();
            }
            // 3. 返回 Posting List
            return postingList.getOrDefault(termId, Collections.emptyList());
        }

        /** AND 查询：多个 Term 的文档交集 */
        List<Integer> andSearch(List<String> terms) {
            if (terms.isEmpty()) return Collections.emptyList();
            List<Integer> result = null;
            for (String term : terms) {
                List<Integer> docs = search(term);
                if (docs.isEmpty()) return Collections.emptyList();
                if (result == null) {
                    result = new ArrayList<>(docs);
                } else {
                    result.retainAll(docs);
                }
            }
            return result != null ? result : Collections.emptyList();
        }

        /** OR 查询：多个 Term 的文档并集 */
        List<Integer> orSearch(List<String> terms) {
            Set<Integer> result = new HashSet<>();
            for (String term : terms) {
                result.addAll(search(term));
            }
            return new ArrayList<>(result);
        }

        /** 获取文档预览 */
        String getDoc(int docId) {
            return docStore.getOrDefault(docId, "NOT_FOUND");
        }

        FSTDictionary getDictionary() {
            return dictionary;
        }

        PostingListCompressor.FOREncoder getForEncoder() {
            return forEncoder;
        }

        Map<Integer, List<Integer>> getPostingList() {
            return postingList;
        }
    }

    // ======================== 演示入口 ========================

    static void demoTokenizer() {
        System.out.println("=== 1. 简易分词器演示 ===");
        SimpleTokenizer tokenizer = new SimpleTokenizer();
        String text = "The quick brown fox jumps over the lazy dog";
        List<String> tokens = tokenizer.tokenize(text);
        System.out.println("原文: " + text);
        System.out.println("分词结果(去停用词): " + tokens);
        System.out.println("词条数: " + tokens.size());
    }

    static void demoBloomFilter() {
        System.out.println("\n=== 2. 布隆过滤器加速演示 ===");
        SimpleBloomFilter bf = new SimpleBloomFilter(100, 0.01);
        bf.add("elasticsearch");
        bf.add("lucene");
        bf.add("inverted");
        System.out.println("添加 term: elasticsearch, lucene, inverted");

        System.out.printf("%-20s -> %s%n", "elasticsearch", bf.mightContain("elasticsearch") ? "可能存在 ✓" : "不存在 ✗");
        System.out.printf("%-20s -> %s%n", "solr", bf.mightContain("solr") ? "可能存在 ✓" : "不存在 ✗");
        System.out.printf("%-20s -> %s%n", "mongodb", bf.mightContain("mongodb") ? "可能存在 ✓" : "不存在 ✗");
        System.out.println("(可能误判为true，但绝不会漏判false)");
    }

    static void demoFSTDictionary() {
        System.out.println("\n=== 3. FST 词典（Trie模拟前缀压缩）演示 ===");
        FSTDictionary dict = new FSTDictionary();
        String[] terms = {"mon", "monday", "month", "monkey", "moon", "mood"};
        for (String term : terms) {
            int id = dict.put(term);
            System.out.printf("  Term: %-10s -> TermId: %d%n", term, id);
        }
        System.out.println("\n前缀搜索 'mon': " + dict.prefixSearch("mon"));
        System.out.println("前缀搜索 'moo': " + dict.prefixSearch("moo"));
        System.out.println("mon/monday/month/monkey 共享 'mon' 前缀，节省存储空间");
    }

    static void demoFORCompression() {
        System.out.println("\n=== 4. Posting List FOR 增量压缩演示 ===");
        PostingListCompressor.FOREncoder forEncoder = new PostingListCompressor.FOREncoder();
        List<Integer> docIds = List.of(108, 115, 120, 125, 131);
        int[] deltas = forEncoder.encode(docIds);
        System.out.println("原始 DocId: " + docIds);
        System.out.print("Delta 编码: [");
        for (int i = 0; i < deltas.length; i++) {
            System.out.print(deltas[i] + (i < deltas.length - 1 ? ", " : ""));
        }
        System.out.println("]");
        double savings = forEncoder.savingsRatio(docIds);
        System.out.printf("FOR 压缩节省空间约: %.1f%% (原始max=%d, delta max=%d)%n",
                savings, Collections.max(docIds), Arrays.stream(deltas).max().orElse(0));
    }

    static void demoFullInvertedIndex() {
        System.out.println("\n=== 5. 完整倒排索引集成演示 ===");
        InvertedIndex index = new InvertedIndex();

        index.index(1, "Elasticsearch is a distributed search engine");
        index.index(2, "Lucene is the core library of Elasticsearch");
        index.index(3, "Search engines need inverted index for fast text search");
        index.index(4, "Distributed systems require coordination between nodes");
        index.index(5, "Elasticsearch uses Lucene inverted index internally");

        System.out.println("已索引 " + index.docStore.size() + " 篇文档\n");

        System.out.println("--- 单 Term 查询 ---");
        System.out.println("  elasticsearch -> DocId: " + index.search("elasticsearch"));
        System.out.println("  lucene        -> DocId: " + index.search("lucene"));
        System.out.println("  search        -> DocId: " + index.search("search"));

        System.out.println("\n--- AND 查询 (elasticsearch AND lucene) ---");
        System.out.println("  结果: " + index.andSearch(List.of("elasticsearch", "lucene")));

        System.out.println("\n--- OR 查询 (distributed OR coordination) ---");
        System.out.println("  结果: " + index.orSearch(List.of("distributed", "coordination")));

        System.out.println("\n--- 文档内容预览 ---");
        for (Integer docId : index.search("elasticsearch")) {
            System.out.printf("  DocId=%d: %s%n", docId, index.getDoc(docId));
        }
    }

    static void demoPostingListMerge() {
        System.out.println("\n=== 6. Posting List 求交(AND)跳表加速演示 ===");
        List<Integer> list1 = List.of(1, 3, 5, 7, 9, 11, 13, 15);
        List<Integer> list2 = List.of(3, 6, 7, 9, 12, 15);
        System.out.println("List1: " + list1);
        System.out.println("List2: " + list2);

        // 双指针归并求交集
        List<Integer> intersection = new ArrayList<>();
        int i = 0, j = 0;
        while (i < list1.size() && j < list2.size()) {
            int a = list1.get(i), b = list2.get(j);
            if (a < b) {
                i++;
                if (i < list1.size() && (i % 3 == 0)) {
                    // 跳表加速：跳过不可能重合的区间
                    while (i < list1.size() && list1.get(i) < b) i++;
                }
            } else if (a > b) {
                j++;
            } else {
                intersection.add(a);
                i++;
                j++;
            }
        }
        System.out.println("交集结果: " + intersection);
        System.out.println("核心: 两个有序列表合并，利用跳表(SkipList)跳过不可能命中的区间");
    }

    public static void main(String[] args) {
        demoTokenizer();
        demoBloomFilter();
        demoFSTDictionary();
        demoFORCompression();
        demoFullInvertedIndex();
        demoPostingListMerge();
    }
}