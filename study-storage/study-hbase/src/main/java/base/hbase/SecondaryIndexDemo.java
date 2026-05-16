package base.hbase;

import java.util.*;

/**
 * HBase 二级索引三种实现方案纯 Java 模拟。
 * 方案 1: 全局索引 -- 索引表独立于数据表, 查询时先查索引表获取 RowKey, 再回数据表获取完整数据 (二次查询)
 * 方案 2: 本地索引 -- 索引与数据存储在同一张表的同一 Region, Scan 时遍历所有 Region 的索引列
 * 方案 3: 协处理器 (Observer) -- 数据写入时, 通过 postPut 钩子自动同步更新索引表, 保证数据一致性
 *
 * <p>Phoenix 概念: Apache Phoenix 基于协处理器框架实现 SQL on HBase,
 * 通过 RegionObserver 和 RegionScanner 实现二级索引、聚合查询、分页等能力,
 * 本质上是对 HBase 协处理器能力的封装和扩展。
 */
public class SecondaryIndexDemo {

    public static void main(String[] args) {
        System.out.println("========== HBase 二级索引方案演示 ==========\n");

        globalIndexDemo();
        localIndexDemo();
        coprocessorIndexDemo();
        phoenixConceptDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /* ===================== 方案1: 全局索引 ===================== */

    /**
     * 全局索引: 索引表与数据表完全独立, 分布在不同的 Region 上。
     *
     * <p>写入: 同时写数据表和索引表 (需要事务保证, 否则可能不一致)
     * 查询: 先查索引表获取匹配的 RowKey 列表, 再根据 RowKey 去数据表获取完整数据 (二次查询)
     *
     * <p>优点: 索引独立, 查询效率高 (只需扫描索引所在的 Region)
     * 缺点: 二次查询有网络开销; 写入需要保证数据表和索引表的一致性
     */
    static void globalIndexDemo() {
        System.out.println("--- 方案1: 全局索引 (索引表独立) ---");

        /* 模拟数据表: RowKey -> 用户信息 */
        Map<String, UserInfo> dataTable = new LinkedHashMap<>();
        dataTable.put("user_001", new UserInfo("user_001", "张三", "zhangsan@example.com", 28));
        dataTable.put("user_002", new UserInfo("user_002", "李四", "lisi@example.com", 32));
        dataTable.put("user_003", new UserInfo("user_003", "王五", "wangwu@example.com", 25));
        dataTable.put("user_004", new UserInfo("user_004", "赵六", "zhaoliu@example.com", 28));
        dataTable.put("user_005", new UserInfo("user_005", "孙七", "sunqi@example.com", 35));

        /* 全局索引表: 索引列 -> RowKey 映射 (按年龄建索引) */
        NavigableMap<Integer, List<String>> globalIndexTable = new TreeMap<>();
        for (Map.Entry<String, UserInfo> entry : dataTable.entrySet()) {
            int age = entry.getValue().age;
            globalIndexTable.computeIfAbsent(age, k -> new ArrayList<>()).add(entry.getKey());
        }

        System.out.println("  数据表 (RowKey -> 用户信息):");
        dataTable.forEach((rk, user) -> System.out.printf("    %s -> %s%n", rk, user));

        System.out.println("  全局索引表 (年龄 -> RowKey 列表):");
        globalIndexTable.forEach((age, rks) ->
                System.out.printf("    age=%d -> %s%n", age, rks));

        /* 模拟查询: 查年龄为 28 的用户 */
        System.out.println("\n  查询: age=28 的用户 (二次查询流程):");
        int queryAge = 28;
        List<String> matchedRowKeys = globalIndexTable.getOrDefault(queryAge, Collections.emptyList());
        System.out.printf("    第1步: 查索引表 -> 匹配 RowKeys: %s%n", matchedRowKeys);
        System.out.println("    第2步: 根据 RowKey 回查数据表:");
        for (String rk : matchedRowKeys) {
            UserInfo user = dataTable.get(rk);
            System.out.printf("      %s -> %s%n", rk, user);
        }
        System.out.println("  特点: 索引和数据分离, 索引表只存索引列 + RowKey, 查询需二次回表\n");
    }

    /** 用户信息模型 */
    record UserInfo(String rowKey, String name, String email, int age) {}

    /* ===================== 方案2: 本地索引 ===================== */

    /**
     * 本地索引: 索引数据与业务数据存储在同一个 Region 内, 通常在同一个 Column Family。
     *
     * <p>写入: 一次 Put 同时写入数据列和索引列 (原子操作, 无需分布式事务)
     * 查询: 通过 Scan 遍历所有 Region, 读取每个 Region 中的索引列过滤
     *
     * <p>优点: 写入原子性 (同一行事务), 无需二次查询
     * 缺点: 查询需 Scan 所有 Region (因为索引分散), 数据量增大时性能下降
     */
    static void localIndexDemo() {
        System.out.println("--- 方案2: 本地索引 (索引与数据共存) ---");

        /* 模拟两个 Region, 每个 Region 存数据和本地索引 */
        RegionWithLocalIndex region1 = new RegionWithLocalIndex("Region-1");
        region1.put("user_001", new UserInfo("user_001", "张三", "zhangsan@example.com", 28));
        region1.put("user_002", new UserInfo("user_002", "李四", "lisi@example.com", 32));
        region1.put("user_003", new UserInfo("user_003", "王五", "wangwu@example.com", 25));

        RegionWithLocalIndex region2 = new RegionWithLocalIndex("Region-2");
        region2.put("user_004", new UserInfo("user_004", "赵六", "zhaoliu@example.com", 28));
        region2.put("user_005", new UserInfo("user_005", "孙七", "sunqi@example.com", 35));

        List<RegionWithLocalIndex> allRegions = List.of(region1, region2);
        System.out.println("  存储结构 (每个 Region 内共存数据和索引):");
        for (RegionWithLocalIndex region : allRegions) {
            System.out.printf("    %s:%n", region.name);
            region.data.forEach((rk, user) ->
                    System.out.printf("      RowKey=%s, cf:data={name=%s,email=%s}, cf:index={age=%d}%n",
                            rk, user.name, user.email, user.age));
        }

        /* 模拟查询: 查 age=28, 需要 Scan 所有 Region */
        int queryAge = 28;
        System.out.printf("%n  查询: age=%d (需 Scan 全部 %d 个 Region):%n", queryAge, allRegions.size());
        List<UserInfo> results = new ArrayList<>();
        for (RegionWithLocalIndex region : allRegions) {
            System.out.printf("    Scan %s...%n", region.name);
            for (Map.Entry<String, UserInfo> entry : region.data.entrySet()) {
                if (entry.getValue().age == queryAge) {
                    results.add(entry.getValue());
                }
            }
        }
        System.out.println("  查询结果:");
        results.forEach(user -> System.out.printf("    %s%n", user));
        System.out.println("  特点: 写入原子, 但查询需遍历所有 Region, 数据量大时性能差\n");
    }

    /** 本地索引 Region 模拟 */
    static class RegionWithLocalIndex {
        final String name;
        final Map<String, UserInfo> data = new LinkedHashMap<>();

        RegionWithLocalIndex(String name) { this.name = name; }

        void put(String rowKey, UserInfo user) { data.put(rowKey, user); }
    }

    /* ===================== 方案3: 协处理器 (Observer) ===================== */

    /**
     * 协处理器 (Coprocessor) Observer 模式: 在数据表注册 Observer 拦截器,
     * 写入数据时通过 postPut 钩子自动同步更新索引表。
     *
     * <p>HBase 协处理器类似 RDBMS 的 Trigger:
     * RegionObserver: 拦截 Region 级别的操作 (put, get, scan, delete 等)
     * MasterObserver: 拦截 Master 级别的操作 (createTable, deleteTable 等)
     * WALObserver: 拦截 WAL 写入
     * Endpoint: 类似存储过程, 客户端可调用
     *
     * <p>Phoenix 索引本质: 注册 RegionObserver, 在 postPut/postDelete 中
     * 自动维护索引表, 保证数据和索引的最终一致性。
     */
    static void coprocessorIndexDemo() {
        System.out.println("--- 方案3: 协处理器 Observer (数据写入时同步更新索引) ---");

        /* 模拟 HBase 协处理器框架 */
        List<RegionObserver> observers = new ArrayList<>();

        /* 注册索引同步 Observer */
        IndexSyncObserver indexObserver = new IndexSyncObserver();
        observers.add(indexObserver);
        System.out.println("  注册 IndexSyncObserver (实现 RegionObserver 接口)");

        /* 模拟数据表 */
        Map<String, UserInfo> dataTable = new LinkedHashMap<>();

        /* 模拟 Put 操作, Observer 自动拦截 postPut */
        System.out.println("\n  写入数据 (Observer 自动同步索引):");
        String[] rawUsers = {
                "user_001|张三|zhangsan@example.com|28",
                "user_002|李四|lisi@example.com|32",
                "user_003|王五|wangwu@example.com|25",
                "user_004|赵六|zhaoliu@example.com|28",
        };

        for (String raw : rawUsers) {
            String[] parts = raw.split("\\|");
            String rk = parts[0];
            UserInfo user = new UserInfo(rk, parts[1], parts[2], Integer.parseInt(parts[3]));

            /* 写入数据表 */
            dataTable.put(rk, user);

            /* 触发 Observer postPut 钩子 */
            for (RegionObserver observer : observers) {
                observer.postPut(rk, user);
            }
        }

        System.out.println("\n  数据表内容:");
        dataTable.forEach((rk, user) -> System.out.printf("    %s -> %s%n", rk, user));

        System.out.println("\n  索引表内容 (Observer 自动维护):");
        indexObserver.printIndex();

        /* 查询: 通过索引表定位 */
        System.out.println("\n  查询 age=28 (通过索引表, 一次定位):");
        List<String> rkMatches = indexObserver.queryByAge(28);
        System.out.printf("    索引命中 RowKeys: %s%n", rkMatches);
        for (String rk : rkMatches) {
            System.out.printf("    回数据表: %s -> %s%n", rk, dataTable.get(rk));
        }

        System.out.println("  优点: 写入时自动维护索引, 查询只需一次索引定位 + 一次回表");
        System.out.println("  缺点: 写入性能有损耗; 索引一致性依赖 Observer 正确实现\n");
    }

    /** RegionObserver 接口模拟 */
    interface RegionObserver {
        void postPut(String rowKey, UserInfo user);
    }

    /** 索引同步 Observer 实现 */
    static class IndexSyncObserver implements RegionObserver {
        final NavigableMap<Integer, List<String>> ageIndex = new TreeMap<>();
        final Map<String, List<String>> emailIndex = new LinkedHashMap<>();

        @Override
        public void postPut(String rowKey, UserInfo user) {
            /* 按年龄建索引 */
            ageIndex.computeIfAbsent(user.age, k -> new ArrayList<>()).add(rowKey);
            /* 按邮箱前缀建索引 */
            String emailPrefix = user.email.split("@")[0];
            emailIndex.computeIfAbsent(emailPrefix, k -> new ArrayList<>()).add(rowKey);
            System.out.printf("    [Observer] postPut: %s -> 更新 age=%d 索引, email前缀=%s 索引%n",
                    rowKey, user.age, emailPrefix);
        }

        List<String> queryByAge(int age) {
            return ageIndex.getOrDefault(age, Collections.emptyList());
        }

        void printIndex() {
            System.out.println("    年龄索引:");
            ageIndex.forEach((age, rks) ->
                    System.out.printf("      age=%d -> %s%n", age, rks));
            System.out.println("    邮箱前缀索引:");
            emailIndex.forEach((prefix, rks) ->
                    System.out.printf("      prefix=%s -> %s%n", prefix, rks));
        }
    }

    /* ===================== Phoenix 协处理器框架概念 ===================== */

    /**
     * Apache Phoenix 协处理器框架概念: Phoenix 本质上是 HBase 之上的 SQL 层,
     * 通过协处理器实现二级索引、聚合、排序等能力。
     *
     * <p>核心机制:
     * 1. Phoenix 建索引时, 自动创建索引表 + 注册 Indexer 协处理器到数据表
     * 2. 数据写入时, Indexer (RegionObserver) 截获 Put/Delete, 同步写入索引表
     * 3. 查询时, QueryOptimizer 分析 SQL, 决定走哪个索引, 生成对应 Scan 计划
     * 4. 索引重建: 通过 MapReduce 全量扫描数据表, 重建索引表
     */
    static void phoenixConceptDemo() {
        System.out.println("--- Phoenix 协处理器框架概念 ---");

        System.out.println("  Phoenix = SQL Parser + 协处理器框架 + 查询优化器");
        System.out.println();
        System.out.println("  建索引流程:");
        System.out.println("    1. CREATE INDEX idx_age ON users(age)");
        System.out.println("    2. Phoenix 自动创建索引表: USERS_IDX_AGE (RowKey=age+原RowKey)");
        System.out.println("    3. 注册 Indexer RegionObserver 到 USERS 表的所有 Region");
        System.out.println();
        System.out.println("  写入流程 (含索引同步):");
        System.out.println("    1. UPSERT INTO users VALUES ('user_001', '张三', 28)");
        System.out.println("    2. Phoenix 生成 Put 写入 USERS 表");
        System.out.println("    3. Indexer.postPut() 触发: 生成索引 Put 写入 USERS_IDX_AGE 表");
        System.out.println("    4. 两表写入在同一 RegionServer 内 (本地索引), 或跨 RegionServer (全局索引)");
        System.out.println();
        System.out.println("  查询流程:");
        System.out.println("    1. SELECT * FROM users WHERE age = 28");
        System.out.println("    2. QueryOptimizer 分析: 命中 idx_age 索引");
        System.out.println("    3. 生成 Scan: 扫描 USERS_IDX_AGE 表, startRow='28', stopRow='28\\xFF'");
        System.out.println("    4. 获取匹配 RowKey 列表, 回查 USERS 表获取完整数据");
        System.out.println();
        System.out.println("  全球索引 vs 本地索引:");
        System.out.println("    全球索引 (GLOBAL): 索引表独立, 读快写慢 (需要跨 RegionServer 写)");
        System.out.println("    本地索引 (LOCAL): 索引与数据同 Region, 写快读慢 (需要扫描所有 Region)");
        System.out.println("    覆盖索引 (COVERED): 索引包含查询所需全部列, 无需回表");
        System.out.println();
        System.out.println("  协处理器能力总结:");
        System.out.println("    RegionObserver: 拦截数据操作, 实现索引同步、权限校验、审计日志");
        System.out.println("    MasterObserver: 拦截 DDL 操作, 实现表创建校验、自动化管理");
        System.out.println("    Endpoint: 服务端计算, 实现聚合 COUNT/SUM/AVG, 避免数据传输");
        System.out.println("    CoprocessorEnvironment: 提供 Region/Master 上下文, 可访问 HBase 内部 API\n");
    }
}