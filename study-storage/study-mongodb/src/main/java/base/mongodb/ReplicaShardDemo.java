package base.mongodb;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * MongoDB 副本集 (Replica Set) 与分片 (Sharding) 手写模拟
 *
 * Replica Set (3 节点):
 *   heartbeat 心跳检测: 每 2s 发送一次, 10s 无响应判定节点不可达
 *   选举机制: 基于 priority 权重 + Raft 协议, 多数派投票选出新 Primary
 *   故障转移: Primary 宕机 -> 选举新 Primary (秒级) -> 客户端重连
 *   Oplog 增量同步: Primary 将所有写操作写入 oplog (capped collection),
 *                   Secondary 持续 tail oplog 追赶
 *
 * Sharding:
 *   Chunk 范围: 每个 shard 负责 hash key 的连续区间
 *   Balancer: 监控各 shard chunk 数量, 不均衡时自动迁移
 *   Chunk 迁移: 将 chunk 从一个 shard 移至另一个, 元数据更新到 config server
 */
public class ReplicaShardDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== MongoDB 副本集 + 分片手写模拟 ==========\n");

        replicaSetDemo();
        shardingDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 副本集 ====================

    /** 副本集: 3 节点 Primary + Secondary + Secondary, 心跳检测与故障转移 */
    static void replicaSetDemo() throws Exception {
        System.out.println("--- Replica Set (3 节点) 故障转移 ---");

        ReplicaSet rs = new ReplicaSet();
        rs.init();

        System.out.println("初始状态: " + rs.status());

        // 模拟 Primary 故障
        rs.primaryFail();
        System.out.println("Primary 宕机! 触发选举...");
        rs.electNewPrimary();
        System.out.println("新状态: " + rs.status());

        // 模拟 Oplog 增量同步
        oplogSyncDemo(rs);

        System.out.println();
    }

    /**
     * Replica Set 节点模型
     * priority 越高越容易被选为 Primary (值 0-1000, 0 表示永不参选)
     */
    static class ReplicaNode {
        String nodeId;
        boolean isPrimary;
        boolean isAlive;
        int priority;
        long lastHeartbeat; // 最后心跳时间戳
        long term;          // 选举任期

        ReplicaNode(String nodeId, int priority) {
            this.nodeId = nodeId;
            this.priority = priority;
            this.isAlive = true;
            this.lastHeartbeat = System.currentTimeMillis();
            this.term = 0;
        }

        @Override
        public String toString() {
            return nodeId + "[primary=" + isPrimary + ", alive=" + isAlive +
                   ", priority=" + priority + ", term=" + term + "]";
        }
    }

    /** 副本集: 管理 3 个节点, 心跳检测 + 选举 */
    static class ReplicaSet {
        List<ReplicaNode> nodes = new ArrayList<>();

        void init() {
            nodes.add(new ReplicaNode("rs1", 10)); // priority 最高, 初始 Primary
            nodes.add(new ReplicaNode("rs2", 5));
            nodes.add(new ReplicaNode("rs3", 5));
            nodes.get(0).isPrimary = true; // rs1 初始为 Primary
            System.out.println("初始化: rs1(Primary), rs2(Secondary), rs3(Secondary)");
        }

        /** 获取当前 Primary */
        ReplicaNode primary() {
            return nodes.stream().filter(n -> n.isPrimary && n.isAlive).findFirst().orElse(null);
        }

        /** 模拟心跳检测 */
        void heartbeat() {
            for (ReplicaNode node : nodes) {
                node.lastHeartbeat = System.currentTimeMillis();
            }
        }

        /** 模拟 Primary 宕机 */
        void primaryFail() {
            ReplicaNode p = primary();
            if (p != null) {
                p.isAlive = false;
                p.isPrimary = false;
            }
        }

        /**
         * 选举新 Primary: 基于 priority + 多数派投票 (Raft 简化)
         * 规则: 在存活的节点中, priority 最高者当选,
         *       若多个同 priority, term 大者当选
         */
        void electNewPrimary() {
            List<ReplicaNode> aliveNodes = nodes.stream()
                    .filter(n -> n.isAlive)
                    .collect(Collectors.toList());

            if (aliveNodes.isEmpty()) {
                System.out.println("  [选举] 无存活节点, 选举失败!");
                return;
            }

            // 按 priority 降序, term 升序选出新 Primary
            ReplicaNode newPrimary = aliveNodes.stream()
                    .max(Comparator.comparingInt((ReplicaNode n) -> n.priority)
                            .thenComparingLong(n -> n.term))
                    .orElse(null);

            if (newPrimary != null) {
                newPrimary.isPrimary = true;
                newPrimary.term++; // 新任期递增
                System.out.println("  [选举] " + newPrimary.nodeId +
                        " 当选新 Primary (priority=" + newPrimary.priority +
                        ", term=" + newPrimary.term + ", 得票=" + aliveNodes.size() + "/" + nodes.size() + ")");
            }
        }

        String status() {
            return nodes.stream().map(ReplicaNode::toString).collect(Collectors.joining("\n  "));
        }
    }

    /** Oplog 增量同步模拟 */
    static void oplogSyncDemo(ReplicaSet rs) {
        System.out.println("--- Oplog 增量同步 ---");

        Oplog oplog = new Oplog(100);
        // Primary 写入操作 -> 记录 oplog
        oplog.append(new OpEntry(1, "i", "users", "{name:'zhangsan', age:25}"));
        oplog.append(new OpEntry(2, "u", "users", "{$set:{age:26}}"));
        oplog.append(new OpEntry(3, "i", "orders", "{item:'book', qty:2}"));
        oplog.append(new OpEntry(4, "d", "users", "{}"));

        System.out.println("Oplog 内容 (Primary 产生的写操作记录):");
        oplog.print();

        // Secondary 通过 tail oplog 同步
        System.out.println("Secondary 同步: 从 ts=2 开始追赶...");
        List<OpEntry> toSync = oplog.tail(2);
        for (OpEntry op : toSync) {
            System.out.println("  apply: " + op);
        }

        System.out.println("Oplog 是 capped collection (固定大小), 写满后覆盖最旧记录");
        System.out.println("Secondary 通过 oplog 增量追赶, 实现最终一致性\n");
    }

    /** Oplog 操作记录 */
    static class OpEntry {
        long timestamp;
        String op;      // i=insert, u=update, d=delete
        String ns;      // namespace: db.collection
        String detail;

        OpEntry(long timestamp, String op, String ns, String detail) {
            this.timestamp = timestamp;
            this.op = op;
            this.ns = ns;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format("ts=%d op=%s ns=%s detail=%s", timestamp, op, ns, detail);
        }
    }

    /** Oplog -- capped collection, 固定容量, 循环写入 */
    static class Oplog {
        private final OpEntry[] entries;
        private int writePos;
        private long currentTs;

        Oplog(int capacity) {
            this.entries = new OpEntry[capacity];
            this.writePos = 0;
            this.currentTs = 0;
        }

        void append(OpEntry entry) {
            entries[writePos % entries.length] = entry;
            writePos++;
            currentTs = entry.timestamp;
        }

        /** 获取 ts > since 的所有操作记录 (增量同步) */
        List<OpEntry> tail(long since) {
            List<OpEntry> result = new ArrayList<>();
            int start = Math.max(0, writePos - entries.length);
            for (int i = start; i < writePos; i++) {
                OpEntry e = entries[i % entries.length];
                if (e != null && e.timestamp > since) {
                    result.add(e);
                }
            }
            return result;
        }

        void print() {
            int start = Math.max(0, writePos - entries.length);
            for (int i = start; i < writePos; i++) {
                OpEntry e = entries[i % entries.length];
                if (e != null) {
                    System.out.println("  " + e);
                }
            }
        }
    }

    // ==================== 分片 ====================

    /**
     * MongoDB Sharding 分片模拟
     * Chunk 按 shard key hash 范围划分, Balancer 均衡各 shard 的 chunk 数
     */
    static void shardingDemo() {
        System.out.println("--- Sharding 分片 (Balanced Chunks) ---");

        ShardCluster cluster = new ShardCluster();
        cluster.addShard("shard1");
        cluster.addShard("shard2");
        cluster.addShard("shard3");

        // 模拟插入数据, 按 shard key (userId hash) 路由到不同 chunk
        String[] userIds = {"u001", "u002", "u003", "u004", "u005", "u006",
                            "u007", "u008", "u009", "u010", "u011", "u012"};
        for (String uid : userIds) {
            cluster.insert(uid, mapOf("_id", uid, "name", "user_" + uid, "score", uid.hashCode() % 100));
        }

        System.out.println("分片数据分布:");
        cluster.printDistribution();

        // Chunk 迁移: 将 chunk 从 shard1 迁移到 shard3
        System.out.println("\nBalancer 检测到 shard1 负载过高, 触发 chunk 迁移...");
        cluster.migrateChunk("shard1", "shard3", 1);

        System.out.println("迁移后分布:");
        cluster.printDistribution();

        System.out.println("\n分片核心概念:");
        System.out.println("  Shard Key: 决定数据如何分布 (hash/range)");
        System.out.println("  Chunk: 一段连续的 shard key 范围, 默认 64MB");
        System.out.println("  Balancer: 后台进程, 监测 chunk 分布, 自动迁移均衡");
        System.out.println("  Config Server: 存储 shard/chunk 元数据 (Replica Set 部署)");
        System.out.println("  Mongos: 路由节点, 接收客户端请求, 根据 shard key 路由到目标 shard\n");
    }

    /** Chunk: shard key 的一段连续范围 + 所属 shard */
    static class Chunk {
        int minHash;
        int maxHash;
        String shardId;
        List<Map<String, Object>> documents = new ArrayList<>();

        Chunk(int minHash, int maxHash, String shardId) {
            this.minHash = minHash;
            this.maxHash = maxHash;
            this.shardId = shardId;
        }

        boolean contains(int hash) {
            return hash >= minHash && hash < maxHash;
        }

        int docCount() { return documents.size(); }
    }

    /** Shard 分片节点 */
    static class Shard {
        String shardId;
        List<Chunk> chunks = new ArrayList<>();

        Shard(String shardId) { this.shardId = shardId; }

        int totalChunks() { return chunks.size(); }
        int totalDocs() { return chunks.stream().mapToInt(Chunk::docCount).sum(); }
    }

    /** 分片集群: 管理多个 shard 和 chunk 分布 */
    static class ShardCluster {
        private final Map<String, Shard> shards = new LinkedHashMap<>();
        private final List<Chunk> allChunks = new ArrayList<>();
        private static final int CHUNK_SIZE = 4; // 每个 chunk 最多 4 个文档
        private static final int TOTAL_HASH_RANGE = 1000;

        void addShard(String shardId) {
            Shard shard = new Shard(shardId);
            shards.put(shardId, shard);
            // 每个 shard 初始化时分配一个覆盖全 hash 范围的 chunk (后续会分裂)
            Chunk chunk = new Chunk(0, TOTAL_HASH_RANGE, shardId);
            shard.chunks.add(chunk);
            allChunks.add(chunk);
        }

        /** 插入文档, 按 _id hash 路由到对应 chunk */
        void insert(String shardKey, Map<String, Object> doc) {
            int hash = Math.abs(shardKey.hashCode()) % TOTAL_HASH_RANGE;
            // 查找覆盖该 hash 的 chunk
            for (Chunk chunk : allChunks) {
                if (chunk.contains(hash)) {
                    chunk.documents.add(doc);
                    // 如果 chunk 满了, 分裂成两个
                    if (chunk.documents.size() > CHUNK_SIZE) {
                        splitChunk(chunk);
                    }
                    return;
                }
            }
            // 兜底: 放到随机 shard 的第一个 chunk
            Shard firstShard = shards.values().iterator().next();
            firstShard.chunks.get(0).documents.add(doc);
        }

        /** Chunk 分裂: 当 chunk 文档数超过阈值, 分裂为两个新 chunk */
        void splitChunk(Chunk oldChunk) {
            int mid = (oldChunk.minHash + oldChunk.maxHash) / 2;
            Chunk newChunk = new Chunk(mid, oldChunk.maxHash, oldChunk.shardId);

            // 将属于新范围 [mid, maxHash) 的文档迁移到新 chunk
            Iterator<Map<String, Object>> iter = oldChunk.documents.iterator();
            while (iter.hasNext()) {
                Map<String, Object> doc = iter.next();
                int hash = Math.abs(doc.get("_id").hashCode()) % TOTAL_HASH_RANGE;
                if (hash >= mid) {
                    newChunk.documents.add(doc);
                    iter.remove();
                }
            }

            oldChunk.maxHash = mid;
            allChunks.add(newChunk);
            shards.get(oldChunk.shardId).chunks.add(newChunk);

            System.out.printf("  [分裂] chunk [%d,%d) 分裂为 [%d,%d) + [%d,%d)%n",
                    oldChunk.minHash, newChunk.maxHash,
                    oldChunk.minHash, oldChunk.maxHash,
                    newChunk.minHash, newChunk.maxHash);
        }

        /** Chunk 迁移: 将指定 chunk 从一个 shard 移到另一个 */
        void migrateChunk(String fromShard, String toShard, int chunkIndex) {
            Shard from = shards.get(fromShard);
            Shard to = shards.get(toShard);
            if (from == null || to == null) return;
            if (chunkIndex >= from.chunks.size()) return;

            Chunk chunk = from.chunks.get(chunkIndex);
            chunk.shardId = toShard;
            from.chunks.remove(chunkIndex);
            to.chunks.add(chunk);

            System.out.printf("  [迁移] chunk 从 %s -> %s (%d 个文档)%n",
                    fromShard, toShard, chunk.documents.size());
        }

        void printDistribution() {
            for (Shard shard : shards.values()) {
                System.out.printf("  %s: %d chunks, %d docs%n",
                        shard.shardId, shard.totalChunks(), shard.totalDocs());
                for (int i = 0; i < shard.chunks.size(); i++) {
                    Chunk c = shard.chunks.get(i);
                    System.out.printf("    chunk[%d]: hash [%d, %d), %d docs%n",
                            i, c.minHash, c.maxHash, c.docCount());
                }
            }
        }
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