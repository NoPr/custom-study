package base.distributed;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式数据一致性方案演示。
 *
 * <h3>一致性级别</h3>
 * <ul>
 *   <li><b>强一致性（Strong）</b>：2PC / Paxos / Raft —— 写入后立即对所有节点可见</li>
 *   <li><b>最终一致性（Eventually）</b>：Gossip 协议 / MQ 异步同步 / 定时对账</li>
 * </ul>
 *
 * <h3>读写分离延迟处理</h3>
 * <ul>
 *   <li><b>延迟双删</b>：写 DB → 删缓存 → 延迟 N ms → 再删缓存</li>
 *   <li><b>Canal + Binlog</b>：监控 MySQL binlog → 异步更新缓存/ES（准实时）</li>
 * </ul>
 *
 * <h3>幂等性方案</h3>
 * <ul>
 *   <li><b>唯一索引/唯一约束</b>：业务唯一键，数据库层面防重</li>
 *   <li><b>Token 机制</b>：先获取 Token → 提交时校验Token并删除</li>
 *   <li><b>状态机</b>：业务状态流转控制，已到终态则拒绝操作</li>
 * </ul>
 */
public class ConsistencyDemo {

    /* =========================================================================
     * 1. 强一致性模拟 —— 类 Paxos 写入等待多数节点确认
     * ========================================================================= */

    static class StrongConsistencySimulator {
        private final Map<Integer, Integer> replicas = new ConcurrentHashMap<>();

        StrongConsistencySimulator(int replicaCount) {
            for (int i = 1; i <= replicaCount; i++) {
                replicas.put(i, 0); // 初始值
            }
        }

        /**
         * 强一致写入：必须过半副本确认。
         * write quorum = W > N/2, read quorum = R > N/2, W + R > N
         */
        boolean strongWrite(int key, int value, int writeQuorum) {
            int ackCount = 0;
            for (int i = 1; i <= replicas.size(); i++) {
                replicas.put(i, value);
                ackCount++;
                if (ackCount >= writeQuorum) break;
            }
            if (ackCount >= writeQuorum) {
                System.out.printf("[强一致] 写入 v=%d, %d/%d 副本确认 → 成功%n",
                    value, ackCount, replicas.size());
                return true;
            }
            System.out.printf("[强一致] 写入失败, 仅 %d/%d 确认%n", ackCount, replicas.size());
            return false;
        }

        int strongRead(int readQuorum) {
            // 读也需要 quorum 确认
            Map<Integer, Integer> values = new HashMap<>();
            for (int i = 1; i <= Math.min(readQuorum, replicas.size()); i++) {
                values.put(i, replicas.get(i));
            }
            System.out.printf("[强一致] 读取 %d 副本 → %s%n", readQuorum, values);
            return values.isEmpty() ? -1 : values.values().iterator().next();
        }
    }

    /* =========================================================================
     * 2. 最终一致性模拟 —— Gossip 协议
     * ========================================================================= */

    static class GossipSimulator {
        static class GossipNode {
            final String name;
            int version;
            Map<String, Integer> data = new ConcurrentHashMap<>();

            GossipNode(String name) {
                this.name = name;
                this.data.put(name, 0);
            }

            void update(int value) {
                this.version++;
                this.data.put(name, value);
                System.out.printf("[Gossip] %s 本地更新 v%d (version=%d)%n", name, value, version);
            }

            /** 与另一个节点交换数据：版本号高的覆盖低的 */
            void gossipWith(GossipNode peer) {
                if (this.version > peer.version) {
                    peer.data.put(this.name, this.data.get(this.name));
                    peer.version = this.version;
                    System.out.printf("[Gossip] %s → %s 同步 v%d%n", this.name, peer.name, this.data.get(this.name));
                } else if (peer.version > this.version) {
                    this.data.put(peer.name, peer.data.get(peer.name));
                    this.version = peer.version;
                    System.out.printf("[Gossip] %s ← %s 同步 v%d%n", this.name, peer.name, peer.data.get(peer.name));
                }
            }
        }

        private final List<GossipNode> nodes = new ArrayList<>();

        void addNode(GossipNode node) { nodes.add(node); }

        /** 随机选择两个节点做 gossip 交换（实际是周期性随机选择） */
        void gossipRound() {
            if (nodes.size() < 2) return;
            int i = (int)(Math.random() * nodes.size());
            int j;
            do { j = (int)(Math.random() * nodes.size()); } while (j == i);
            nodes.get(i).gossipWith(nodes.get(j));
        }
    }

    /* =========================================================================
     * 3. 读写分离延迟方案
     * ========================================================================= */

    /** 缓存一致性：延迟双删 */
    static class DelayDoubleDelete {
        private final Map<String, String> db = new ConcurrentHashMap<>();
        private final Map<String, String> cache = new ConcurrentHashMap<>();

        DelayDoubleDelete() {
            db.put("user:1", "Alice");
            cache.put("user:1", "Alice");
        }

        /** 写操作：①写DB ②删缓存 ③延迟Nms再删缓存 */
        void update(String key, String newValue) {
            // ① 先写数据库
            db.put(key, newValue);
            System.out.printf("[延迟双删-①] DB 更新 %s=%s%n", key, newValue);

            // ② 立即删除缓存
            cache.remove(key);
            System.out.printf("[延迟双删-②] 删除缓存 %s%n", key);

            // ③ 延迟 N ms 后再删一次（清理在①和②之间读到的脏数据）
            int delayMs = 500;
            System.out.printf("[延迟双删-③] 延迟 %dms 后再次删除缓存%n", delayMs);
            // 模拟异步延迟执行
            new Thread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                cache.remove(key);
                System.out.printf("[延迟双删-③] 二次删除缓存 %s (清理脏数据)%n", key);
            }).start();
        }
    }

    /** Canal 模拟：监听 MySQL binlog → 异步更新缓存 */
    static class CanalSimulator {
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        private final List<String> binlog = new ArrayList<>();

        /** 模拟写 DB 产生 binlog */
        void writeToDB(String sql) {
            binlog.add(sql);
            System.out.printf("[Canal-Binlog] 捕获: %s%n", sql);
        }

        /** Canal 解析 binlog 后更新缓存 */
        void syncFromBinlog() {
            for (String entry : binlog) {
                if (entry.contains("UPDATE")) {
                    String[] parts = entry.split(" ");
                    String key = parts[2];
                    String value = parts[4];
                    cache.put(key, value);
                    System.out.printf("[Canal] 缓存同步 %s=%s%n", key, value);
                }
            }
            binlog.clear();
        }
    }

    /* =========================================================================
     * 4. 幂等性方案
     * ========================================================================= */

    /** 方案一：唯一索引 */
    static class UniqueIndexIdempotent {
        private final Set<String> uniqueKeys = ConcurrentHashMap.newKeySet();

        /** 基于业务唯一键防重 */
        boolean insert(String orderNo, String data) {
            boolean added = uniqueKeys.add(orderNo);
            if (added) {
                System.out.printf("[幂等-唯一索引] 插入成功 orderNo=%s%n", orderNo);
            } else {
                System.out.printf("[幂等-唯一索引] 重复请求 orderNo=%s, 已拦截%n", orderNo);
            }
            return added;
        }
    }

    /** 方案二：Token 机制 */
    static class TokenIdempotent {
        private final Set<String> tokens = ConcurrentHashMap.newKeySet();

        /** 客户端先获取 Token */
        String applyToken() {
            String token = UUID.randomUUID().toString().substring(0, 8);
            tokens.add(token);
            System.out.printf("[幂等-Token] 发放 Token: %s%n", token);
            return token;
        }

        /** 提交时校验 Token 并删除（原子操作防并发） */
        boolean submit(String token, String data) {
            boolean removed = tokens.remove(token);
            if (removed) {
                System.out.printf("[幂等-Token] Token=%s 校验通过，处理请求%n", token);
            } else {
                System.out.printf("[幂等-Token] Token=%s 不存在/已使用，拒绝%n", token);
            }
            return removed;
        }
    }

    /** 方案三：状态机 */
    static class StateMachineIdempotent {
        enum OrderState { CREATED, PAID, SHIPPED, COMPLETED, CANCELLED }

        private final Map<String, OrderState> stateMap = new ConcurrentHashMap<>();

        /** 状态流转控制：只有当前状态允许的操作才能执行 */
        boolean transition(String orderId, OrderState from, OrderState to) {
            OrderState current = stateMap.getOrDefault(orderId, OrderState.CREATED);
            if (current == from) {
                stateMap.put(orderId, to);
                System.out.printf("[幂等-状态机] 订单%s: %s → %s%n", orderId, from, to);
                return true;
            }
            System.out.printf("[幂等-状态机] 订单%s: 非法流转 %s→%s (当前=%s)%n",
                orderId, from, to, current);
            return false;
        }
    }

    /* =========================================================================
     * main
     * ========================================================================= */

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=".repeat(60));
        System.out.println("1. 强一致性 —— Quorum W+R > N");
        System.out.println("=".repeat(60));
        {
            StrongConsistencySimulator strong = new StrongConsistencySimulator(5);
            strong.strongWrite(1, 42, 3); // W=3 > 5/2
            strong.strongRead(3);          // R=3, W+R=6 > 5
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("2. 最终一致性 —— Gossip 协议");
        System.out.println("=".repeat(60));
        {
            GossipSimulator gossip = new GossipSimulator();
            GossipSimulator.GossipNode nodeA = new GossipSimulator.GossipNode("A");
            GossipSimulator.GossipNode nodeB = new GossipSimulator.GossipNode("B");
            GossipSimulator.GossipNode nodeC = new GossipSimulator.GossipNode("C");
            gossip.addNode(nodeA);
            gossip.addNode(nodeB);
            gossip.addNode(nodeC);

            nodeA.update(999);   // A 先更新
            gossip.gossipRound(); // A→B
            gossip.gossipRound(); // B→C
            System.out.printf("最终状态: A=v%d, B=v%d, C=v%d%n",
                nodeA.data.get("A"), nodeB.data.get("A"), nodeC.data.get("A"));
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("3. 延迟双删 + Canal");
        System.out.println("=".repeat(60));
        {
            DelayDoubleDelete ddd = new DelayDoubleDelete();
            ddd.update("user:1", "Bob");
            Thread.sleep(100); // 等异步线程输出

            System.out.println();
            CanalSimulator canal = new CanalSimulator();
            canal.writeToDB("UPDATE user SET name = Charlie WHERE id = 1");
            canal.syncFromBinlog();
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("4. 幂等性 —— 3 种方案");
        System.out.println("=".repeat(60));
        {
            // 唯一索引
            UniqueIndexIdempotent unique = new UniqueIndexIdempotent();
            unique.insert("ORDER-001", "data");
            unique.insert("ORDER-001", "data"); // 重复拦截

            // Token
            TokenIdempotent tokenChecker = new TokenIdempotent();
            String token = tokenChecker.applyToken();
            tokenChecker.submit(token, "payload");
            tokenChecker.submit(token, "payload"); // 重复使用拒绝

            // 状态机
            StateMachineIdempotent stateMachine = new StateMachineIdempotent();
            stateMachine.transition("ORDER-1",
                StateMachineIdempotent.OrderState.CREATED,
                StateMachineIdempotent.OrderState.PAID);
            stateMachine.transition("ORDER-1",
                StateMachineIdempotent.OrderState.CREATED,
                StateMachineIdempotent.OrderState.PAID); // 非法
        }
    }
}