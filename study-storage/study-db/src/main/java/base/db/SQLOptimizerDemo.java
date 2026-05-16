package base.db;

import java.util.*;

/**
 * MySQL 优化器原理模拟: 基于代价的优化器 CBO + EXPLAIN 输出 + Optimizer Trace + Order By Limit 优化
 * CBO: 估算全表扫描 cost vs 索引扫描 cost, 选低代价方案
 * EXPLAIN: 核心字段 type(访问类型)、key(使用的索引)、Extra(Using index/filesort)
 * Optimizer Trace: JSON 跟踪优化器决策过程, 分析为什么选了某个索引
 * Order By + Limit: 有索引直接取前 N 条, 无索引需全量排序 (filesort)
 */
public class SQLOptimizerDemo {

    /**
     * 基于代价的优化器简版 -- 全表扫描代价 = IO页 + CPU行成本,
     * 索引扫描代价 = 索引IO + 回表IO + CPU行成本
     */
    static class CostBasedOptimizer {
        static class TableStats {
            String name;
            long rowCount;
            double dataLength;
            double indexLength;

            TableStats(String name, long rowCount, double dataLength, double indexLength) {
                this.name = name;
                this.rowCount = rowCount;
                this.dataLength = dataLength;
                this.indexLength = indexLength;
            }
        }

        static class ExecutionPlan {
            String accessType;
            String table;
            double estimatedCost;
            long estimatedRows;

            ExecutionPlan(String accessType, String table, double estimatedCost, long estimatedRows) {
                this.accessType = accessType;
                this.table = table;
                this.estimatedCost = estimatedCost;
                this.estimatedRows = estimatedRows;
            }

            @Override
            public String toString() {
                return String.format("access_type=%-8s table=%-15s cost=%-8.2f rows=%d",
                        accessType, table, estimatedCost, estimatedRows);
            }
        }

        static double estimateFullScanCost(TableStats stats) {
            double ioCost = stats.dataLength / 16384.0;
            double cpuCost = stats.rowCount * 0.2;
            return ioCost + cpuCost;
        }

        static double estimateIndexRangeCost(TableStats stats, double selectivity) {
            long matchingRows = (long) (stats.rowCount * selectivity);
            double ioCost = (stats.indexLength / 16384.0) * selectivity + (matchingRows * 1.0);
            double cpuCost = matchingRows * 0.2;
            return ioCost + cpuCost;
        }

        static List<ExecutionPlan> comparePlans(TableStats stats, double selectivity) {
            List<ExecutionPlan> plans = new ArrayList<>();
            double fullScan = estimateFullScanCost(stats);
            double indexScan = estimateIndexRangeCost(stats, selectivity);

            plans.add(new ExecutionPlan("ALL", stats.name,
                    fullScan, stats.rowCount));
            plans.add(new ExecutionPlan("range", stats.name,
                    indexScan, (long) (stats.rowCount * selectivity)));

            return plans;
        }
    }

    /**
     * EXPLAIN 输出模拟器 -- 针对不同 SQL 场景输出对应的 type/key/Extra,
     * 覆盖 const/eq_ref/ref/range/index/ALL 等关键访问类型
     */
    static class ExplainSimulator {
        static final String[] ACCESS_TYPES = {
                "system", "const", "eq_ref", "ref", "range", "index", "ALL"
        };

        static class ExplainResult {
            String id;
            String selectType;
            String table;
            String type;
            String possibleKeys;
            String key;
            String rows;
            String extra;

            ExplainResult(String type, String table, String key, String rows, String extra) {
                this.type = type;
                this.table = table;
                this.key = key;
                this.rows = rows;
                this.extra = extra;
            }

            @Override
            public String toString() {
                return String.format(
                        "| %-4s | %-10s | %-8s | %-15s | %-6s | %-30s |",
                        "1", "SIMPLE", table, type, rows, extra);
            }
        }

        static void printHeader() {
            System.out.println("| id   | table      | type      | key             | rows   | Extra                         |");
            System.out.println("|------|------------|-----------|-----------------|--------|-------------------------------|");
        }

        static void simulate(String scenario) {
            List<ExplainResult> results = new ArrayList<>();
            switch (scenario) {
                case "pk" -> results.add(new ExplainResult("const", "users",
                        "PRIMARY", "1", ""));
                case "uk" -> results.add(new ExplainResult("const", "users",
                        "uk_email", "1", ""));
                case "join_pk" -> {
                    results.add(new ExplainResult("ALL", "orders",
                            null, "1000", "Using where"));
                    results.add(new ExplainResult("eq_ref", "users",
                            "PRIMARY", "1", ""));
                }
                case "ref" -> results.add(new ExplainResult("ref", "users",
                        "idx_name", "5", "Using index condition"));
                case "range" -> results.add(new ExplainResult("range", "users",
                        "idx_age", "200", "Using index condition"));
                case "index" -> results.add(new ExplainResult("index", "users",
                        "idx_name_age", "1000", "Using index"));
                case "all" -> results.add(new ExplainResult("ALL", "users",
                        null, "1000", "Using where; Using filesort"));
            }
            for (ExplainResult r : results) {
                System.out.println(r);
            }
        }
    }

    /**
     * Optimizer Trace 模拟 -- JSON 格式展示优化器如何评估候选索引并选择最优方案,
     * 包含 rows_estimation、potential_range_indexes、best_covering_index 等关键步骤
     */
    static class OptimizerTraceSimulator {
        static void simulateTrace() {
            System.out.println("=== MySQL Optimizer Trace JSON 模拟 ===");
            String trace = """
                    {
                      "steps": [
                        {
                          "join_preparation": {
                            "select#": 1,
                            "steps": [{"expanded_query": "SELECT * FROM orders WHERE user_id=1 AND status='paid'"}]
                          }
                        },
                        {
                          "join_optimization": {
                            "select#": 1,
                            "steps": [
                              {"condition_processing": {"condition": "WHERE", "original_condition": "user_id=1 AND status='paid'"}},
                              {"ref_optimizer_key_uses": [
                                {"table": "orders", "field": "user_id", "equals": "1", "null_rejecting": true},
                                {"table": "orders", "field": "status", "equals": "paid", "null_rejecting": true}
                              ]},
                              {"rows_estimation": [
                                {"table": "orders", "range_analysis": {
                                  "table_scan": {"rows": 100000, "cost": 20300},
                                  "potential_range_indexes": [
                                    {"index": "idx_user_id", "usable": true, "key_parts": ["user_id"]},
                                    {"index": "idx_status", "usable": true, "key_parts": ["status"]},
                                    {"index": "idx_user_status", "usable": true, "key_parts": ["user_id", "status"]}
                                  ]},
                                  "best_covering_index": {"index": "idx_user_status", "cost": 2.5, "rows": 50},
                                  "chosen": true
                                }}
                              ]}
                            ]
                          }
                        }
                      ]
                    }""";
            System.out.println(trace);
            System.out.println("\n解析: 优化器评估了 idx_user_id(cost高), idx_status(cost高), idx_user_status(cost低)");
            System.out.println("最终选择 idx_user_status(user_id,status) 联合索引，扫描50行");
        }
    }

    /**
     * Order By + Limit 优化对比 -- 无索引走 filesort (O(NlogN) + O(N)),
     * 有索引直接从 B+Tree 叶子链表取前 N 条 (O(logN) + O(N))
     */
    static class OrderByLimitOptimizer {
        static void simulateFilesortVsIndex() {
            System.out.println("\n--- Order By + Limit 优化对比 ---");

            System.out.println("场景1: SELECT * FROM orders ORDER BY create_time DESC LIMIT 10");
            System.out.println("  无索引: Extra=Using filesort, 需全表扫描100万行排序取前10");
            System.out.println("  代价: O(N log N) 排序 + O(N) 扫描");

            System.out.println("\n场景2: ALTER TABLE orders ADD INDEX idx_ct(create_time)");
            System.out.println("  SELECT * FROM orders ORDER BY create_time DESC LIMIT 10");
            System.out.println("  有索引: Extra=Using index, 直接从索引最后取10条");
            System.out.println("  代价: O(log N) 定位 + O(10) 读取");

            System.out.println("\n场景3: SELECT * FROM orders WHERE status='done' ORDER BY create_time DESC LIMIT 10");
            System.out.println("  单列idx_status: filesort (status过滤后仍需排序)");
            System.out.println("  联合索引 idx_status_ct(status, create_time): 避免filesort, 索引有序直接取");
        }
    }

    static void demoCostBasedOptimizer() {
        System.out.println("=== 1. 简易代价估算器 (CBO) ===");
        CostBasedOptimizer.TableStats userTable = new CostBasedOptimizer.TableStats(
                "users", 100000, 16384.0 * 100, 16384.0 * 10);

        System.out.printf("表 %s: %d 行, 数据 %.0f 页, 索引 %.0f 页%n",
                userTable.name, userTable.rowCount,
                userTable.dataLength / 16384.0, userTable.indexLength / 16384.0);

        System.out.println("\n查询: SELECT * FROM users WHERE age=25 (假设 1% 选择性)");
        List<CostBasedOptimizer.ExecutionPlan> plans =
                CostBasedOptimizer.comparePlans(userTable, 0.01);

        for (CostBasedOptimizer.ExecutionPlan plan : plans) {
            System.out.println("  " + plan);
        }

        double fullCost = CostBasedOptimizer.estimateFullScanCost(userTable);
        double idxCost = CostBasedOptimizer.estimateIndexRangeCost(userTable, 0.01);
        System.out.printf("优化器决策: %s (cost %.2f < %.2f)%n",
                idxCost < fullCost ? "走索引" : "全表扫描", Math.min(fullCost, idxCost),
                Math.max(fullCost, idxCost));
    }

    static void demoExplain() {
        System.out.println("\n=== 2. EXPLAIN 输出简化模拟 ===");

        String[] scenarios = {"pk", "uk", "join_pk", "ref", "range", "index", "all"};
        String[] labels = {"PRIMARY KEY 等值", "UNIQUE KEY 等值", "JOIN 关联主键",
                "普通索引等值(ref)", "索引范围(range)", "全索引扫描(index)", "全表扫描(ALL)"};

        for (int i = 0; i < scenarios.length; i++) {
            System.out.println("\n[" + labels[i] + "]");
            ExplainSimulator.printHeader();
            ExplainSimulator.simulate(scenarios[i]);
        }

        System.out.println("\n访问类型从优到劣: system > const > eq_ref > ref > range > index > ALL");
    }

    static void demoOptimizerTrace() {
        System.out.println("\n=== 3. MySQL 优化器追踪模拟 ===");
        OptimizerTraceSimulator.simulateTrace();
    }

    static void demoOrderByLimit() {
        System.out.println("\n=== 4. Order By + Limit 优化演示 ===");
        OrderByLimitOptimizer.simulateFilesortVsIndex();
    }

    public static void main(String[] args) {
        demoCostBasedOptimizer();
        demoExplain();
        demoOptimizerTrace();
        demoOrderByLimit();
    }
}