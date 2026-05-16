package base.distributed;

import java.util.*;

/**
 * CAP 定理与 BASE 理论演示。
 *
 * <h3>CAP 定理（布鲁尔定理）</h3>
 * <pre>
 *   C (Consistency)：一致性 —— 所有节点同一时刻看到相同数据
 *   A (Availability)：可用性 —— 每个请求都能得到非错误的响应
 *   P (Partition Tolerance)：分区容错性 —— 网络分区发生时系统仍能工作
 *
 *   核心结论：P 在网络环境中必选 → C 和 A 只能二选一
 * </pre>
 *
 * <h3>注册中心对比</h3>
 * <table border="1">
 *   <tr><th>组件</th><th>模型</th><th>说明</th></tr>
 *   <tr><td>Eureka</td><td>AP</td><td>优先保证可用性；节点故障不剔除，自我保护模式</td></tr>
 *   <tr><td>ZooKeeper</td><td>CP</td><td>优先保证一致性；Leader 选举期间集群不可用</td></tr>
 *   <tr><td>Nacos</td><td>AP+CP</td><td>双模式切换：默认 AP，可配置为 CP（Raft 协议）</td></tr>
 * </table>
 *
 * <h3>BASE 理论（对 CAP 的权衡）</h3>
 * <pre>
 *   BA (Basically Available) ：基本可用 —— 允许部分故障下损失部分可用性
 *   S  (Soft state)          ：软状态 —— 允许系统存在中间状态，无需强一致
 *   E  (Eventually consistent)：最终一致 —— 经过一定时间后所有副本达到一致
 * </pre>
 */
public class CAPBaseDemo {

    /* =========================================================================
     * 1. CAP 模型模拟
     * ========================================================================= */

    /** 通用节点模型 */
    static class Node {
        final String name;
        int version = 0;
        boolean online = true;

        Node(String name) { this.name = name; }

        void setVersion(int v) { this.version = v; }
    }

    /* -------------------------------------------------------------------------
     * CP 系统（ZooKeeper 风格）：发生分区时牺牲可用性，保证一致性
     * ------------------------------------------------------------------------- */
    static class CPSystem {
        private final List<Node> nodes;
        private Node leader;

        CPSystem(int count) {
            nodes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                nodes.add(new Node("CP-" + (i + 1)));
            }
            leader = nodes.get(0);
            System.out.println("[CP] 系统启动，Leader = " + leader.name);
        }

        /** CP 写入：必须过半节点确认（Raft 协议） */
        String write(int data) {
            if (leader == null) {
                System.out.println("[CP-WRITE] 无 Leader（选举中），拒绝写入 → 牺牲 A 保 C");
                return "REJECTED: 集群选举中";
            }
            int quorum = nodes.size() / 2 + 1;
            int ackCount = 0;
            for (Node node : nodes) {
                if (node.online) {
                    node.setVersion(data);
                    ackCount++;
                }
            }
            if (ackCount >= quorum) {
                System.out.printf("[CP-WRITE] 过半确认 (%d/%d), 写入 v%d 成功%n", ackCount, nodes.size(), data);
                return "OK: v" + data;
            }
            System.out.printf("[CP-WRITE] 未过半 (%d/%d), 拒绝写入%n", ackCount, nodes.size());
            return "REJECTED: 未达法定人数";
        }

        /** CP 读取：必须读到最新一致数据 */
        String read() {
            if (leader == null) {
                System.out.println("[CP-READ] 无 Leader，拒绝读取 → 牺牲 A 保 C");
                return "REJECTED";
            }
            System.out.printf("[CP-READ]  Leader=%s, v=%d (强一致)%n", leader.name, leader.version);
            return "v" + leader.version;
        }
    }

    /* -------------------------------------------------------------------------
     * AP 系统（Eureka 风格）：发生分区时牺牲一致性，保证可用性
     * ------------------------------------------------------------------------- */
    static class APSystem {
        private final List<Node> nodes;
        private final Map<String, Integer> versions = new HashMap<>();

        APSystem(int count) {
            nodes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Node node = new Node("AP-" + (i + 1));
                nodes.add(node);
                versions.put(node.name, 0);
            }
        }

        /** AP 写入：任意节点写入即成功（可能不一致） */
        String write(String nodeName, int data) {
            versions.put(nodeName, data);
            System.out.printf("[AP-WRITE] %s 写入 v%d (可能不同步)%n", nodeName, data);
            return "OK: " + nodeName + " v" + data;
        }

        /** AP 读取：总是返回，但可能读到过期数据 */
        String read(String nodeName) {
            int v = versions.getOrDefault(nodeName, 0);
            System.out.printf("[AP-READ]  %s 返回 v%d (可能不是最新)%n", nodeName, v);
            return nodeName + " = v" + v;
        }
    }

    /* -------------------------------------------------------------------------
     * Nacos 双模式：AP + CP
     * ------------------------------------------------------------------------- */
    static class NacosDualMode {
        private boolean cpMode = false; // 默认 AP

        void switchToAP() {
            cpMode = false;
            System.out.println("[Nacos] 切换至 AP 模式（默认）");
        }

        void switchToCP() {
            cpMode = true;
            System.out.println("[Nacos] 切换至 CP 模式（Raft 协议）");
        }

        String query() {
            if (cpMode) {
                System.out.println("[Nacos-CP] 强一致查询");
                return "CP: v=3";
            } else {
                System.out.println("[Nacos-AP] 最终一致查询");
                return "AP: v≈3";
            }
        }
    }

    /* =========================================================================
     * 2. BASE 理论演示
     * ========================================================================= */

    /** 模拟一个最终一致的数据同步过程 */
    static class EventualConsistencyDemo {
        private final Map<String, Integer> nodeData = new LinkedHashMap<>();
        private final Map<String, Long> lastSyncTime = new LinkedHashMap<>();

        EventualConsistencyDemo() {
            nodeData.put("node-A", 100);
            nodeData.put("node-B", 100);
            lastSyncTime.put("node-A", 0L);
            lastSyncTime.put("node-B", 0L);
        }

        /** 写入 node-A（BA: 基本可用 —— 写单节点即返回） */
        void write(int value) {
            nodeData.put("node-A", value);
            lastSyncTime.put("node-A", System.currentTimeMillis());
            System.out.printf("[BASE-WRITE] node-A v=%d (Soft State: node-B 延迟)%n", value);
        }

        /** 读取（可能读到不一致的数据 —— Soft State） */
        void readAll() {
            nodeData.forEach((node, data) ->
                System.out.printf("[BASE-READ]  %s = %d%n", node, data)
            );
        }

        /** 模拟异步同步：一段时间后 node-B 追上 node-A（Eventually Consistent） */
        void simulateSync() {
            System.out.println("[BASE-SYNC] 异步同步中...");
            // node-B 追上 node-A
            Integer masterValue = nodeData.get("node-A");
            nodeData.put("node-B", masterValue);
            lastSyncTime.put("node-B", System.currentTimeMillis());
            System.out.printf("[BASE-SYNC] node-B v=%d (最终一致达成!)%n", masterValue);
        }
    }

    /* =========================================================================
     * main
     * ========================================================================= */

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("1. CAP 定理 —— CP vs AP");
        System.out.println("=".repeat(60));

        System.out.println("\n--- CP 系统（ZooKeeper 风格）---");
        {
            CPSystem cp = new CPSystem(3);
            System.out.println(cp.write(42));
            System.out.println(cp.read());
        }

        System.out.println("\n--- AP 系统（Eureka 风格）---");
        {
            APSystem ap = new APSystem(3);
            ap.write("AP-1", 10);
            ap.write("AP-2", 20);     // 不同节点不一致
            ap.read("AP-1");           // v10
            ap.read("AP-2");           // v20 — 不一致！
            System.out.println("  NOTE: AP-1 和 AP-2 数据不一致，但都可用");
        }

        System.out.println("\n--- Nacos 双模式 ---");
        {
            NacosDualMode nacos = new NacosDualMode();
            System.out.println(nacos.query()); // AP
            nacos.switchToCP();
            System.out.println(nacos.query()); // CP
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("2. BASE 理论 —— 基本可用 + 软状态 + 最终一致");
        System.out.println("=".repeat(60));
        {
            EventualConsistencyDemo base = new EventualConsistencyDemo();
            System.out.println("--- 初始状态 ---");
            base.readAll();

            System.out.println("\n--- BA: 写入单个节点即返回 ---");
            base.write(200);

            System.out.println("\n--- S(Soft State): 读取可能不一致 ---");
            base.readAll();

            System.out.println("\n--- E(Eventually Consistent): 异步同步后一致 ---");
            base.simulateSync();
            base.readAll();
        }
    }
}