package study;

import java.util.*;

/**
 * 全项目模块总览 + 学习路线入口。
 *
 * <p>custom-study 分为两个子工程：
 * <ul>
 *   <li><b>study-base</b>（阶段1）—— JDK 源码底盘：泛型、集合、设计模式、反射、并发、JVM、NIO</li>
 *   <li><b>study-tuling</b>（阶段2-4）—— 框架 + 中间件 + 大数据 + DevOps</li>
 * </ul>
 *
 * <p>每个模块的结构：{@code Demo → SourceAnalysis → Tuning → Interview}，
 * 所有 Demo 都含 main 方法，可直接独立运行。
 *
 * <p>配套笔记位于 {@code src/main/resources/notes/模块名/}，
 * 采用 Markdown + Mermaid 图表，覆盖核心原理、源码分析和面试高频题。
 */
public class StudyOverview {

    static final Map<String, String[]> STAGE1 = new LinkedHashMap<>();
    static final Map<String, String[]> STAGE2 = new LinkedHashMap<>();
    static final Map<String, String[]> STAGE3 = new LinkedHashMap<>();
    static final Map<String, String[]> STAGE4 = new LinkedHashMap<>();

    static {
        STAGE1.put("generics", new String[]{
                "GenericDemo", "PECSDemo", "TypeErasureDemo", "WildcardDemo"});
        STAGE1.put("collection", new String[]{
                "HashMapDemo", "HashMapSourceAnalysis", "HashMapTuning"});
        STAGE1.put("design_patterns", new String[]{
                "SingletonDemo", "FactoryMethodDemo", "ProxyDemo", "StrategyDemo", "TemplateMethodDemo"});
        STAGE1.put("reflect", new String[]{
                "ReflectDemo", "AnnotationDemo", "ProxyDemo"});
        STAGE1.put("concurrent", new String[]{
                "CASDemo", "SynchronizedDemo", "VolatileDemo", "AQSDemo", "MyLock", "ThreadPoolDemo"});
        STAGE1.put("jvm", new String[]{
                "MemoryModelDemo", "ClassLoaderDemo", "GCDemo", "JVMTuningDemo"});
        STAGE1.put("nio", new String[]{
                "NIODemo", "StickyPacketDemo", "ZeroCopyDemo"});

        STAGE2.put("spring/core", new String[]{
                "IoCDemo", "BeanLifecycleDemo", "AOPDemo", "TransactionalSourceAnalysis"});
        STAGE2.put("spring/boot", new String[]{
                "AutoConfigPrincipleDemo", "StarterPrincipleDemo", "ConditionalDemo", "LifecycleDemo"});
        STAGE2.put("spring/alibaba", new String[]{
                "NacosConfigDemo", "NacosDiscoveryDemo", "SentinelDemo", "SeataDemo", "FeignDemo"});
        STAGE2.put("mybatis", new String[]{
                "MyBatisCoreDemo", "MyBatisPlusDemo", "SourceAnalysisDemo", "DynamicSQLDemo"});
        STAGE2.put("redis", new String[]{
                "DataStructureDemo", "PersistenceDemo", "ClusterDemo", "CacheProblemDemo", "BigKeyDemo", "RedisTuningDemo"});
        STAGE2.put("mq", new String[]{
                "RocketMQCoreDemo", "MessageReliabilityDemo", "MessageOrderDemo", "TransactionDemo",
                "KafkaCoreDemo", "KafkaIdempotentDemo"});
        STAGE2.put("netty", new String[]{
                "EventLoopDemo", "PipelineDemo", "ZeroCopyDemo", "StickyPacketDemo"});
        STAGE2.put("websocket", new String[]{
                "WebSocketCoreDemo", "ConnectionManagerDemo", "HeartbeatDemo", "ReconnectDemo"});
        STAGE2.put("db", new String[]{
                "IndexDemo", "SQLRewriteDemo", "ShardingJDBCDemo", "SQLOptimizerDemo", "PostgreSQLDemo", "OracleKingbaseDemo"});

        STAGE3.put("elasticsearch", new String[]{
                "InvertedIndexDemo", "DSLQueryDemo", "AggregationDemo", "ShardingClusterDemo"});
        STAGE3.put("mongodb", new String[]{
                "DocumentModelDemo", "AggregationPipelineDemo", "IndexDemo", "ReplicaShardDemo"});
        STAGE3.put("distributed", new String[]{
                "TransactionDemo", "LockDemo", "IDGeneratorDemo", "CAPBaseDemo", "ConsistencyDemo"});
        STAGE3.put("hbase", new String[]{
                "RowKeyDesignDemo", "RegionSplitDemo", "SecondaryIndexDemo", "StorageArchitectureDemo"});
        STAGE3.put("seatunnel", new String[]{
                "PluginModelDemo", "DataSourceIntegrationDemo", "TransformDemo"});
        STAGE3.put("dolphinscheduler", new String[]{
                "DAGDemo", "TaskSchedulerDemo", "TaskTypeDemo"});
        STAGE3.put("minio", new String[]{
                "ObjectStorageDemo", "ErasureCodingDemo", "PolicyVersionDemo"});

        STAGE4.put("flink", new String[]{
                "WindowDemo", "WatermarkDemo", "CheckpointDemo", "StateBackendDemo"});
        STAGE4.put("spark", new String[]{
                "RDDDemo", "DAGShuffleDemo", "DataFrameDemo", "BroadcastJoinDemo"});
        STAGE4.put("devops", new String[]{
                "MavenDemo", "GitDemo", "DockerDemo", "K8sDemo", "LinuxDemo"});
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║              custom-study — 全栈知识体系                         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║  study-base  (7 modules)  — JDK 源码底盘                        ║");
        System.out.println("║  study-tuling (17 modules) — 框架 + 中间件 + 大数据 + DevOps    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        printSection("阶段1 — JDK 源码底盘（study-base）", STAGE1);
        printSection("阶段2 — 框架 + 中间件（study-tuling）", STAGE2);
        printSection("阶段3 — 分布式 + 大数据存储（study-tuling）", STAGE3);
        printSection("阶段4 — 流计算 + DevOps（study-tuling）", STAGE4);

        System.out.println("────────────────────────────────────────────────────────────────────");
        System.out.println("  运行方式：每个 Demo 类都有 main 方法，在 IDE 中直接运行即可。");
        System.out.println("  笔记索引：src/main/resources/notes/{模块名}/  共 80+ 篇。");
        System.out.println("  项目文档：根目录 问题设计.md（微服务架构设计）。");
        System.out.println("────────────────────────────────────────────────────────────────────");
        System.out.println();
    }

    static void printSection(String title, Map<String, String[]> modules) {
        System.out.println();
        System.out.println("  ── " + title);
        for (Map.Entry<String, String[]> entry : modules.entrySet()) {
            String module = entry.getKey();
            String[] demos = entry.getValue();
            String count = String.format("(%d)", demos.length);
            String packagePath = module.replace('/', '.');
            System.out.printf("  %-20s %-5s   %s.%s%n", module, count, packagePath, demos[0]);
            for (int i = 1; i < Math.min(demos.length, 5); i++) {
                System.out.printf("  %-20s        %s.%s%n", "", packagePath, demos[i]);
            }
            if (demos.length > 5) {
                System.out.printf("  %-20s        ... +%d more%n", "", demos.length - 5);
            }
        }
    }
}