package base.seatunnel;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 多数据源集成演示：模拟 MySQL CDC/JDBC 分片(source) + Kafka 实时流(source/sink) + Hive HDFS 文件写入 + 手动分区拆分 + 断点续传(savepoint)。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>MySQL CDC</b>：模拟 Binlog 变更数据捕获，记录 INSERT/UPDATE/DELETE 操作</li>
 *   <li><b>JDBC 分片读取</b>：按主键范围(range)、取模(int/hash)拆分成多个分片并行读取</li>
 *   <li><b>Kafka 实时流</b>：模拟 Topic 订阅(Source)和生产(Sink)，支持 offset 管理</li>
 *   <li><b>Hive/HDFS 写入</b>：模拟按分区字段写入文件，支持按天/小时分区目录</li>
 *   <li><b>断点续传</b>：Savepoint 保存每个分区的消费偏移量，重启后从上次位置继续</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()。
 *
 * @author study-tuling
 */
public class DataSourceIntegrationDemo {

    /** 模拟的系统时间基准 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("HH");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        System.out.println("=== Seatunnel 多数据源集成演示 ===\n");

        // 1. MySQL JDBC 分片读取
        demonstrateJdbcSharding();

        // 2. MySQL CDC 变更捕获
        demonstrateCdcCapture();

        // 3. Kafka 实时流 Source/Sink
        demonstrateKafkaStreaming();

        // 4. Hive HDFS 分区写入
        demonstrateHivePartitionWrite();

        // 5. 断点续传 Savepoint
        demonstrateSavepointResume();

        System.out.println("\n=== 演示完成 ===");
    }


    // ==================== 数据模型 ====================

    /** CDC 变更事件 */
    enum CdcOperation { INSERT, UPDATE, DELETE }

    record CdcEvent(long id, String table, CdcOperation operation, Map<String, Object> before,
                    Map<String, Object> after, long timestamp) {
        @Override
        public String toString() {
            return String.format("CDC{%s %s id=%d ts=%s}", operation, table, id,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                            .format(TS_FMT));
        }
    }

    /** Kafka 消息 */
    record KafkaMessage(String topic, int partition, long offset, String key, String value, long timestamp) {
        @Override
        public String toString() {
            return String.format("KafkaMsg{topic=%s, partition=%d, offset=%d, key=%s}",
                    topic, partition, offset, key);
        }
    }

    /** HDFS 文件路径 */
    record HdfsPath(String basePath, String partitionColumn, String partitionValue, String fileName) {
        String fullPath() {
            return basePath + "/" + partitionColumn + "=" + partitionValue + "/" + fileName;
        }
    }

    /** 断点快照 */
    record Savepoint(String taskId, Map<Integer, Long> partitionOffsets, long checkpointTime) {
        @Override
        public String toString() {
            return String.format("Savepoint{task=%s, offsets=%s, time=%s}", taskId, partitionOffsets,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(checkpointTime), ZoneId.systemDefault())
                            .format(TS_FMT));
        }
    }


    // ==================== 1. MySQL JDBC 分片读取 ====================

    /** 分片策略枚举 */
    enum ShardingStrategy { RANGE, MOD_INT, MOD_HASH }

    /**
     * JDBC 分片读取演示。
     *
     * <p>Seatunnel 中 JDBC Source 通过 split 参数将大表拆分成多个分片，
     * 每个分片由独立的 Reader 线程并行读取，提升全量同步速度。
     */
    static void demonstrateJdbcSharding() {
        System.out.println("--- 1. MySQL JDBC 分片读取 ---");

        int totalRows = 1000;
        int splitCount = 4;
        String table = "order_info";
        String splitColumn = "id";

        System.out.printf("  表 %s, 总行数 %d, 分片数 %d, 分片列 %s%n",
                table, totalRows, splitCount, splitColumn);

        // RANGE 分片：按主键范围均匀拆分
        System.out.println("\n  [RANGE 分片策略]");
        List<ShardRange> rangeShards = generateRangeShards(totalRows, splitCount);
        for (ShardRange shard : rangeShards) {
            System.out.printf("    SELECT * FROM %s WHERE %s >= %d AND %s < %d%n",
                    table, splitColumn, shard.start, splitColumn, shard.end());
        }

        // MOD_INT 分片：按取模拆分
        System.out.println("\n  [MOD_INT 分片策略]");
        for (int i = 0; i < splitCount; i++) {
            System.out.printf("    SELECT * FROM %s WHERE MOD(%s, %d) = %d%n",
                    table, splitColumn, splitCount, i);
        }

        // MOD_HASH 分片：按哈希取模拆分（适用于字符串主键）
        System.out.println("\n  [MOD_HASH 分片策略]");
        for (int i = 0; i < splitCount; i++) {
            System.out.printf("    SELECT * FROM %s WHERE MOD(CRC32(%s), %d) = %d%n",
                    table, splitColumn, splitCount, i);
        }

        // 模拟并行读取
        System.out.println("\n  [并行分片读取模拟]");
        for (ShardRange shard : rangeShards) {
            int rows = (int)(shard.end() - shard.start());
            System.out.printf("    Reader-%d: 读取 [%d, %d), %d 行%n",
                    shard.index(), shard.start(), shard.end(), rows);
        }
        System.out.println();
    }

    /** 分片范围 */
    record ShardRange(int index, long start, long end) {}

    /** 生成 RANGE 分片 */
    static List<ShardRange> generateRangeShards(int totalRows, int splitCount) {
        List<ShardRange> shards = new ArrayList<>();
        long chunkSize = (long) Math.ceil((double) totalRows / splitCount);
        for (int i = 0; i < splitCount; i++) {
            long start = i * chunkSize + 1;
            long end = Math.min((i + 1) * chunkSize + 1, totalRows + 1);
            shards.add(new ShardRange(i, start, end));
        }
        return shards;
    }


    // ==================== 2. MySQL CDC 变更捕获 ====================

    /**
     * CDC 变更捕获演示。
     *
     * <p>MySQL CDC Source 通过伪装成 MySQL Slave，订阅 Binlog 实时捕获变更。
     * 支持 INSERT、UPDATE、DELETE 三种操作，每种操作携带 before/after 镜像。
     */
    static void demonstrateCdcCapture() {
        System.out.println("--- 2. MySQL CDC 变更捕获 ---");

        List<CdcEvent> mockBinlog = generateMockBinlog();
        System.out.printf("  模拟 Binlog 事件数: %d%n", mockBinlog.size());

        for (CdcEvent event : mockBinlog) {
            System.out.printf("    %s%n", event);
            if (event.operation() == CdcOperation.INSERT) {
                System.out.printf("      → 新增数据: %s%n", event.after());
            } else if (event.operation() == CdcOperation.UPDATE) {
                System.out.printf("      → 修改前: %s%n", event.before());
                System.out.printf("      → 修改后: %s%n", event.after());
            } else {
                System.out.printf("      → 删除数据: %s%n", event.before());
            }
        }
        System.out.println();
    }

    /** 生成模拟 Binlog 事件 */
    static List<CdcEvent> generateMockBinlog() {
        List<CdcEvent> events = new ArrayList<>();
        long baseTs = System.currentTimeMillis() - 60_000;

        // INSERT
        Map<String, Object> after1 = new LinkedHashMap<>();
        after1.put("id", 101); after1.put("name", "张三"); after1.put("age", 28);
        events.add(new CdcEvent(101, "user", CdcOperation.INSERT, null, after1, baseTs));

        // UPDATE
        Map<String, Object> before2 = new LinkedHashMap<>();
        before2.put("id", 50); before2.put("name", "李四"); before2.put("age", 30);
        Map<String, Object> after2 = new LinkedHashMap<>();
        after2.put("id", 50); after2.put("name", "李四"); after2.put("age", 31);
        events.add(new CdcEvent(50, "user", CdcOperation.UPDATE, before2, after2, baseTs + 1000));

        // DELETE
        Map<String, Object> before3 = new LinkedHashMap<>();
        before3.put("id", 33); before3.put("name", "王五"); before3.put("age", 22);
        events.add(new CdcEvent(33, "user", CdcOperation.DELETE, before3, null, baseTs + 2000));

        return events;
    }


    // ==================== 3. Kafka 实时流 ====================

    /**
     * Kafka 实时流演示。
     *
     * <p>Seatunnel 中 Kafka Source 订阅 Topic 拉取消息，Kafka Sink 将处理后的数据写入 Topic。
     * 每个 Partition 独立维护 offset，支持从指定 offset 消费。
     */
    static void demonstrateKafkaStreaming() {
        System.out.println("--- 3. Kafka 实时流 Source/Sink ---");

        // 模拟 Kafka Broker
        MockKafkaBroker broker = new MockKafkaBroker();

        // 模拟 Source 端：从 Kafka Topic 消费
        String sourceTopic = "ods_user_behavior";
        broker.produce(sourceTopic, 0, "user_login", "{\"uid\":1001,\"action\":\"login\"}");
        broker.produce(sourceTopic, 0, "page_view", "{\"uid\":1001,\"page\":\"/home\"}");
        broker.produce(sourceTopic, 1, "order_create", "{\"uid\":1002,\"amount\":99.9}");
        broker.produce(sourceTopic, 1, "order_pay", "{\"uid\":1002,\"status\":\"paid\"}");

        System.out.printf("  [Kafka Source] 消费 Topic: %s%n", sourceTopic);
        List<KafkaMessage> consumed = broker.consume(sourceTopic, Map.of(0, 0L, 1, 0L));
        for (KafkaMessage msg : consumed) {
            System.out.printf("    partition=%d offset=%d key=%s value=%s%n",
                    msg.partition(), msg.offset(), msg.key(), msg.value());
        }

        // 模拟 Sink 端：写入 Kafka Topic
        String sinkTopic = "dwd_user_events";
        System.out.printf("%n  [Kafka Sink] 写入 Topic: %s%n", sinkTopic);
        System.out.println("    写入 processed 消息 4 条...");
        broker.produce(sinkTopic, 0, "user_login_processed", "{\"status\":\"ok\"}");
        broker.produce(sinkTopic, 0, "page_view_processed", "{\"status\":\"ok\"}");
        broker.produce(sinkTopic, 1, "order_create_processed", "{\"status\":\"ok\"}");
        broker.produce(sinkTopic, 1, "order_pay_processed", "{\"status\":\"ok\"}");

        // 消费验证
        List<KafkaMessage> sinkResult = broker.consume(sinkTopic, Map.of(0, 4L, 1, 2L));
        System.out.printf("    Sink Topic 当前总消息数: %d%n", sinkResult.size());
        System.out.println();
    }

    /** 模拟 Kafka Broker */
    static class MockKafkaBroker {
        /** topic → partition → 消息队列 */
        private final Map<String, Map<Integer, List<KafkaMessage>>> storage = new ConcurrentHashMap<>();
        /** topic → partition → offset 计数器 */
        private final Map<String, Map<Integer, AtomicLong>> offsetCounters = new ConcurrentHashMap<>();

        void produce(String topic, int partition, String key, String value) {
            storage.computeIfAbsent(topic, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(partition, k -> new ArrayList<>());
            offsetCounters.computeIfAbsent(topic, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(partition, k -> new AtomicLong(0));

            long offset = offsetCounters.get(topic).get(partition).getAndIncrement();
            KafkaMessage msg = new KafkaMessage(topic, partition, offset, key, value, System.currentTimeMillis());
            storage.get(topic).get(partition).add(msg);
        }

        /**
         * 从指定 offset 开始消费。
         *
         * @param partitionOffsets 每个分区的起始 offset
         */
        List<KafkaMessage> consume(String topic, Map<Integer, Long> partitionOffsets) {
            List<KafkaMessage> result = new ArrayList<>();
            Map<Integer, List<KafkaMessage>> topicData = storage.get(topic);
            if (topicData == null) return result;

            for (Map.Entry<Integer, Long> entry : partitionOffsets.entrySet()) {
                int partition = entry.getKey();
                long startOffset = entry.getValue();
                List<KafkaMessage> partitionData = topicData.get(partition);
                if (partitionData == null) continue;

                for (KafkaMessage msg : partitionData) {
                    if (msg.offset() >= startOffset) {
                        result.add(msg);
                    }
                }
            }
            return result;
        }

        /** 获取当前各分区的最新 offset */
        Map<Integer, Long> getCurrentOffsets(String topic) {
            Map<Integer, Long> offsets = new LinkedHashMap<>();
            Map<Integer, AtomicLong> counters = offsetCounters.get(topic);
            if (counters != null) {
                for (Map.Entry<Integer, AtomicLong> entry : counters.entrySet()) {
                    offsets.put(entry.getKey(), entry.getValue().get());
                }
            }
            return offsets;
        }
    }


    // ==================== 4. Hive HDFS 分区写入 ====================

    /**
     * Hive HDFS 分区写入演示。
     *
     * <p>Seatunnel 的 Hive Sink 将数据按分区字段写入 HDFS 目录，
     * 路径格式：{@code /warehouse/db/table/dt=2024-01-01/hour=12/file.parquet}
     */
    static void demonstrateHivePartitionWrite() {
        System.out.println("--- 4. Hive HDFS 分区写入 ---");

        String basePath = "/warehouse/ods/order_detail";
        String tableName = "order_detail";

        // 模拟多天多小时的订单数据
        List<Map<String, Object>> orders = generateMockOrders();

        // 按分区字段分组
        Map<String, List<Map<String, Object>>> partitioned = orders.stream()
                .collect(Collectors.groupingBy(
                        order -> order.get("dt") + "|" + order.get("hour"),
                        LinkedHashMap::new, Collectors.toList()));

        System.out.printf("  表: %s, 总记录: %d, 分区数: %d%n", tableName, orders.size(), partitioned.size());

        // 写入各分区
        for (Map.Entry<String, List<Map<String, Object>>> entry : partitioned.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String dt = parts[0];
            String hour = parts[1];
            HdfsPath path = new HdfsPath(basePath, "dt", dt, "part-0000.parquet");
            HdfsPath hourPath = new HdfsPath(path.fullPath(), "hour", hour, "part-0000.parquet");

            System.out.printf("  分区 dt=%s, hour=%s: %d 行 → %s%n",
                    dt, hour, entry.getValue().size(), hourPath.fullPath());
        }

        // 分区裁剪演示（只读某个分区）
        String queryDt = "2024-03-15";
        String queryHour = "10";
        System.out.printf("%n  [分区裁剪] 只查询 dt=%s, hour=%s%n", queryDt, queryHour);
        System.out.printf("    → 只扫描目录: %s/dt=%s/hour=%s/%n", basePath, queryDt, queryHour);
        System.out.println();
    }

    /** 生成模拟订单数据 */
    static List<Map<String, Object>> generateMockOrders() {
        List<Map<String, Object>> orders = new ArrayList<>();
        String[] dates = {"2024-03-14", "2024-03-15", "2024-03-16"};
        String[] hours = {"08", "10", "12", "14"};
        int orderId = 0;

        for (String dt : dates) {
            for (String hour : hours) {
                for (int j = 0; j < 2; j++) { // 每个分区 2 条
                    Map<String, Object> order = new LinkedHashMap<>();
                    order.put("order_id", ++orderId);
                    order.put("user_id", 1000 + orderId);
                    order.put("amount", 50.0 + (orderId % 10) * 10);
                    order.put("status", orderId % 3 == 0 ? "paid" : "pending");
                    order.put("dt", dt);
                    order.put("hour", hour);
                    orders.add(order);
                }
            }
        }
        return orders;
    }


    // ==================== 5. 断点续传 Savepoint ====================

    /**
     * 断点续传 Savepoint 演示。
     *
     * <p>Seatunnel 通过 Checkpoint/Savepoint 机制实现故障恢复：
     * 每个 Reader 定期上报自己的消费进度（分区 + offset），
     * 任务失败重启后从上次 Savepoint 恢复，避免重复消费或数据丢失。
     */
    static void demonstrateSavepointResume() {
        System.out.println("--- 5. 断点续传 Savepoint ---");

        // 模拟首次执行
        System.out.println("  [首次执行] 开始同步任务...");
        MockKafkaBroker broker = new MockKafkaBroker();
        String topic = "ods_business_log";

        // 生产 100 条消息到 3 个分区
        for (int i = 0; i < 100; i++) {
            broker.produce(topic, i % 3, "key_" + i, "value_" + i);
        }

        // 模拟消费到一半（每个分区消费了 10 条）
        Map<Integer, Long> partialOffsets = new LinkedHashMap<>();
        partialOffsets.put(0, 10L);
        partialOffsets.put(1, 10L);
        partialOffsets.put(2, 10L);

        Savepoint savepoint = new Savepoint("task-ods-business", partialOffsets, System.currentTimeMillis());
        System.out.printf("  已消费 30/100 条，生成 Savepoint: %s%n", savepoint);

        // 模拟故障重启后恢复
        System.out.println("\n  [故障重启] 从 Savepoint 恢复...");
        Map<Integer, Long> resumeOffsets = savepoint.partitionOffsets();
        System.out.printf("  恢复分区偏移量: %s%n", resumeOffsets);

        List<KafkaMessage> remaining = broker.consume(topic, resumeOffsets);
        System.out.printf("  待消费剩余消息: %d 条 (总计 100 - 已消费 30 = 70)%n", remaining.size());

        // 验证：剩余消息应从 offset=10 开始
        int[] expectedStart = {0, 0, 0};
        for (KafkaMessage msg : remaining) {
            int p = msg.partition();
            if (expectedStart[p] == 0) {
                System.out.printf("    partition=%d 起始 offset=%d%n", p, msg.offset());
                expectedStart[p] = 1;
            }
        }

        // 完成消费后生成最终 Savepoint
        Map<Integer, Long> finalOffsets = broker.getCurrentOffsets(topic);
        Savepoint finalSavepoint = new Savepoint("task-ods-business", finalOffsets, System.currentTimeMillis());
        System.out.printf("%n  消费完成，最终 Savepoint: %s%n", finalSavepoint);
        System.out.println();
    }
}