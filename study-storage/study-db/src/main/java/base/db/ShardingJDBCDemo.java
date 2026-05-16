package base.db;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ShardingSphere 分库分表核心原理手写: 哈希取模路由 + 基因法 + 雪花算法 + 跨分片聚合
 * 哈希取模: key % totalShards 确定 db.table, 简单但扩容迁移量大
 * 基因法: 将 user_id 低 N 位嵌入 order_id, 保证同一用户的订单在同一分片, 避免跨分片 JOIN
 * 雪花算法: 41bit 时间戳 + 10bit 机器位 + 12bit 序列, 趋势递增, 本地生成不依赖 DB
 * 跨分片聚合: 各分片独立 COUNT/SUM -> 汇总层相加, 但 ORDER BY + LIMIT 需要全量排序
 */
public class ShardingJDBCDemo {

    /**
     * 哈希取模路由器 -- 4 库 x 4 表 = 16 分片,
     * 按 shardKey 取模确定 db 和 table 序号
     */
    static class ShardingRouter {
        static final int DB_COUNT = 4;
        static final int TABLE_COUNT = 4;

        static String route(long shardKey) {
            int totalShards = DB_COUNT * TABLE_COUNT;
            int shard = (int) (Math.abs(shardKey) % totalShards);
            int dbIndex = shard / TABLE_COUNT;
            int tableIndex = shard % TABLE_COUNT;
            return String.format("db_%d.table_%d", dbIndex, tableIndex);
        }

        static Map<String, List<String>> routeAndExplain(long userId, long orderId,
                                                          boolean useUserId, boolean useGeneMethod) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            result.put("分片键 userId", Collections.singletonList(route(userId)));

            if (useGeneMethod) {
                long geneOrderId = embedGene(orderId, userId);
                result.put("基因法 orderId(嵌入userId低4位)", Collections.singletonList(route(geneOrderId)));
            } else {
                result.put("普通 orderId 分片", Collections.singletonList(route(orderId)));
            }
            return result;
        }

        static long embedGene(long orderId, long userId) {
            long gene = userId & 0xF;
            return (orderId << 4) | gene;
        }

        static long extractGene(long geneOrderId) {
            return geneOrderId & 0xF;
        }
    }

    /**
     * 跨分片聚合模拟 -- 每个分片独立计算 COUNT/SUM,
     * 汇总层对结果做二次聚合, 类似 ShardingSphere 的归并引擎
     */
    static class CrossShardAggregator {
        static class ShardData {
            int shardId;
            int count;
            double sum;

            ShardData(int shardId, int count, double sum) {
                this.shardId = shardId;
                this.count = count;
                this.sum = sum;
            }

            @Override
            public String toString() {
                return String.format("shard_%d{count=%d, sum=%.2f}", shardId, count, sum);
            }
        }

        static void simulate() {
            System.out.println("\n--- 跨分片聚合查询模拟 ---");
            int totalShards = ShardingRouter.DB_COUNT * ShardingRouter.TABLE_COUNT;
            List<ShardData> shardResults = new ArrayList<>();

            Random rand = new Random(42);
            long totalCount = 0;
            double totalSum = 0;

            for (int i = 0; i < totalShards; i++) {
                int count = rand.nextInt(100) + 10;
                double sum = rand.nextDouble() * 1000;
                shardResults.add(new ShardData(i, count, sum));
                totalCount += count;
                totalSum += sum;
            }

            System.out.println("各分片结果:");
            for (ShardData sd : shardResults) {
                System.out.println("  " + sd);
            }
            System.out.printf("聚合结果: total_count=%d, total_sum=%.2f, avg=%.2f%n",
                    totalCount, totalSum, totalSum / totalCount);
            System.out.println("流程: 各分片计算 COUNT/SUM -> 汇总层相加 -> 返回最终结果");
        }
    }

    /**
     * 雪花算法手写版 -- 64bit ID = 1bit(保留) + 41bit(时间戳) + 10bit(机器) + 12bit(序列)
     * 时钟回拨检测: lastTimestamp > timestamp 时抛异常
     * 同一毫秒内序列用完则自旋等待下一毫秒
     */
    static class SnowflakeID {
        static final long EPOCH = 1609459200000L;
        static final long WORKER_ID_BITS = 5L;
        static final long DATACENTER_ID_BITS = 5L;
        static final long SEQUENCE_BITS = 12L;
        static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
        static final long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1;
        static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

        static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
        static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
        static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

        long workerId;
        long datacenterId;
        long sequence = 0L;
        long lastTimestamp = -1L;

        synchronized long nextId() {
            long timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new RuntimeException("Clock moved backwards");
            }
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    while (timestamp <= lastTimestamp) {
                        timestamp = System.currentTimeMillis();
                    }
                }
            } else {
                sequence = 0L;
            }
            lastTimestamp = timestamp;
            return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                    | (datacenterId << DATACENTER_ID_SHIFT)
                    | (workerId << WORKER_ID_SHIFT)
                    | sequence;
        }

        static String decode(long id) {
            long timestamp = (id >> TIMESTAMP_SHIFT) + EPOCH;
            long datacenter = (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
            long worker = (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
            long seq = id & MAX_SEQUENCE;
            return String.format("timestamp=%tF %<tT, dc=%d, worker=%d, seq=%d",
                    new Date(timestamp), datacenter, worker, seq);
        }
    }

    static void demoHashSharding() {
        System.out.println("=== 1. 哈希取模分片路由 (4库 x 4表 = 16分片) ===");
        long[] userIds = {1001L, 1002L, 1003L, 1004L, 1005L, 1017L, 1033L};
        for (long uid : userIds) {
            System.out.printf("user_id=%d -> %s%n", uid, ShardingRouter.route(uid));
        }
    }

    static void demoShardKeyStrategy() {
        System.out.println("\n=== 2. 分片键选择策略对比 ===");
        long userId = 1001L;
        long orderId = 88888L;

        Map<String, List<String>> results = ShardingRouter.routeAndExplain(userId, orderId, true, false);
        for (Map.Entry<String, List<String>> e : results.entrySet()) {
            System.out.printf("%s: %s%n", e.getKey(), e.getValue());
        }
        System.out.println("结论: 以 user_id 分片时，查用户所有订单只需查1个分片");
        System.out.println("      以 order_id 分片时，查用户所有订单需查全部分片(散射)");
    }

    static void demoCrossShardAggregation() {
        System.out.println("\n=== 3. 跨分片聚合查询模拟 ===");
        CrossShardAggregator.simulate();
    }

    static void demoSnowflake() {
        System.out.println("\n=== 4. 雪花算法简化版 ===");
        SnowflakeID snowflake = new SnowflakeID();
        snowflake.workerId = 1;
        snowflake.datacenterId = 1;

        for (int i = 0; i < 5; i++) {
            long id = snowflake.nextId();
            System.out.printf("ID: %d (%s)%n", id, SnowflakeID.decode(id));
        }
        System.out.println("特点: 全局唯一、趋势递增、不依赖DB、毫秒级精度");
    }

    static void demoGeneMethod() {
        System.out.println("\n=== 5. 基因法 (user_id 低 N 位嵌入 order_id) ===");
        long userId = 1001L;
        long orderId = 88888L;
        long geneOrderId = ShardingRouter.embedGene(orderId, userId);

        System.out.printf("user_id=%d (低4位基因=%d)%n", userId, userId & 0xF);
        System.out.printf("原始 order_id=%d%n", orderId);
        System.out.printf("嵌入基因后 order_id=%d (低4位基因=%d)%n",
                geneOrderId, ShardingRouter.extractGene(geneOrderId));
        System.out.println("路由结果: " + ShardingRouter.route(geneOrderId));
        System.out.println("优势: 根据 order_id 路由时，相同 user_id 的订单落在同一分片");
        System.out.println("      即 user 和其订单在同一个分片，避免跨分片 JOIN");
    }

    public static void main(String[] args) {
        demoHashSharding();
        demoShardKeyStrategy();
        demoCrossShardAggregation();
        demoSnowflake();
        demoGeneMethod();
    }
}