package base.flink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;

/**
 * Flink State 状态管理：KeyedState(ValueState/ListState/MapState/ReducingState)
 * + 状态后端分级存储 + 手写 RocksDB 写放大问题(L0→L1→L2 Compaction)
 * + TTL 自动清理 + BroadcastState 广播配置。
 *
 * <p>核心考点：
 * <ul>
 *   <li><b>KeyedState</b>：每个 Key 独立状态，通过 KeyGroup 映射到物理存储</li>
 *   <li><b>ValueState</b>：单值状态，get/update 原子操作</li>
 *   <li><b>ListState</b>：列表状态，add/addAll/get 支持迭代</li>
 *   <li><b>MapState</b>：KV 状态，put/get/contains/remove 支持迭代</li>
 *   <li><b>ReducingState</b>：聚合状态，每次 add 自动执行 ReduceFunction</li>
 *   <li><b>StateBackend</b>：MemoryStateBackend / FsStateBackend / RocksDBStateBackend</li>
 *   <li><b>RocksDB LSM Tree 写放大</b>：L0→L1 Compaction 文件数增多导致重复读写</li>
 *   <li><b>TTL</b>：状态过期自动清理，避免无限膨胀</li>
 *   <li><b>BroadcastState</b>：广播配置流，所有并行实例持有相同状态副本</li>
 * </ul>
 *
 * @author study-tuling
 */
public class StateBackendDemo {

    /* ======================== KeyedState 接口定义 ======================== */

    /** ValueState：单值状态 */
    static class ValueState<V> {
        V value;
        void update(V v) { this.value = v; }
        V value() { return value; }
        void clear() { value = null; }
    }

    /** ListState：列表状态 */
    static class ListState<V> {
        final List<V> values = new ArrayList<>();
        void add(V v) { values.add(v); }
        void addAll(List<V> vs) { values.addAll(vs); }
        List<V> get() { return Collections.unmodifiableList(values); }
        void clear() { values.clear(); }
    }

    /** MapState：KV 状态 */
    static class MapState<K, V> {
        final Map<K, V> map = new ConcurrentHashMap<>();
        void put(K k, V v) { map.put(k, v); }
        V get(K k) { return map.get(k); }
        boolean contains(K k) { return map.containsKey(k); }
        void remove(K k) { map.remove(k); }
        Set<K> keys() { return Collections.unmodifiableSet(map.keySet()); }
    }

    /** ReducingState：聚合状态，自动执行 ReduceFunction */
    static class ReducingState<V> {
        V current;
        final BinaryOperator<V> reduceFunction;

        ReducingState(BinaryOperator<V> reduceFunction) {
            this.reduceFunction = reduceFunction;
        }

        void add(V v) {
            if (current == null) {
                current = v;
            } else {
                current = reduceFunction.apply(current, v);
            }
        }

        V get() { return current; }
        void clear() { current = null; }
    }

    /* ======================== 多种 KeyedState 演示 ======================== */

    static void rdKeyedStateDemo() {
        System.out.println("========== KeyedState 四大类型手写展示 ==========\n");

        System.out.println("--- 1. ValueState<T> 单值状态 ---");
        ValueState<Integer> valueState = new ValueState<>();
        valueState.update(100);
        System.out.println("  get() = " + valueState.value());
        valueState.update(200);
        System.out.println("  update(200) -> get() = " + valueState.value());
        valueState.clear();
        System.out.println("  clear() -> get() = " + valueState.value());

        System.out.println("\n--- 2. ListState<T> 列表状态 ---");
        ListState<String> listState = new ListState<>();
        listState.add("a");
        listState.add("b");
        listState.addAll(List.of("c", "d", "e"));
        System.out.println("  get() = " + listState.get());
        listState.clear();
        System.out.println("  clear() -> get() = " + listState.get());

        System.out.println("\n--- 3. MapState<K,V> KV 状态 ---");
        MapState<String, Integer> mapState = new MapState<>();
        mapState.put("apple", 10);
        mapState.put("banana", 20);
        System.out.println("  contains('apple') = " + mapState.contains("apple"));
        System.out.println("  get('banana') = " + mapState.get("banana"));
        System.out.println("  keys() = " + mapState.keys());
        mapState.remove("apple");
        System.out.println("  remove('apple') -> contains('apple') = " + mapState.contains("apple"));

        System.out.println("\n--- 4. ReducingState<T> 聚合状态 ---");
        ReducingState<Integer> reducingState = new ReducingState<>(Integer::sum);
        reducingState.add(5);
        reducingState.add(10);
        reducingState.add(15);
        System.out.println("  add(5) -> add(10) -> add(15) -> get() = " + reducingState.get());
    }

    /* ======================== StateBackend 分级存储 ======================== */

    /**
     * StateBackend 分级存储模型。
     * <p>MemoryBackend：全内存（速度快，OOM 风险高）
     * <p>FsBackend：本地状态 + 文件系统 Checkpoint（中等容量）
     * <p>RocksDBBackend：磁盘 + 内存缓存（海量状态，但序列化开销）
     */
    enum StateBackendType {
        /** 内存后端 -- 状态全在 JVM 堆, CK 也存 JobManager 内存, 限制 5MB */
        MEMORY("HashMapBackend", "内存", "极快", "5MB"),
        /** 文件后端 -- 状态在 TM 内存, CK 存 HDFS/本地文件, GB 级 */
        FS_BACKEND("HashMapBackend", "内存+磁盘(CK)", "快", "GB"),
        /** RocksDB 后端 -- 状态序列化存磁盘, 增量 CK, TB 级 */
        ROCKSDB("EmbeddedRocksDB", "磁盘+L0内存缓存", "中等(有序列化开销)", "TB");

        final String backend;
        final String storage;
        final String speed;
        final String capacity;

        StateBackendType(String backend, String storage, String speed, String capacity) {
            this.backend = backend;
            this.storage = storage;
            this.speed = speed;
            this.capacity = capacity;
        }
    }

    static void rdStateBackendCompare() {
        System.out.println("\n--- StateBackend 分级存储对比 ---\n");
        System.out.printf("| %-18s | %-12s | %-10s | %-6s |%n",
                "后端", "存储位置", "速度", "容量");
        System.out.println("|--------------------|--------------|------------|--------|");
        for (StateBackendType t : StateBackendType.values()) {
            System.out.printf("| %-18s | %-12s | %-10s | %-6s |%n",
                    t.name(), t.storage, t.speed, t.capacity);
        }
        System.out.println("\n  选型建议:");
        System.out.println("    开发测试 → MemoryBackend (快, 无外部依赖)");
        System.out.println("    生产中等 → FsBackend (GB级, HDFS Checkpoint)");
        System.out.println("    海量状态 → RocksDB (TB级, 增量CK, 唯一支持增量快照)\n");
    }

    /* ======================== RocksDB LSM Tree 写放大 ======================== */

    /**
     * RocksDB LSM Tree 写放大问题模拟。
     *
     * <p>LSM Tree 结构：
     * <pre>
     *   MemTable (活跃写入, SkipList 有序) → Immutable MemTable (不可变, 等待 Flush)
     *     ↓ Flush
     *   L0 SST Files (无序, 有重叠 KeyRange) —— 每个文件独立排序
     *     ↓ Compaction (归并排序+去重)
     *   L1 SST Files (全局有序, 无Key重叠, 大小 ~256MB)
     *     ↓ Compaction
     *   L2 SST Files (全局有序, 无Key重叠, 大小 ~2.5GB)
     *     ↓ ...
     *   Ln ...
     * </pre>
     *
     * <p>写放大产生原因：Level Compaction 层层归并，1MB 写入最终可能产生 10+MB 磁盘 I/O。
     * <p>计算公式：Write Amplification = Total Bytes Written / User Bytes Written
     * <p>典型值：Level Compaction 约 10-30x，Universal Compaction 约 2-5x。
     */
    static void rdRocksDBWriteAmplificationDemo() {
        System.out.println("--- RocksDB LSM Tree 写放大原理 ---\n");

        System.out.println("""
                LSM Tree 写入路径:
                1. 数据先写 WAL (Write Ahead Log) -- 保证 crash-safe
                2. 写入 MemTable (内存 SkipList 有序)
                3. MemTable 满 → 转为 Immutable MemTable → Flush 到 L0 SST
                4. L0 文件数 > 阈值(默认4) → Compaction L0→L1 (归并排序)
                5. L1 文件数 > 阈值 → Compaction L1→L2 ... → Ln
                """);

        // 模拟写放大计算
        System.out.println("模拟 Compaction 写放大:");
        System.out.println("  第1层: 4个 L0 SST(各64MB) → Compaction → 归并排序后写回 L1");
        System.out.println("           读取: 4×64MB=256MB,  L1中重叠区间约100MB → 读 356MB");
        System.out.println("           写入: 去重+归并后 → 约 200MB");
        System.out.println("           写放大系数: (256+200) / 64(原始一个SST的写入) ≈ 7.1x");
        System.out.println();
        System.out.println("  第2层: L1(200MB全部) + L2重叠区间(500MB) → 读取 700MB");
        System.out.println("           写入: 归并后 → 约 600MB");
        System.out.println("           写放大系数: (700+600) / 200 ≈ 6.5x");
        System.out.println();
        System.out.println("  总体写放大: 1 → 7x → 6.5x (层层放大) ≈ 30-50x in extreme cases");
        System.out.println("  优化: 调大 write_buffer_size, 使用 Universal/FIFO Compaction\n");
    }

    /* ======================== TTL 状态自动清理 ======================== */

    /**
     * Flink State TTL (Time To Live) 自动过期清理机制。
     *
     * <p>三种清理策略：
     * <ul>
     *   <li><b>OnReadAndWrite</b>：读写时检查并清理（默认）</li>
     *   <li><b>OnCreateAndWrite</b>：写入时检查，读取不清理</li>
     *   <li><b>Never</b>：全量快照时才清理，状态可能积累</li>
     * </ul>
     */
    static void rdStateTTLDemo() {
        System.out.println("--- State TTL 自动清理 ---\n");

        record StateEntry<V>(V value, long createTime, long ttlMs) {
            boolean isExpired() {
                return System.currentTimeMillis() - createTime > ttlMs;
            }
        }

        Map<String, StateEntry<Integer>> stateStore = new LinkedHashMap<>();
        long ttlMs = 3_000L; // 3秒过期

        // 写入一些状态
        stateStore.put("key-1", new StateEntry<>(100, System.currentTimeMillis(), ttlMs));
        stateStore.put("key-2", new StateEntry<>(200, System.currentTimeMillis() - 4000, ttlMs));
        stateStore.put("key-3", new StateEntry<>(300, System.currentTimeMillis() - 2000, ttlMs));

        System.out.printf("TTL=%dms, 状态数量=%d (清理前)%n", ttlMs, stateStore.size());

        // 清理策略: OnReadAndWrite -- 访问时检查过期
        Iterator<Map.Entry<String, StateEntry<Integer>>> it = stateStore.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, StateEntry<Integer>> entry = it.next();
            if (entry.getValue().isExpired()) {
                System.out.printf("  [TTL 清理] %s (已过期 %dms)%n",
                        entry.getKey(),
                        System.currentTimeMillis() - entry.getValue().createTime());
                it.remove();
            }
        }

        System.out.printf("  清理后状态数量=%d%n", stateStore.size());
        stateStore.forEach((k, v) ->
                System.out.printf("    保留: %s = %d (剩余TTL=%dms)%n",
                        k, v.value(),
                        v.ttlMs - (System.currentTimeMillis() - v.createTime())));

        System.out.println("\n  设计要点:");
        System.out.println("    Flink TTL 不是后台线程定时清理, 而是访问时惰性清理");
        System.out.println("    全量快照(FullSnapshot)也会触发清理（Never策略专用）");
        System.out.println("    注意: TTL 基于 ProcessingTime 可能导致数据不一致\n");
    }

    /* ======================== BroadcastState 广播配置 ======================== */

    /**
     * BroadcastState 广播配置流：所有并行实例持有相同状态副本。
     *
     * <p>典型场景：规则引擎动态更新，配置流广播到所有 Task。
     * <p>BroadcastState 写入规则：
     * <ul>
     *   <li>所有并行实例相同数据 → 数据一致</li>
     *   <li>Checkpoint 时所有并行实例一起快照</li>
     *   <li>恢复时所有并行实例一起恢复, 保证全局一致</li>
     * </ul>
     */
    static void rdBroadcastStateDemo() {
        System.out.println("--- BroadcastState 广播配置 ---\n");

        // 模拟 3 个并行 Task 实例
        List<String> instances = List.of("Task-1", "Task-2", "Task-3");
        // 广播的配置 Map
        Map<String, String> broadcastConfig = new ConcurrentHashMap<>();

        System.out.println("场景: 实时规则匹配, 配置通过广播流下发");

        // 配置流: 规则规则
        broadcastConfig.put("rule.amount.threshold", "10000");
        broadcastConfig.put("rule.amount.action", "SEND_ALERT");
        broadcastConfig.put("rule.user.blacklist", "user-1,user-2,user-3");

        System.out.println("广播配置内容:");
        broadcastConfig.forEach((k, v) -> System.out.printf("  %-24s = %s%n", k, v));

        System.out.println("\n各 Task 实例读取广播配置:");
        for (String instance : instances) {
            System.out.printf("  [%s] 规则: %s=%s, 黑名单: %s=%s%n",
                    instance,
                    "rule.amount.threshold",
                    broadcastConfig.get("rule.amount.threshold"),
                    "rule.user.blacklist",
                    broadcastConfig.get("rule.user.blacklist"));
        }

        System.out.println("\n  BroadcastState 关键特性:");
        System.out.println("    1. 所有并行实例状态副本完全一致 (保证全局规则一致性)");
        System.out.println("    2. 仅 BroadcastStream 可写入 BroadcastState");
        System.out.println("    3. 非广播流(keyed/broadcast)只能读取, 不能写入");
        System.out.println("    4. Checkpoint 保证所有副本在同一 Barrier 上一致\n");
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        rdKeyedStateDemo();
        rdStateBackendCompare();
        rdRocksDBWriteAmplificationDemo();
        rdStateTTLDemo();
        rdBroadcastStateDemo();

        System.out.println("========== 演示完毕 ==========");
    }
}