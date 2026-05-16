package base.elasticsearch;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 集群分片演示 —— routing hash(shardKey) % shardCount, primary shard vs replica,
 * 写入路由: request → coordinating node → primary shard → replica,
 * 搜索两阶段(query_then_fetch)。
 *
 * <p>ES 集群的核心能力在于水平扩展，通过分片(Shard)将海量数据分布到多台节点。
 * 本类模拟了分片路由、写入流程、搜索两阶段的完整过程：</p>
 *
 * <ul>
 *   <li>路由算法：hash(_routing) % num_primary_shards</li>
 *   <li>Primary Shard vs Replica：主分片负责写入，副本提供读取和容灾</li>
 *   <li>写入流程：coordinating → primary → replicate → ack</li>
 *   <li>搜索两阶段：Query(获取DocId+排序值) → Fetch(按DocId取完整文档)</li>
 * </ul>
 *
 * @author study-tuling
 */
public class ShardingClusterDemo {

    // ======================== 1. 分片定义 ========================

    /**
     * 分片：包含主分片(Primary)和副本分片(Replica)。
     * 每个 Shard 内部维护一个简化版的倒排索引和文档存储。
     */
    static class Shard {
        final int shardId;
        final boolean isPrimary;
        final String nodeName;
        /** 倒排索引：term → [docId, ...] */
        final Map<String, List<Integer>> invertedIndex = new HashMap<>();
        /** 文档存储：docId → 文档内容 */
        final Map<Integer, Map<String, String>> docStore = new LinkedHashMap<>();

        Shard(int shardId, boolean isPrimary, String nodeName) {
            this.shardId = shardId;
            this.isPrimary = isPrimary;
            this.nodeName = nodeName;
        }

        void index(int docId, Map<String, String> doc) {
            docStore.put(docId, doc);
            for (Map.Entry<String, String> entry : doc.entrySet()) {
                String value = entry.getValue().toLowerCase();
                for (String word : value.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+")) {
                    if (word.isEmpty()) continue;
                    invertedIndex.computeIfAbsent(word, k -> new ArrayList<>()).add(docId);
                }
            }
        }

        /**
         * 本地 Query 阶段：返回匹配的 (docId, _score)
         */
        List<Map.Entry<Integer, Double>> query(String keyword) {
            List<Integer> docIds = invertedIndex.getOrDefault(keyword.toLowerCase(), Collections.emptyList());
            List<Map.Entry<Integer, Double>> results = new ArrayList<>();
            for (int docId : docIds) {
                // 简易评分：出现一次计 1 分
                results.add(new AbstractMap.SimpleEntry<>(docId, 1.0));
            }
            return results;
        }

        String getDoc(int docId) {
            Map<String, String> doc = docStore.get(docId);
            return doc != null ? doc.toString() : null;
        }
    }

    /** 集群节点 */
    static class Node {
        final String name;
        final List<Shard> shards = new ArrayList<>();

        Node(String name) { this.name = name; }

        void addShard(Shard shard) { shards.add(shard); }

        @Override
        public String toString() { return name + "(shards=" + shards.size() + ")"; }
    }

    // ======================== 2. 集群管理 ========================

    /** 集群：管理节点、分片、路由 */
    static class Cluster {
        final List<Node> nodes = new ArrayList<>();
        final int numPrimaryShards;
        final int numReplicas;
        final Map<Integer, Shard> primaryShards = new HashMap<>();
        final Map<Integer, List<Shard>> replicaShards = new HashMap<>();
        int nextDocId = 1;

        Cluster(int numPrimaryShards, int numReplicas) {
            this.numPrimaryShards = numPrimaryShards;
            this.numReplicas = numReplicas;
        }

        /** 添加节点并分配分片 */
        void addNode(String nodeName) {
            Node node = new Node(nodeName);
            nodes.add(node);

            // 简易分配策略：每个节点负责一部分主分片
            int shardsPerNode = Math.max(1, numPrimaryShards / Math.max(nodes.size(), 1));
            int nodeIndex = nodes.size() - 1;
            int startShard = nodeIndex * shardsPerNode;
            int endShard = Math.min(startShard + shardsPerNode, numPrimaryShards);

            for (int shardId = startShard; shardId < endShard; shardId++) {
                Shard primary = new Shard(shardId, true, nodeName);
                node.addShard(primary);
                primaryShards.put(shardId, primary);

                // 创建副本分片，分配到其他节点
                List<Shard> replicas = replicaShards.computeIfAbsent(shardId, k -> new ArrayList<>());
                // 模拟副本分配（简化：实际由 master 节点统一分配）
                while (replicas.size() < numReplicas) {
                    Shard replica = new Shard(shardId, false, nodeName + "-replica-" + (replicas.size() + 1));
                    replicas.add(replica);
                    node.addShard(replica);
                }
            }
        }

        /** 路由算法：hash(_routing) % num_primary_shards */
        int route(String routingKey) {
            int hash = Math.abs(routingKey.hashCode());
            return hash % numPrimaryShards;
        }

        /** 获取主分片 */
        Shard getPrimaryShard(int shardId) {
            return primaryShards.get(shardId);
        }

        /** 获取某分片的所有副本 */
        List<Shard> getReplicaShards(int shardId) {
            return replicaShards.getOrDefault(shardId, Collections.emptyList());
        }

        /** 获取协调节点（轮询选择） */
        Node pickCoordinatingNode() {
            return nodes.get(ThreadLocalRandom.current().nextInt(nodes.size()));
        }

        // ======================== 写入流程 ========================

        /**
         * 写入文档流程：
         * 1. 请求到达协调节点(Coordinating Node)
         * 2. 协调节点根据 _routing 计算目标主分片
         * 3. 主分片写入完成后，同步复制到副本分片
         * 4. 所有副本确认后，协调节点返回成功
         */
        void indexDocument(String routingKey, Map<String, String> doc) {
            int docId = nextDocId++;
            int shardId = route(routingKey);

            System.out.printf("  [写入] DocId=%d, routing='%s' -> shard=%d%n", docId, routingKey, shardId);

            // Step 1: 协调节点路由
            Node coordinatingNode = pickCoordinatingNode();
            System.out.printf("  [协调节点] %s 接收请求, 路由到 shard-%d%n", coordinatingNode.name, shardId);

            // Step 2: 写入主分片
            Shard primary = getPrimaryShard(shardId);
            primary.index(docId, doc);
            System.out.printf("  [Primary] shard-%d@%s 写入完成%n", shardId, primary.nodeName);

            // Step 3: 同步副本
            List<Shard> replicas = getReplicaShards(shardId);
            for (Shard replica : replicas) {
                replica.index(docId, doc);
                System.out.printf("  [Replica] shard-%d@%s 同步完成%n", shardId, replica.nodeName);
            }

            System.out.printf("  [ACK] DocId=%d 写入成功 (primary + %d replicas)%n%n", docId, replicas.size());
        }

        // ======================== 搜索两阶段 ========================

        /**
         * 搜索两阶段(query_then_fetch)：
         *
         * <p><b>Phase 1: Query</b> —— 协调节点广播到所有相关分片(主分片或副本)，
         * 每个分片返回 Top-N 的 (docId, _score)，协调节点合并排序。</p>
         *
         * <p><b>Phase 2: Fetch</b> —— 根据合并后的 docId 列表，
         * 向持有这些文档的分片请求完整文档内容。</p>
         */
        List<Map<String, String>> search(String keyword, int size) {
            System.out.printf("%n=== 搜索: '%s', size=%d ===%n", keyword, size);

            Node coordinatingNode = pickCoordinatingNode();
            System.out.printf("[协调节点] %s 处理搜索请求%n", coordinatingNode.name);

            // ===== Phase 1: Query =====
            System.out.println("\n--- Phase 1: Query ---");
            List<Map.Entry<Integer, Double>> allResults = new ArrayList<>();

            for (int shardId = 0; shardId < numPrimaryShards; shardId++) {
                // 每个分片取主分片或副本之一（负载均衡）
                Shard shard = getPrimaryShard(shardId);
                List<Map.Entry<Integer, Double>> shardResults = shard.query(keyword);
                allResults.addAll(shardResults);
                System.out.printf("  shard-%d: 返回 %d 条结果%n", shardId, shardResults.size());
            }

            // 合并排序取 Top-N
            allResults.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<Map.Entry<Integer, Double>> topN = allResults.subList(0, Math.min(size, allResults.size()));

            System.out.println("  [合并排序] Query 阶段完成, Top-" + topN.size() + " docIds: "
                    + topN.stream().map(e -> "doc" + e.getKey()).collect(java.util.stream.Collectors.toList()));

            // ===== Phase 2: Fetch =====
            System.out.println("\n--- Phase 2: Fetch ---");
            List<Map<String, String>> finalResults = new ArrayList<>();

            for (Map.Entry<Integer, Double> entry : topN) {
                int docId = entry.getKey();
                int shardId = route("doc-" + docId);
                Shard primary = getPrimaryShard(shardId);
                String docContent = primary.getDoc(docId);
                Map<String, String> doc = new LinkedHashMap<>();
                doc.put("_id", String.valueOf(docId));
                doc.put("_score", String.format("%.2f", entry.getValue()));
                doc.put("_source", docContent);
                finalResults.add(doc);
                System.out.printf("  Fetch doc%d from shard-%d: %s%n", docId, shardId, docContent);
            }

            System.out.println("\n[搜索完成] 返回 " + finalResults.size() + " 条结果");
            return finalResults;
        }

        void printClusterInfo() {
            System.out.println("=== ES 集群拓扑 ===");
            System.out.println("主分片数: " + numPrimaryShards + ", 副本数: " + numReplicas);
            System.out.println("节点列表:");
            for (Node node : nodes) {
                System.out.println("  " + node.name + " (分片数=" + node.shards.size() + ")");
            }
            System.out.println("\n分片分配:");
            for (int shardId = 0; shardId < numPrimaryShards; shardId++) {
                Shard primary = getPrimaryShard(shardId);
                System.out.printf("  [P%d] Primary@%s%n", shardId, primary.nodeName);
                for (Shard replica : getReplicaShards(shardId)) {
                    System.out.printf("  [R%d] Replica@%s%n", shardId, replica.nodeName);
                }
            }
            System.out.println();
        }
    }

    // ======================== 3. 演示入口 ========================

    static void demoRoutingAlgorithm() {
        System.out.println("=== 1. 路由算法: hash(routing) % numPrimaryShards ===");
        int shardCount = 5;
        String[] routingKeys = {"user_1", "user_2", "user_99", "order_1001", "product_42"};
        System.out.println("分片数=" + shardCount);
        for (String key : routingKeys) {
            int hash = Math.abs(key.hashCode());
            int shardId = hash % shardCount;
            System.out.printf("  routing='%s' -> hash=%d -> shardId=%d%n", key, hash, shardId);
        }
        System.out.println("\n注意: 主分片数一旦确定不能修改(routing依赖)，只能扩副本数");
    }

    static void demoWriteFlow() {
        System.out.println("\n=== 2. 写入流程: Coordinating → Primary → Replica → ACK ===");
        Cluster cluster = new Cluster(3, 1);
        cluster.addNode("node-1");
        cluster.addNode("node-2");
        cluster.addNode("node-3");

        cluster.printClusterInfo();

        System.out.println("--- 写入演示 ---");
        cluster.indexDocument("product_1", Map.of("title", "iPhone 15", "price", "8999"));
        cluster.indexDocument("product_2", Map.of("title", "MacBook Pro", "price", "14999"));
        cluster.indexDocument("product_3", Map.of("title", "AirPods Pro", "price", "1999"));
    }

    static void demoSearchTwoPhase() {
        System.out.println("=== 3. 搜索两阶段: Query → Fetch ===");
        Cluster cluster = new Cluster(3, 1);
        cluster.addNode("node-1");
        cluster.addNode("node-2");

        // 先写入数据
        cluster.indexDocument("product_1", Map.of("title", "iPhone 15 Pro Max", "price", "9999"));
        cluster.indexDocument("product_2", Map.of("title", "MacBook Pro 16-inch", "price", "19999"));
        cluster.indexDocument("product_3", Map.of("title", "iPhone Charger", "price", "149"));
        cluster.indexDocument("product_4", Map.of("title", "MacBook Air", "price", "8999"));
        cluster.indexDocument("product_5", Map.of("title", "iPhone Case", "price", "49"));

        // 搜索
        List<Map<String, String>> results = cluster.search("iphone", 3);

        System.out.println("\n=== 最终搜索结果 ===");
        for (Map<String, String> result : results) {
            System.out.printf("  _id=%s, _score=%s, _source=%s%n",
                    result.get("_id"), result.get("_score"), result.get("_source"));
        }
    }

    static void demoShardVsReplica() {
        System.out.println("\n=== 4. Primary Shard vs Replica 职责对比 ===");
        System.out.println("┌──────────────┬─────────────────────┬─────────────────────┐");
        System.out.println("│    特性      │   Primary Shard     │   Replica Shard     │");
        System.out.println("├──────────────┼─────────────────────┼─────────────────────┤");
        System.out.println("│ 写入         │  负责处理所有写入    │  被动同步（只读）    │");
        System.out.println("│ 读取         │  可读               │  可读（负载均衡）    │");
        System.out.println("│ 索引操作     │  执行               │  复制                │");
        System.out.println("│ 故障恢复     │  丢失需从副本提升    │  可提升为 Primary    │");
        System.out.println("│ 数量限制     │  索引创建时定义      │  可动态调整          │");
        System.out.println("│ 分配策略     │  分散在不同节点      │  不与Primary同节点   │");
        System.out.println("└──────────────┴─────────────────────┴─────────────────────┘");
    }

    static void demoQueryThenFetch() {
        System.out.println("\n=== 5. Query-Then-Fetch 两阶段详解 ===");
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Phase 1: QUERY                                          │");
        System.out.println("│ ┌─────────────────────────────────────────────────────┐ │");
        System.out.println("│ │ 1. 协调节点收到搜索请求                              │ │");
        System.out.println("│ │ 2. 广播到索引所有分片(主分片或副本)                  │ │");
        System.out.println("│ │ 3. 每个分片本地执行查询, 返回 (docId, _score) 排序  │ │");
        System.out.println("│ │ 4. 协调节点合并所有分片结果, 全局排序               │ │");
        System.out.println("│ │ 5. 确定最终的 Top-N docId 列表                      │ │");
        System.out.println("│ └─────────────────────────────────────────────────────┘ │");
        System.out.println("│                         ↓                                │");
        System.out.println("│ Phase 2: FETCH                                           │");
        System.out.println("│ ┌─────────────────────────────────────────────────────┐ │");
        System.out.println("│ │ 6. 协调节点根据 docId → 路由到对应分片              │ │");
        System.out.println("│ │ 7. 向分片请求完整文档(_source)                      │ │");
        System.out.println("│ │ 8. 组装最终搜索结果返回客户端                       │ │");
        System.out.println("│ └─────────────────────────────────────────────────────┘ │");
        System.out.println("└─────────────────────────────────────────────────────────┘");
    }

    public static void main(String[] args) {
        demoRoutingAlgorithm();
        demoWriteFlow();
        demoSearchTwoPhase();
        demoShardVsReplica();
        demoQueryThenFetch();
    }
}