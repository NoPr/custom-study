package base.seatunnel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seatunnel 插件模型演示：Source(读取器) + Transform(转换器) + Sink(写入器) SPI 机制 + 手写 PluginLoader + 多源多目标 DAG 编排。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>SPI 机制</b>：通过 PluginLoader 动态发现和加载插件实现，类似 JDK ServiceLoader</li>
 *   <li><b>Source</b>：从外部数据源读取数据，生产 {@code DataRecord}</li>
 *   <li><b>Transform</b>：对数据进行转换处理（映射、过滤、聚合等）</li>
 *   <li><b>Sink</b>：将处理后的数据写入目标端</li>
 *   <li><b>DAG</b>：有向无环图编排多个 Source → Transform → Sink 的数据管道</li>
 *   <li><b>Checkpoint</b>：支持断点续传的状态存储</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()。
 *
 * @author study-tuling
 */
public class PluginModelDemo {

    public static void main(String[] args) {
        System.out.println("=== Seatunnel 插件模型演示 ===\n");

        // 1. 注册插件到 PluginLoader
        PluginLoader.register("fake-source", FakeSourcePlugin.class.getName());
        PluginLoader.register("console-sink", ConsoleSinkPlugin.class.getName());
        PluginLoader.register("field-rename-transform", FieldRenameTransformPlugin.class.getName());

        // 2. 构建 DAG 编排：Source(MySQL) → Transform(字段映射) → Sink(Console/Kafka)
        DAGPipeline pipeline = new DAGPipeline("etl-pipeline-001");

        // 源节点
        DAGNode sourceNode = pipeline.addNode("source-mysql", "fake-source",
                Map.of("table", "user_info", "batch_size", "500"));

        // 转换节点
        DAGNode transformNode = pipeline.addNode("transform-rename", "field-rename-transform",
                Map.of("mappings", "user_name→name,user_age→age"));

        // 目标节点
        DAGNode sinkConsoleNode = pipeline.addNode("sink-console", "console-sink",
                Map.of("format", "json"));
        DAGNode sinkKafkaNode = pipeline.addNode("sink-kafka", "console-sink",
                Map.of("topic", "ods_user_info"));

        // 编排边：source → transform → sink(console) / sink(kafka)
        pipeline.addEdge(sourceNode, transformNode);
        pipeline.addEdge(transformNode, sinkConsoleNode);
        pipeline.addEdge(transformNode, sinkKafkaNode);

        // 3. 执行流水线
        System.out.println("--- DAG 编排信息 ---");
        System.out.println(pipeline.describe());
        System.out.println("\n--- 开始执行流水线 ---");
        pipeline.execute();
        System.out.println("--- 流水线执行完成 ---");
    }


    // ==================== 核心接口定义 ====================

    /** 数据记录：模拟 Seatunnel 中的 Row/SeaTunnelRow */
    static class DataRecord {
        private final long id;
        private final Map<String, Object> fields;

        DataRecord(long id, Map<String, Object> fields) {
            this.id = id;
            this.fields = new LinkedHashMap<>(fields);
        }

        long id() { return id; }
        Map<String, Object> fields() { return fields; }
        Object get(String fieldName) { return fields.get(fieldName); }
        void put(String fieldName, Object value) { fields.put(fieldName, value); }
        void remove(String fieldName) { fields.remove(fieldName); }

        DataRecord copy() {
            return new DataRecord(this.id, new LinkedHashMap<>(this.fields));
        }

        @Override
        public String toString() {
            return "Record{id=" + id + ", fields=" + fields + '}';
        }
    }

    /** 检查点状态：用于断点续传 */
    static class CheckpointState {
        private final String pipelineId;
        private final long lastCommittedId;
        private final long timestamp;

        CheckpointState(String pipelineId, long lastCommittedId) {
            this.pipelineId = pipelineId;
            this.lastCommittedId = lastCommittedId;
            this.timestamp = System.currentTimeMillis();
        }

        long lastCommittedId() { return lastCommittedId; }
        @Override
        public String toString() { return "Checkpoint{pipeline=" + pipelineId + ", offset=" + lastCommittedId + '}'; }
    }


    // ==================== SPI 接口 ====================

    /** Source 插件接口 */
    interface SourcePlugin {
        /** 插件名称 */
        String pluginName();
        /** 打开连接/初始化 */
        void open(Map<String, Object> config);
        /** 读取下一批数据，返回 null 表示读取完毕 */
        List<DataRecord> readBatch();
        /** 关闭连接 */
        void close();
    }

    /** Transform 插件接口 */
    interface TransformPlugin {
        String pluginName();
        void open(Map<String, Object> config);
        /** 对单条记录进行转换 */
        DataRecord transform(DataRecord record);
        void close();
    }

    /** Sink 插件接口 */
    interface SinkPlugin {
        String pluginName();
        void open(Map<String, Object> config);
        /** 写入单条记录 */
        void write(DataRecord record);
        /** 批量写入 */
        void writeBatch(List<DataRecord> records);
        void close();
    }


    // ==================== PluginLoader（模拟 ServiceLoader） ====================

    /** 手写插件加载器：替代 JDK ServiceLoader，展示 SPI 加载原理 */
    static class PluginLoader {
        /** 插件注册表：插件名 → 全限定类名 */
        private static final Map<String, String> REGISTRY = new ConcurrentHashMap<>();
        /** 实例缓存 */
        private static final Map<String, Object> INSTANCE_CACHE = new ConcurrentHashMap<>();

        static void register(String pluginName, String className) {
            REGISTRY.put(pluginName, className);
        }

        @SuppressWarnings("unchecked")
        static <T> T load(String pluginName, Class<T> pluginType) {
            String className = REGISTRY.get(pluginName);
            if (className == null) {
                throw new IllegalArgumentException("未找到插件: " + pluginName);
            }
            try {
                Object cached = INSTANCE_CACHE.get(pluginName);
                if (cached != null && pluginType.isInstance(cached)) {
                    return (T) cached;
                }
                /* 反射创建实例 —— 模拟 SPI 中 ServiceLoader 的实例化过程 */
                Class<?> clazz = Class.forName(className);
                T instance = (T) clazz.getDeclaredConstructor().newInstance();
                INSTANCE_CACHE.put(pluginName, instance);
                return instance;
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("插件加载失败: " + pluginName + " → " + className, e);
            }
        }

        /** 列出所有已注册的插件 */
        static Set<String> listPlugins() {
            return Collections.unmodifiableSet(REGISTRY.keySet());
        }
    }


    // ==================== 插件实现 ====================

    /** Fake 数据源：模拟 MySQL/JDBC 分片读取 */
    static class FakeSourcePlugin implements SourcePlugin {
        private int totalRecords = 20;
        private int batchSize = 5;
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public String pluginName() { return "fake-source"; }

        @Override
        public void open(Map<String, Object> config) {
            String table = (String) config.get("table");
            Object bs = config.get("batch_size");
            if (bs != null) {
                batchSize = Integer.parseInt(bs.toString());
            }
            System.out.printf("  [FakeSource] 打开数据源 table=%s, batchSize=%d%n", table, batchSize);
        }

        @Override
        public List<DataRecord> readBatch() {
            int current = counter.get();
            if (current >= totalRecords) return null; // 读取完毕
            List<DataRecord> batch = new ArrayList<>();
            int end = Math.min(current + batchSize, totalRecords);
            for (int i = current; i < end; i++) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("user_name", "user_" + i);
                fields.put("user_age", 20 + (i % 30));
                fields.put("user_email", "user" + i + "@example.com");
                fields.put("create_time", System.currentTimeMillis() - i * 86400000L);
                batch.add(new DataRecord(i + 1, fields));
            }
            counter.set(end);
            System.out.printf("  [FakeSource] 读取批次 [%d-%d], 共 %d 条%n", current + 1, end, batch.size());
            return batch;
        }

        @Override
        public void close() { System.out.println("  [FakeSource] 关闭数据源"); }
    }

    /** 字段重命名转换器 */
    static class FieldRenameTransformPlugin implements TransformPlugin {
        private Map<String, String> renameMappings = new LinkedHashMap<>();

        @Override
        public String pluginName() { return "field-rename-transform"; }

        @Override
        public void open(Map<String, Object> config) {
            String mappings = (String) config.get("mappings");
            if (mappings != null) {
                for (String pair : mappings.split(",")) {
                    String[] kv = pair.trim().split("→"); // 使用中文箭头分隔
                    if (kv.length == 2) {
                        renameMappings.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
            System.out.printf("  [RenameTransform] 字段映射: %s%n", renameMappings);
        }

        @Override
        public DataRecord transform(DataRecord record) {
            for (Map.Entry<String, String> entry : renameMappings.entrySet()) {
                Object value = record.get(entry.getKey());
                if (value != null) {
                    record.remove(entry.getKey());
                    record.put(entry.getValue(), value);
                }
            }
            return record;
        }

        @Override
        public void close() { System.out.println("  [RenameTransform] 关闭转换器"); }
    }

    /** Console 输出 Sink */
    static class ConsoleSinkPlugin implements SinkPlugin {
        private String format = "json";
        private int written = 0;

        @Override
        public String pluginName() { return "console-sink"; }

        @Override
        public void open(Map<String, Object> config) {
            if (config.containsKey("format")) {
                format = (String) config.get("format");
            }
            System.out.printf("  [ConsoleSink] 打开, format=%s%n", format);
        }

        @Override
        public void write(DataRecord record) {
            written++;
            System.out.printf("  [ConsoleSink] #%d → %s%n", written, record);
        }

        @Override
        public void writeBatch(List<DataRecord> records) {
            for (DataRecord record : records) {
                write(record);
            }
        }

        @Override
        public void close() { System.out.printf("  [ConsoleSink] 关闭, 共写入 %d 条%n", written); }
    }


    // ==================== DAG 编排引擎 ====================

    /** DAG 节点 */
    static class DAGNode {
        final String nodeId;
        final String pluginName;
        final Map<String, Object> config;
        final List<DAGNode> downstream = new ArrayList<>(); // 下游节点
        Object pluginInstance;

        DAGNode(String nodeId, String pluginName, Map<String, Object> config) {
            this.nodeId = nodeId;
            this.pluginName = pluginName;
            this.config = config;
        }

        @Override
        public String toString() { return "Node{" + nodeId + ":" + pluginName + '}'; }
    }

    /** DAG 边 */
    record DAGEdge(DAGNode from, DAGNode to) {}

    /** DAG 流水线：管理节点与边的拓扑关系 */
    static class DAGPipeline {
        final String pipelineId;
        final LinkedHashMap<String, DAGNode> nodeMap = new LinkedHashMap<>();
        final List<DAGEdge> edges = new ArrayList<>();
        private DAGNode rootNode;

        DAGPipeline(String pipelineId) {
            this.pipelineId = pipelineId;
        }

        DAGNode addNode(String nodeId, String pluginName, Map<String, Object> config) {
            if (nodeMap.containsKey(nodeId)) {
                throw new IllegalArgumentException("节点已存在: " + nodeId);
            }
            DAGNode node = new DAGNode(nodeId, pluginName, config);
            nodeMap.put(nodeId, node);
            if (rootNode == null) rootNode = node;
            return node;
        }

        void addEdge(DAGNode from, DAGNode to) {
            edges.add(new DAGEdge(from, to));
            from.downstream.add(to);
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Pipeline: ").append(pipelineId).append("\n");
            sb.append("  节点数: ").append(nodeMap.size()).append("\n");
            sb.append("  边数: ").append(edges.size()).append("\n");
            for (DAGNode node : nodeMap.values()) {
                sb.append("  ").append(node.nodeId).append(" [").append(node.pluginName).append("]");
                if (!node.downstream.isEmpty()) {
                    sb.append(" → ");
                    for (DAGNode ds : node.downstream) {
                        sb.append(ds.nodeId).append(" ");
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        /** 执行流水线：拓扑顺序逐节点处理，支持多路输出 */
        void execute() {
            System.out.printf("  流水线 [%s] 开始执行%n", pipelineId);

            // 初始化所有插件
            for (DAGNode node : nodeMap.values()) {
                if (node.pluginName.contains("source")) {
                    node.pluginInstance = PluginLoader.load(node.pluginName, SourcePlugin.class);
                    ((SourcePlugin) node.pluginInstance).open(node.config);
                } else if (node.pluginName.contains("transform")) {
                    node.pluginInstance = PluginLoader.load(node.pluginName, TransformPlugin.class);
                    ((TransformPlugin) node.pluginInstance).open(node.config);
                } else {
                    node.pluginInstance = PluginLoader.load(node.pluginName, SinkPlugin.class);
                    ((SinkPlugin) node.pluginInstance).open(node.config);
                }
            }

            // 从 Source 节点开始拉取数据
            DAGNode sourceNode = findSourceNode();
            SourcePlugin source = (SourcePlugin) sourceNode.pluginInstance;

            List<DataRecord> batch;
            int batchNo = 0;
            while ((batch = source.readBatch()) != null) {
                batchNo++;
                System.out.printf("%n--- 处理第 %d 批数据 (%d 条) ---%n", batchNo, batch.size());

                // 沿 DAG 传播：对每个下游节点进行处理
                for (DAGNode downstreamNode : sourceNode.downstream) {
                    propagateData(batch, downstreamNode);
                }
            }

            // 关闭所有插件
            for (DAGNode node : nodeMap.values()) {
                if (node.pluginInstance instanceof SourcePlugin s) { s.close(); }
                else if (node.pluginInstance instanceof TransformPlugin t) { t.close(); }
                else if (node.pluginInstance instanceof SinkPlugin sk) { sk.close(); }
            }

            // 模拟产生检查点
            CheckpointState checkpoint = new CheckpointState(pipelineId, 20);
            System.out.printf("%n  流水线 [%s] Checkpoint: %s%n", pipelineId, checkpoint);
        }

        /** 查找源节点（入度为 0 的节点） */
        private DAGNode findSourceNode() {
            Set<String> hasIncoming = new HashSet<>();
            for (DAGEdge edge : edges) {
                hasIncoming.add(edge.to().nodeId);
            }
            for (DAGNode node : nodeMap.values()) {
                if (!hasIncoming.contains(node.nodeId)) {
                    return node;
                }
            }
            throw new IllegalStateException("DAG 中未找到源节点");
        }

        /** 沿 DAG 向下游传播数据 */
        private void propagateData(List<DataRecord> batch, DAGNode node) {
            // 对每条记录应用当前节点的转换
            List<DataRecord> processed = new ArrayList<>();
            for (DataRecord record : batch) {
                DataRecord r = record.copy(); // 多路输出时需要 copy
                if (node.pluginInstance instanceof TransformPlugin transform) {
                    r = transform.transform(r);
                }
                processed.add(r);
            }

            if (node.pluginInstance instanceof SinkPlugin sink) {
                /* 到达叶子节点，写入 Sink */
                sink.writeBatch(processed);
            }

            /* 继续向下游传播 */
            for (DAGNode ds : node.downstream) {
                propagateData(processed, ds);
            }
        }
    }
}