package base.db.interview;

import java.util.*;

/**
 * 面试题: 分库分表平滑扩容 -- 一致性哈希 vs 虚拟槽 vs 倍数扩容 vs 停机迁移 + 全局唯一 ID 方案对比
 * 一致性哈希 + 虚拟节点: 扩缩容时仅迁移约 1/N 数据, 缓存/NoSQL 扩容首选
 * 虚拟槽 (Redis Cluster): 16384 槽, 按槽迁移精确可控, 节点匹配后自动感知
 * 倍数扩容: 2->4->8, 约 50% 数据迁移, 需推全量数据, MySQL 分库分表常用
 * 停机迁移: 简单无风险, 但需停服, 适合数据量 <100GB
 * 全局 ID: 雪花算法 (本地生成, 趋势递增) 是分布式场景最优解
 */
public class Q02_Sharding_Expansion {

    /**
     * 一致性哈希模拟器 -- 虚拟节点 + FNV1a 哈希,
     * 通过新旧哈希环对比计算数据迁移比例
     */
    static class ConsistentHashSimulator {
        static final int VIRTUAL_NODES = 3;
        static final int HASH_SPACE = 360;

        static class Node {
            String name;
            int position;

            Node(String name, int position) {
                this.name = name;
                this.position = position;
            }
        }

        static List<Node> buildRing(int nodeCount) {
            List<Node> ring = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                for (int v = 0; v < VIRTUAL_NODES; v++) {
                    ring.add(new Node("node_" + i, (i * 127 + v * 31 + 7) % HASH_SPACE));
                }
            }
            ring.sort((a, b) -> Integer.compare(a.position, b.position));
            return ring;
        }

        static String route(int dataKey, List<Node> ring) {
            int hash = (dataKey * 31 + 17) % HASH_SPACE;
            for (Node node : ring) {
                if (node.position >= hash) return node.name;
            }
            return ring.get(0).name;
        }

        static Map<String, Integer> calculateMigration(int dataCount, List<Node> oldRing, List<Node> newRing) {
            Map<String, Integer> migration = new LinkedHashMap<>();
            int totalMigrated = 0;
            for (int i = 0; i < dataCount; i++) {
                String oldNode = route(i, oldRing);
                String newNode = route(i, newRing);
                if (!oldNode.equals(newNode)) {
                    migration.merge(oldNode + "->" + newNode, 1, Integer::sum);
                    totalMigrated++;
                }
            }
            System.out.printf("总数据 %d 条, 迁移 %d 条 (%.1f%%)%n",
                    dataCount, totalMigrated, 100.0 * totalMigrated / dataCount);
            return migration;
        }
    }

    /** 虚拟槽扩容模拟 -- Redis Cluster 16384 槽分配, 扩容时只迁移槽对应的数据 */
    static class VirtualSlotSimulator {
        static final int SLOTS = 16384;

        static Map<String, List<Integer>> assignSlots(int nodeCount) {
            Map<String, List<Integer>> assignments = new LinkedHashMap<>();
            int slotsPerNode = SLOTS / nodeCount;
            for (int i = 0; i < nodeCount; i++) {
                int start = i * slotsPerNode;
                int end = (i == nodeCount - 1) ? SLOTS : start + slotsPerNode;
                List<Integer> slotList = new ArrayList<>();
                for (int s = start; s < end; s++) slotList.add(s);
                assignments.put("node_" + i, slotList);
            }
            return assignments;
        }

        static void simulateExpansion() {
            System.out.println("\n--- 虚拟槽扩容模拟 (Redis Cluster 方案) ---");
            Map<String, List<Integer>> slots3 = assignSlots(3);
            Map<String, List<Integer>> slots4 = assignSlots(4);

            System.out.println("3 节点时槽分布:");
            for (Map.Entry<String, List<Integer>> e : slots3.entrySet()) {
                System.out.printf("  %s: 槽 %d-%d (%d 个)%n", e.getKey(),
                        e.getValue().get(0), e.getValue().get(e.getValue().size() - 1),
                        e.getValue().size());
            }

            System.out.println("\n扩容到 4 节点时槽分布:");
            for (Map.Entry<String, List<Integer>> e : slots4.entrySet()) {
                System.out.printf("  %s: 槽 %d-%d (%d 个)%n", e.getKey(),
                        e.getValue().get(0), e.getValue().get(e.getValue().size() - 1),
                        e.getValue().size());
            }
            System.out.println("特点: 只需迁移槽对应的数据，迁移量精确可控");
        }
    }

    /** 倍数扩容模拟 -- 2->4->8 逐步扩容, 计算各阶段数据迁移比例 */
    static class DoublingExpansionSimulator {
        static void simulate() {
            System.out.println("\n--- 倍数扩容模拟 ---");

            int[][] stages = {
                    {2, 4},   // 2->4
                    {4, 8},   // 4->8
                    {8, 16},  // 8->16
            };

            for (int[] stage : stages) {
                int oldCount = stage[0];
                int newCount = stage[1];
                int totalData = 100000;

                int migratedFromOld = 0;
                for (int i = 0; i < totalData; i++) {
                    int oldShard = i % oldCount;
                    int newShard = i % newCount;
                    if (oldShard != newShard && oldShard < oldCount) {
                        migratedFromOld++;
                    }
                }

                String oldFmt = String.join(", ", Collections.nCopies(oldCount / 2, "db"));
                String newFmt = String.join(", ", Collections.nCopies(newCount / 2, "db"));

                System.out.printf("%s(%d库) -> %s(%d库): 迁移 %.1f%% 数据%n",
                        oldFmt, oldCount, newFmt, newCount,
                        100.0 * migratedFromOld / totalData);
            }
            System.out.println("倍数扩容特点: 50% 数据不变，老节点数据减半，但需推全量数据");
        }
    }

    /** 停机迁移方案 -- 步骤清单, 优点/缺点/适用场景 */
    static class DowntimeMigrationSimulator {
        static void simulate() {
            System.out.println("\n--- 停机迁移模拟 ---");
            System.out.println("步骤:");
            System.out.println("  1. 发布停机公告");
            System.out.println("  2. 停止写入，等现有请求完成");
            System.out.println("  3. 导出全量数据 (mysqldump/DTS)");
            System.out.println("  4. 按新分片规则重新导入数据");
            System.out.println("  5. 切换应用配置到新分片集群");
            System.out.println("  6. 恢复服务");
            System.out.println();
            System.out.println("优点: 实现简单，无数据一致性风险");
            System.out.println("缺点: 需要停机，大数据量耗时长");
            System.out.println("适用: 业务允许停机窗口(凌晨)、数据量 < 100GB");
        }
    }

    /** 全局唯一 ID 方案对比 -- 雪花算法/UUID/号段/Redis/ZK/美团 Leaf 六种方案 */
    static class IDGeneratorComparer {
        static void compare() {
            System.out.println("\n=== 全局唯一 ID 方案对比 ===");

            class IDScheme {
                String name;
                String uniqueness;
                String increment;
                String dependency;
                String performance;
                String cons;

                IDScheme(String name, String uniqueness, String increment, String dependency,
                         String performance, String cons) {
                    this.name = name;
                    this.uniqueness = uniqueness;
                    this.increment = increment;
                    this.dependency = dependency;
                    this.performance = performance;
                    this.cons = cons;
                }
            }

            List<IDScheme> schemes = List.of(
                    new IDScheme("雪花算法", "全局唯一", "趋势递增", "无外部依赖",
                            "极高(本地生成)", "时钟回拨问题"),
                    new IDScheme("UUID", "全局唯一", "无序/随机", "无外部依赖",
                            "极高", "索引不友好, 占空间大"),
                    new IDScheme("号段模式", "全局唯一", "递增", "依赖DB",
                            "高(号段用完才访DB)", "DB 单点, 需主从"),
                    new IDScheme("Redis自增", "全局唯一", "递增", "依赖Redis",
                            "高", "Redis 故障丢失, 需持久化"),
                    new IDScheme("ZK 顺序节点", "全局唯一", "递增", "依赖ZK集群",
                            "中", "Znode 限制, 性能瓶颈"),
                    new IDScheme("美团 Leaf", "全局唯一", "趋势递增",
                            "依赖DB+ZK", "极高", "架构复杂, 需分段")
            );

            String fmt = "| %-14s | %-10s | %-10s | %-12s | %-18s | %-22s |%n";
            System.out.printf(fmt, "方案", "唯一性", "递增性", "外部依赖", "性能", "主要缺点");
            System.out.println("|----------------|------------|------------|--------------|--------------------|------------------------|");
            for (IDScheme s : schemes) {
                System.out.printf(fmt, s.name, s.uniqueness, s.increment, s.dependency,
                        s.performance, s.cons);
            }
        }
    }

    static void printExpansionComparisonTable() {
        System.out.println("=== 分库分表平滑扩容方案对比 ===");

        class ExpansionScheme {
            String name;
            String migrationRatio;
            String complexity;
            String downtime;
            String autoBalancing;
            String applicable;

            ExpansionScheme(String name, String migrationRatio, String complexity,
                            String downtime, String autoBalancing, String applicable) {
                this.name = name;
                this.migrationRatio = migrationRatio;
                this.complexity = complexity;
                this.downtime = downtime;
                this.autoBalancing = autoBalancing;
                this.applicable = applicable;
            }
        }

        List<ExpansionScheme> schemes = List.of(
                new ExpansionScheme("一致性哈希", "约 1/N 数据迁移", "中", "无需停机",
                        "是(虚拟节点)", "缓存/NoSQL 扩容"),
                new ExpansionScheme("虚拟槽(Redis)", "按槽迁移,可控", "较高", "无需停机",
                        "是(槽迁移)", "Redis Cluster"),
                new ExpansionScheme("倍数扩容", "约 50% 数据迁移", "高(需推全量)", "可在线",
                        "否(需手动平衡)", "MySQL 分库分表"),
                new ExpansionScheme("停机迁移", "100% 数据迁移", "低", "需停机",
                        "否", "小数据量/允许停机")
        );

        String fmt = "| %-14s | %-18s | %-10s | %-10s | %-16s | %-20s |%n";
        System.out.printf(fmt, "方案", "迁移比例", "复杂度", "是否停机", "自动均衡", "适用场景");
        System.out.println("|----------------|--------------------|------------|------------|------------------|----------------------|");
        for (ExpansionScheme s : schemes) {
            System.out.printf(fmt, s.name, s.migrationRatio, s.complexity, s.downtime,
                    s.autoBalancing, s.applicable);
        }
    }

    static void demoConsistentHash() {
        System.out.println("\n=== 一致性哈希扩容模拟 ===");

        List<ConsistentHashSimulator.Node> ring3 = ConsistentHashSimulator.buildRing(3);
        List<ConsistentHashSimulator.Node> ring4 = ConsistentHashSimulator.buildRing(4);

        System.out.println("3节点哈希环 (" + ring3.size() + " 个虚拟节点):");
        for (ConsistentHashSimulator.Node n : ring3) {
            System.out.printf("  pos=%d -> %s%n", n.position, n.name);
        }

        System.out.println("\n4节点哈希环 (" + ring4.size() + " 个虚拟节点):");
        for (ConsistentHashSimulator.Node n : ring4) {
            System.out.printf("  pos=%d -> %s%n", n.position, n.name);
        }

        ConsistentHashSimulator.calculateMigration(500, ring3, ring4);
    }

    public static void main(String[] args) {
        printExpansionComparisonTable();
        demoConsistentHash();
        VirtualSlotSimulator.simulateExpansion();
        DoublingExpansionSimulator.simulate();
        DowntimeMigrationSimulator.simulate();
        IDGeneratorComparer.compare();
    }
}