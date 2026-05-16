package base.flink;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flink Checkpoint 机制：Barrier(屏障)对齐流程(Source→下游所有Operator→Sink)
 * + Exactly-Once 语义(Chandy-Lamport 分布式快照简化版)
 * + Savepoint vs Checkpoint 区别 + StateBackend(内存/HDFS/RocksDB)。
 *
 * <p>核心考点：
 * <ul>
 *   <li><b>Checkpoint Barrier</b>：JobManager 定期向 Source 注入 Barrier，随数据流向下游传递</li>
 *   <li><b>Barrier 对齐</b>：多输入 Operator 等待所有输入 Barrier 到达后再做本地快照</li>
 *   <li><b>Chandy-Lamport 算法</b>：异步分布式快照，无暂停，Barrier 作为快照边界标记</li>
 *   <li><b>Exactly-Once</b>：对齐式 Checkpoint + 端到端 Exactly-Once(TwoPhaseCommitSink)</li>
 *   <li><b>Savepoint vs Checkpoint</b>：Savepoint 手动触发、可迁移；Checkpoint 自动、可恢复</li>
 * </ul>
 *
 * @see StateBackendDemo State 状态后端
 */
public class CheckpointDemo {

    /* ======================== StreamRecord 数据流记录 ======================== */

    /** 数据流记录 */
    record StreamRecord(String key, int value, long timestamp) {
    }

    /** Barrier 标记 */
    record Barrier(long checkpointId, long timestamp) {
        @Override
        public String toString() {
            return String.format("Barrier(ckId=%d, t=%d)", checkpointId, timestamp);
        }
    }

    /** 消息体：可以是 StreamRecord 或 Barrier */
    sealed interface Message permits StreamMessage, BarrierMessage {
    }

    record StreamMessage(StreamRecord record) implements Message {
    }

    record BarrierMessage(Barrier barrier) implements Message {
    }

    /* ======================== Operator 状态快照 ======================== */

    /** 算子本地状态快照 */
    record OperatorSnapshot(String operatorName, String state, long checkpointId) {
    }

    /** 完整的 Checkpoint 快照 */
    record CheckpointSnapshot(long checkpointId, List<OperatorSnapshot> snapshots, long timestamp) {
    }

    /* ======================== Operator 算子简化模型 ======================== */

    /**
     * 算子基类：处理 Barrier 对齐和状态快照。
     * <p>Barrier 对齐流程：
     * <ol>
     *   <li>从某个上游 Channel 收到 Barrier → 暂停该 Channel 的数据消费</li>
     *   <li>继续消费其他 Channel 的数据, 直到该 Channel 的 Barrier 也到达</li>
     *   <li>所有 Channel Barrier 到达 → 做本地状态快照 → 向下游广播 Barrier</li>
     * </ol>
     */
    abstract static class Operator {
        final String name;
        /** 状态数据 (Key → Value) */
        final Map<String, Integer> state = new LinkedHashMap<>();
        /** 收到的 Barrier 通道记录 */
        final Set<Integer> receivedBarrierChannels = new HashSet<>();
        /** 屏障对齐中 (对齐时仍处理数据但暂不放行) */
        boolean aligning;

        Operator(String name) { this.name = name; }

        /** 处理消息/Barrier */
        abstract List<Message> process(Message msg);

        /** 做本地状态快照 */
        OperatorSnapshot createSnapshot(long checkpointId) {
            return new OperatorSnapshot(name,
                    state.isEmpty() ? "(empty)" : state.toString(), checkpointId);
        }

        @Override
        public String toString() { return "Operator[" + name + "]"; }
    }

    /* ======================== Source Operator ======================== */

    static class SourceOperator extends Operator {
        final List<StreamRecord> records;
        int index = 0;

        SourceOperator(String name, List<StreamRecord> records) {
            super(name);
            this.records = records;
        }

        @Override
        List<Message> process(Message msg) {
            List<Message> output = new ArrayList<>();

            if (msg instanceof BarrierMessage) {
                Barrier barrier = ((BarrierMessage) msg).barrier();
                // Source 收到 Barrier → 立即做快照 → 向下游传递 Barrier
                OperatorSnapshot snapshot = createSnapshot(barrier.checkpointId());
                System.out.printf("  [Source %s] Barrier(%d) → 快照: %s → 向下游 Barrier%n",
                        name, barrier.checkpointId(), snapshot.state());
                output.add(new BarrierMessage(barrier));
                return output;
            }

            // 输出下一条数据
            if (index < records.size()) {
                StreamRecord record = records.get(index++);
                output.add(new StreamMessage(record));
            }
            return output;
        }
    }

    /* ======================== Map Operator ======================== */

    static class MapOperator extends Operator {
        final List<Message> upstream1 = new ArrayList<>(); // Channel-0
        final List<Message> upstream2 = new ArrayList<>(); // Channel-1 (模拟多输入)

        int channelIndex = 0; // 0=Channel-0, 1=Channel-1, 交替

        MapOperator(String name) {
            super(name);
        }

        void receiveFromUpstream(Message msg, int channel) {
            if (channel == 0) upstream1.add(msg);
            else upstream2.add(msg);
        }

        @Override
        List<Message> process(Message msg) {
            List<Message> output = new ArrayList<>();

            if (msg instanceof BarrierMessage) {
                Barrier barrier = ((BarrierMessage) msg).barrier();
                int currentChannel = (receivedBarrierChannels.isEmpty() ? 0 : 1);
                receivedBarrierChannels.add(currentChannel);

                if (receivedBarrierChannels.size() < 2) {
                    // 只收到一个 Channel 的 Barrier: 开始对齐, 阻塞该 Channel
                    if (!aligning) aligning = true;
                    System.out.printf("  [Map %s] Barrier(%d) 到达 Channel-%d, 等待另一 Channel Barrier...%n",
                            name, barrier.checkpointId(), currentChannel);
                }

                if (receivedBarrierChannels.size() >= 2) {
                    // 所有 Channel Barrier 到达: 做快照 → 向下游广播 Barrier → 恢复消费
                    aligning = false;
                    receivedBarrierChannels.clear();
                    OperatorSnapshot snapshot = createSnapshot(barrier.checkpointId());
                    System.out.printf("  [Map %s] 所有 Channel Barrier 达成 → 快照: %s → 向下游 Barrier%n",
                            name, snapshot.state());
                    output.add(new BarrierMessage(barrier));
                    // 恢复处理之前暂存的数据
                    output.addAll(flushPendingRecords());
                }
                return output;
            }

            if (msg instanceof StreamMessage) {
                if (aligning) {
                    // 对齐期间：暂存数据到待处理队列
                    System.out.printf("  [Map %s] 对齐中, 暂存: %s%n",
                            name, ((StreamMessage) msg).record());
                    upstream1.add(msg); // 暂存
                } else {
                    // 正常处理
                    StreamRecord record = ((StreamMessage) msg).record();
                    state.merge(record.key(), record.value(), Integer::sum);
                    output.add(new StreamMessage(
                            new StreamRecord(record.key(), record.value() * 2, record.timestamp())));
                }
            }
            return output;
        }

        List<Message> flushPendingRecords() {
            List<Message> flushed = new ArrayList<>();
            for (Message msg : new ArrayList<>(upstream1)) {
                if (msg instanceof StreamMessage) {
                    StreamRecord r = ((StreamMessage) msg).record();
                    state.merge(r.key(), r.value(), Integer::sum);
                    flushed.add(new StreamMessage(
                            new StreamRecord(r.key(), r.value() * 2, r.timestamp())));
                }
            }
            upstream1.clear();
            System.out.printf("  [Map %s] 对齐完成, 恢复处理 %d 条暂存数据%n",
                    name, flushed.size());
            return flushed;
        }
    }

    /* ======================== Sink Operator ======================== */

    static class SinkOperator extends Operator {
        final List<StreamRecord> collected = new ArrayList<>();

        SinkOperator(String name) {
            super(name);
        }

        @Override
        List<Message> process(Message msg) {
            if (msg instanceof BarrierMessage) {
                Barrier barrier = ((BarrierMessage) msg).barrier();
                OperatorSnapshot snapshot = createSnapshot(barrier.checkpointId());
                System.out.printf("  [Sink %s] Barrier(%d) → 快照: %s → Checkpoint 完成!%n",
                        name, barrier.checkpointId(), snapshot.state());
                return List.of(); // Sink 不向下游传 Barrier
            }
            if (msg instanceof StreamMessage) {
                StreamRecord record = ((StreamMessage) msg).record();
                collected.add(record);
                System.out.printf("  [Sink %s] 写入结果: key=%s val=%d%n",
                        name, record.key(), record.value());
            }
            return List.of();
        }
    }

    /* ======================== JobManager 协调 Checkpoint ======================== */

    /**
     * Checkpoint 协调器（模拟 JobManager 触发 Checkpoint）。
     * <p>流程：
     * <ol>
     *   <li>JM 向所有 Source 注入 Barrier（checkpointId 递增）</li>
     *   <li>Barrier 随数据流向下游传递</li>
     *   <li>每个算子收到所有上游 Barrier → 做本地快照 → 向下游传递</li>
     *   <li>Sink 完成快照 → JM 收到所有算子快照 → Checkpoint 完成</li>
     * </ol>
     */
    static class CheckpointCoordinator {
        final SourceOperator source;
        final MapOperator mapOp;
        final SinkOperator sink;
        final AtomicInteger checkpointIdGenerator = new AtomicInteger(1);
        final List<CheckpointSnapshot> completedCheckpoints = new ArrayList<>();

        CheckpointCoordinator(SourceOperator source, MapOperator mapOp, SinkOperator sink) {
            this.source = source;
            this.mapOp = mapOp;
            this.sink = sink;
        }

        /** 触发一次 Checkpoint */
        List<Message> triggerCheckpoint() {
            long ckId = checkpointIdGenerator.getAndIncrement();
            System.out.printf("%n>>> Checkpoint #%d 触发 (JM 向 Source 注入 Barrier) <<<%n", ckId);

            // 1. Source 收到 Barrier
            List<Message> sourceOutput = source.process(new BarrierMessage(
                    new Barrier(ckId, System.currentTimeMillis())));
            // 2. Map 处理 (包括 Barrier)
            List<Message> mapOutput = new ArrayList<>();
            for (Message m : sourceOutput) {
                mapOutput.addAll(mapOp.process(m));
            }
            // 3. Sink 处理
            List<Message> sinkData = new ArrayList<>();
            for (Message m : mapOutput) {
                if (m instanceof StreamMessage) {
                    sinkData.add(m);
                }
                List<Message> result = sink.process(m);
                sinkData.addAll(result);
            }

            return sinkData;
        }
    }

    /* ======================== 分布式快照 (Chandy-Lamport 简化) ======================== */

    /**
     * Chandy-Lamport 分布式快照算法（简化版）。
     * <p>核心：
     * <ol>
     *   <li>任意进程发起 Marker 消息（Flink 映射为 Barrier）</li>
     *   <li>进程收到第一个 Marker → 记录本地状态 + 开始记录输入 Channel 数据</li>
     *   <li>Marker 随输出 Channel 向下游传递</li>
     *   <li>所有进程完成本地快照 → 全局快照完成</li>
     * </ol>
     */
    static void rdChandyLamportDemo() {
        System.out.println("========== Chandy-Lamport 分布式快照算法 ==========\n");

        System.out.println("""
                步骤:
                1. JobManager 向 Source 注入 Barrier(Marker)
                2. Source 收到 Barrier → 记录本地状态(offset) → 向 Map 发送 Barrier
                3. Map 收到上游所有 Barrier → 记录本地状态 → 向 Sink 发送 Barrier
                4. Sink 收到 Barrier → 记录本地状态 → 通知 JM Checkpoint 完成
                
                关键: Barrier 在数据流中按序传递, 不打断数据处理
                """);
    }

    /* ======================== Savepoint vs Checkpoint ======================== */

    static void rdSavepointVsCheckpoint() {
        System.out.println("========== Savepoint vs Checkpoint 对比 ==========\n");

        String fmt = "| %-18s | %-24s | %-24s |%n";
        System.out.printf(fmt, "维度", "Checkpoint", "Savepoint");
        System.out.println("|--------------------|--------------------------|--------------------------|");
        System.out.printf(fmt, "触发方式", "自动(JM定时)", "手动(CLI/REST)");
        System.out.printf(fmt, "目的", "故障恢复", "升级/迁移/回滚");
        System.out.printf(fmt, "生命周期", "完成后自动清理(可配)", "永久保留(手动删除)");
        System.out.printf(fmt, "格式", "状态后端原生格式", "标准化格式(可跨版本)");
        System.out.printf(fmt, "并行度要求", "恢复时必须匹配原并行度", "可修改并行度");
        System.out.printf(fmt, "UID", "可选", "必须设置UID(状态映射)");
        System.out.printf(fmt, "增量", "支持增量CK(incremental)", "全量快照");
        System.out.printf(fmt, "触发条件", "运行时(Source持续输出)", "需停止/暂停作业");
        System.out.println();
        System.out.println("  最佳实践:");
        System.out.println("    生产: Checkpoint(RocksDB, increment, 5min) + 紧急Savepoint(升级前)");
        System.out.println("    所有 Stateful Operator 必须设置 uid() 以保证 Savepoint 恢复兼容性\n");
    }

    /* ======================== Exactly-Once 语义 ======================== */

    static void rdExactlyOnceSemantics() {
        System.out.println("========== Exactly-Once 语义保证 ==========\n");

        System.out.println("Flink 端到端 Exactly-Once 两条路径:\n");

        System.out.println("路径 1: 仅 Flink 内部 Exactly-Once");
        System.out.println("  - Barrier 对齐保证分布式一致性快照");
        System.out.println("  - 失败恢复: 从最近 Checkpoint 恢复所有算子状态");
        System.out.println("  - Source 重置 Offset (Kafka → 从上次 Committed Offset 开始)");
        System.out.println();

        System.out.println("路径 2: 端到端 Exactly-Once (两阶段提交)");
        System.out.println("  - Source: 支持重置 Offset (Kafka/Kinesis)");
        System.out.println("  - Sink: TwoPhaseCommitSinkFunction");
        System.out.println("     Phase 1 (pre-commit): Flink checkpoint 完成 → Sink 预提交");
        System.out.println("     Phase 2 (commit): JM 确认 Checkpoint 完成 → Sink 正式提交");
        System.out.println("  - 支持: Kafka 0.11+ (Transactional), File/Cassandra Sink");
        System.out.println();
        System.out.println("关键条件:");
        System.out.println("  1. Checkpoint 开启 (env.enableCheckpointing(5000))");
        System.out.println("  2. Exactly-Once 模式 (env.getCheckpointConfig().setCheckpointingMode())");
        System.out.println("  3. Source 支持重置 (KafkaConsumer.setStartFromGroupOffsets())");
        System.out.println("  4. Sink 支持 2PC (FlinkKafkaProducer.Semantic.EXACTLY_ONCE)\n");
    }

    /* ======================== 完整 Checkpoint 流程 ======================== */

    static void rdCheckpointFlowDemo() {
        System.out.println("========== Checkpoint Barrier 对齐流程 ==========\n");

        List<StreamRecord> sourceRecords = List.of(
                new StreamRecord("A", 10, 1000L),
                new StreamRecord("B", 20, 2000L),
                new StreamRecord("A", 5, 3000L),
                new StreamRecord("B", 15, 4000L),
                new StreamRecord("A", 30, 5000L)
        );

        SourceOperator source = new SourceOperator("Source-Kafka", sourceRecords);
        MapOperator mapOp = new MapOperator("Map-Multiplex");
        SinkOperator sink = new SinkOperator("Sink-HDFS");

        CheckpointCoordinator coordinator = new CheckpointCoordinator(source, mapOp, sink);

        System.out.println("正常处理数据 + Checkpoint 注入:\n");

        // 模拟持续处理数据
        int iterations = sourceRecords.size() + 1;
        for (int step = 0; step < iterations; step++) {
            System.out.printf("\n--- Step %d ---%n", step + 1);

            // 正常 data 处理
            List<Message> output = source.process(new StreamMessage(null)); // null→获取下一条
            for (Message m : output) {
                mapOp.process(m).forEach(sink::process);
            }

            // 第3步触发 Checkpoint
            if (step == 2) {
                List<Message> ckResult = coordinator.triggerCheckpoint();
            }
        }

        System.out.printf("%nSink 写入总数: %d%n", sink.collected.size());
        System.out.println("Sink 结果: " + sink.collected);
    }

    /* ======================== 状态后端演示 ======================== */

    static void rdStateBackendDemo() {
        System.out.println("========== Flink 三种 StateBackend ==========\n");

        String fmt = "| %-22s | %-12s | %-10s | %-8s |%n";
        System.out.printf(fmt, "StateBackend", "State位置", "CK位置", "增量CK");
        System.out.println("|------------------------|--------------|------------|----------|");
        System.out.printf(fmt, "HashMapStateBackend", "JVM Heap", "JM Memory", "不支持");
        System.out.printf(fmt, "EmbeddedRocksDB", "磁盘+内存缓存", "HDFS/FS", "支持");
        System.out.println();

        System.out.println("HashMapStateBackend:");
        System.out.println("  - 数据对象直接存 JVM Heap，访问零开销");
        System.out.println("  - 需要警惕 OOM，建议单 Slot < 5GB 状态");
        System.out.println("  - Checkpoint 全量快照到 JobManager 内存 → 限制 Checkpoint 大小");
        System.out.println();

        System.out.println("EmbeddedRocksDBStateBackend:");
        System.out.println("  - 状态序列化后存 RocksDB LSM Tree 磁盘");
        System.out.println("  - 读写需要序列化/反序列化 → 有 CPU 开销");
        System.out.println("  - 支持增量 Checkpoint (只上传新增 SST 文件)");
        System.out.println("  - TB 级状态稳定（如用户画像窗口聚合）\n");
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        rdChandyLamportDemo();
        rdCheckpointFlowDemo();
        rdExactlyOnceSemantics();
        rdSavepointVsCheckpoint();
        rdStateBackendDemo();

        System.out.println("========== 演示完毕 ==========");
    }
}