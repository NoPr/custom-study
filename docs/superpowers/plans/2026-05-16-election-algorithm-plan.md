# 分区选举算法实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 2 个 simulate 文件——5 种共识选举算法模拟 + 5 种 Kafka LeaderSelector 模拟

**Architecture:** 纯 Java 手写模拟，内部类建模 → main 方法分段演示 → 面试 Q&A

**Tech Stack:** Java 17, 无外部依赖

---

### Task 1: ElectionAlgorithmDemo — 5 种共识选举算法

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/ElectionAlgorithmDemo.java`

- [ ] **Step 1: 创建完整文件**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】分布式共识选举算法 —— Bully·Ring·Paxos·ZAB·Raft
 * 【描述】手写模拟 5 种经典分布式选举算法：Bully（最高ID获胜）、Ring（令牌环）、
 *         Paxos（Prepare/Accept 两阶段）、ZAB（ZXID 比较，ZK使用）、
 *         Raft（Term+Vote，KRaft/DLedger使用）。每种算法附带 MQ 对应关系。
 * 【关键概念】Bully、Ring、Paxos、ZAB、ZXID、Raft、Term、Vote、
 *             Leader Election、Quorum、AppendEntries
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *          @see com.nopr.mq.kafka.simulate.SplitBrainDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class ElectionAlgorithmDemo {

    /* ==========================================================
     *  1. Bully 算法 —— 最高 ID 获胜
     *  场景：节点发现 Leader 无响应 → 向所有更高 ID 发 Election
     *        → 超时无回应 → 自宣告为 Leader
     *  对应：无主流 MQ 使用，但理解门槛最低
     * ========================================================== */
    static class BullyNode {
        int id;
        boolean isLeader;
        List<BullyNode> cluster;

        BullyNode(int id) { this.id = id; }

        void detectLeaderDown() {
            System.out.printf("    [Node-%d] 检测到 Leader 无响应，发起选举%n", id);
            boolean higherExists = false;
            for (BullyNode n : cluster) {
                if (n.id > id) {
                    System.out.printf("    [Node-%d] → 向 Node-%d 发送 Election 消息%n", id, n.id);
                    higherExists = true;
                }
            }
            if (!higherExists) {
                becomeLeader();
            }
        }

        void receiveElection(int fromId) {
            System.out.printf("    [Node-%d] 收到 Node-%d 的 Election，回复 OK%n", id, fromId);
            detectLeaderDown();
        }

        void becomeLeader() {
            isLeader = true;
            System.out.printf("    \uD83D\uDC51 [Node-%d] 自宣告为 Leader！%n", id);
            for (BullyNode n : cluster) {
                if (n != this) n.receiveCoordinator(id);
            }
        }

        void receiveCoordinator(int leaderId) {
            isLeader = (id == leaderId);
            System.out.printf("    [Node-%d] 收到 Coordinator 消息，新 Leader=Node-%d%n", id, leaderId);
        }
    }

    static void demo_bully() {
        printSection("1. Bully 算法（最高 ID 获胜）");

        System.out.println("  原则：节点发现 Leader 宕机 → 向所有更高 ID 发 Election");
        System.out.println("        → 超时未收到 OK → 自宣告 Leader（因为没比自己更高的节点活着）");
        System.out.println();

        List<BullyNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            BullyNode n = new BullyNode(i);
            nodes.add(n);
        }
        for (BullyNode n : nodes) n.cluster = nodes;
        nodes.get(4).isLeader = true; // Node-5 是 Leader

        System.out.println("  初始状态: Node-5 是 Leader，Node-5 宕机...");
        System.out.println("  Node-2 最先检测到 → 开始选举:");
        nodes.get(1).detectLeaderDown();

        System.out.println();
        System.out.println("  \uD83D\uDCA1 面试要点: Bully 算法在节点启动时「直接挑战」现存 Leader——");
        System.out.println("     新节点 ID 更大则夺权（名字来源：以大欺小）。");
        System.out.println("     MQ 不用 Bully 因为：全连接通信 O(n²) + 无日志一致性保证。");
    }

    /* ==========================================================
     *  2. Ring 算法 —— 令牌环选举
     *  场景：节点组成逻辑环 → 检测 Leader 失效 → 生成 Election 消息
     *        → 沿环传递，每节点插入自己 ID → 回到起点后选最高 ID
     * ========================================================== */
    static void demo_ring() {
        printSection("2. Ring 算法（令牌环选举）");

        System.out.println("  原则：节点按逻辑环排列 → 任一节点检测到 Leader 失效");
        System.out.println("        → 沿环发送 Election 消息（携带候选 ID 列表）");
        System.out.println("        → 回到起点后广播选出的最高 ID 为 Leader");
        System.out.println();

        int[] ids = {2, 5, 1, 4, 3}; // 环顺序
        System.out.print("  环结构: ");
        for (int i = 0; i < ids.length; i++) {
            System.out.printf("Node-%d", ids[i]);
            if (i < ids.length - 1) System.out.print(" → ");
        }
        System.out.println(" → Node-2（闭环）");
        System.out.println();

        System.out.println("  模拟: Node-1 检测到 Leader 宕机，发起选举");
        int startIdx = 2; // Node-1 在环中的位置
        List<Integer> candidateList = new ArrayList<>();
        candidateList.add(ids[startIdx]);

        System.out.printf("    [Node-%d] 发起 Election → 携带 [%d]%n", ids[startIdx], ids[startIdx]);

        for (int i = 0; i < ids.length - 1; i++) {
            int currentIdx = (startIdx + i + 1) % ids.length;
            int currentNode = ids[currentIdx];
            if (currentNode > candidateList.get(candidateList.size() - 1)) {
                candidateList.clear();
                candidateList.add(currentNode);
            }
            System.out.printf("    [Node-%d] 收到 Election → 当前候选: %d → 转发%n",
                    currentNode, candidateList.get(0));
        }

        int newLeader = candidateList.get(0);
        System.out.printf("    \uD83D\uDC51 Election 回到 Node-%d → 广播新 Leader: Node-%d%n",
                ids[startIdx], newLeader);

        System.out.println();
        System.out.println("  \uD83D\uDCA1 面试要点: Ring 算法避免全连接，通信次数 O(n)，但延迟 O(n)。");
        System.out.println("     适合对实时性要求不高的场景。MQ 不采用因为故障恢复太慢。");
    }

    /* ==========================================================
     *  3. Paxos 选举 —— Prepare/Accept 两阶段
     *  场景：Proposer 提议 → Prepare(N) 问 Acceptor
     *        → Promise 返回已接受的最高值 → Accept(N,V)
     *        → 多数派 Accepted → 决议达成
     * ========================================================== */
    static class PaxosAcceptor {
        int id;
        int promisedN = -1;
        int acceptedN = -1;
        String acceptedV = null;

        PaxosAcceptor(int id) { this.id = id; }

        // Phase 1: Prepare
        String prepare(int n) {
            if (n > promisedN) {
                promisedN = n;
                String prev = (acceptedV != null) ? acceptedV : "(none)";
                System.out.printf("    [Acceptor-%d] Promise N=%d, 之前已接受: %s%n", id, n, prev);
                return prev;
            }
            System.out.printf("    [Acceptor-%d] Reject Prepare N=%d (promised=%d)%n", id, n, promisedN);
            return null;
        }

        // Phase 2: Accept
        boolean accept(int n, String v) {
            if (n >= promisedN) {
                promisedN = n;
                acceptedN = n;
                acceptedV = v;
                System.out.printf("    [Acceptor-%d] Accept N=%d V=%s%n", id, n, v);
                return true;
            }
            System.out.printf("    [Acceptor-%d] Reject Accept N=%d (promised=%d)%n", id, n, promisedN);
            return false;
        }
    }

    static void demo_paxos() {
        printSection("3. Paxos 选举（Prepare/Accept 两阶段）");

        System.out.println("  原则：Proposer(提议者) → Acceptor(接受者) → Learner(学习者)");
        System.out.println("        Phase1: Prepare(N) → Promise（确认编号，带回旧值）");
        System.out.println("        Phase2: Accept(N,V) → Accepted → 多数派通过");
        System.out.println();

        List<PaxosAcceptor> acceptors = new ArrayList<>();
        for (int i = 1; i <= 5; i++) acceptors.add(new PaxosAcceptor(i));

        System.out.println("  Proposer 提 N=10, V=Leader-A:");
        int n1 = 10;
        String v1 = "Leader-A";
        int promises1 = 0;
        for (PaxosAcceptor a : acceptors) {
            String prev = a.prepare(n1);
            if (prev != null) promises1++;
        }
        System.out.printf("    Phase1 结果: %d/5 Promise%n", promises1);
        int accepts1 = 0;
        for (PaxosAcceptor a : acceptors) {
            if (a.accept(n1, v1)) accepts1++;
        }
        System.out.printf("    Phase2 结果: %d/5 Accepted → \uD83D\uDC51 %s%n", accepts1, v1);

        System.out.println();
        System.out.println("  \uD83D\uDCA1 面试要点: Paxos 是共识算法的理论基础，Raft/ZAB 都是其工程化变体。");
        System.out.println("     核心在于：任何被选中的值，后续选举必须沿用（Prepare 带回旧值）。");
        System.out.println("     Pulsar BookKeeper 元数据管理使用 Paxos 变体。");
    }

    /* ==========================================================
     *  4. ZAB 选举 —— ZXID 比较（ZooKeeper）
     *  场景：节点投票给 ZXID 最大者 → 过半投票 → 新 Leader 发 NEW_LEADER
     *        → Follower ACK → 过半 ACK → Leader 正式服务
     *  对应：Kafka 旧版 Controller 选举（依赖 ZK）
     * ========================================================== */
    static class ZABNode {
        int id;
        long zxid;
        int votedFor = -1;
        int leaderId = -1;

        ZABNode(int id, long zxid) { this.id = id; this.zxid = zxid; }

        int vote(List<ZABNode> voters) {
            int bestId = id;
            long bestZxid = zxid;
            for (ZABNode n : voters) {
                if (n.zxid > bestZxid || (n.zxid == bestZxid && n.id > bestId)) {
                    bestZxid = n.zxid;
                    bestId = n.id;
                }
            }
            votedFor = bestId;
            System.out.printf("    [Node-%d zxid=%d] → 投票给 Node-%d%n", id, zxid, bestId);
            return bestId;
        }
    }

    static void demo_zab() {
        printSection("4. ZAB 选举（ZXID 比较 — ZooKeeper 使用）");

        System.out.println("  原则：每个节点有 zxid（事务ID，越大数据越新）");
        System.out.println("        → 投票给 zxid 最大的节点（zxid 相同则 ID 大的胜）");
        System.out.println("        → 过半投票 → 新 Leader 发送 NEW_LEADER → Follower 同步 ACK");
        System.out.println();

        List<ZABNode> nodes = new ArrayList<>();
        nodes.add(new ZABNode(1, 100));
        nodes.add(new ZABNode(2, 105));
        nodes.add(new ZABNode(3, 100));
        nodes.add(new ZABNode(4, 103));
        nodes.add(new ZABNode(5, 105)); // Node-5 zxid=105, id=5 => 最大

        System.out.println("  投票阶段:");
        Map<Integer, Integer> voteCount = new HashMap<>();
        for (ZABNode n : nodes) {
            int vote = n.vote(nodes);
            voteCount.merge(vote, 1, Integer::sum);
        }
        System.out.println();
        int winner = voteCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).get().getKey();
        System.out.printf("    \uD83D\uDC51 Node-%d 获得 %d 票（过半 3/5）→ 当选 Leader%n",
                winner, voteCount.get(winner));

        System.out.println();
        System.out.println("  Leader 发送 NEW_LEADER → Follower ACK → 过半 ACK → Leader 正式对外服务");

        System.out.println();
        System.out.println("  \uD83D\uDCA1 面试要点: ZAB 优于 Paxos 在于「主进程顺序、不并发」——");
        System.out.println("     所有事务由 Leader 顺序提交，保证 FIFO 顺序。");
        System.out.println("     Kafka 旧版 Controller 选举通过 ZK 临时节点 + ZAB 实现。");
    }

    /* ==========================================================
     *  5. Raft 选举 —— Term + Vote
     *  场景：Follower 超时 → Candidate → Term++ → RequestVote
     *        → 过半投票 → Leader → AppendEntries 心跳
     *  对应：KRaft(Kafka 3.3+) / DLedger(RocketMQ 4.5+)
     * ========================================================== */
    static class RaftNode {
        int id;
        int currentTerm = 0;
        int votedFor = -1;
        enum Role { FOLLOWER, CANDIDATE, LEADER }
        Role role = Role.FOLLOWER;
        int voteCount = 0;

        RaftNode(int id) { this.id = id; }

        void startElection(List<RaftNode> cluster) {
            currentTerm++;
            role = Role.CANDIDATE;
            votedFor = id;
            voteCount = 1;
            System.out.printf("    [Node-%d] Term=%d, 成为 Candidate → 给自己投票%n", id, currentTerm);

            for (RaftNode n : cluster) {
                if (n != this && n.requestVote(currentTerm, id)) {
                    voteCount++;
                }
            }
            System.out.printf("    [Node-%d] 获得 %d 票 (Term=%d)%n", id, voteCount, currentTerm);
            if (voteCount > cluster.size() / 2) {
                role = Role.LEADER;
                System.out.printf("    \uD83D\uDC51 [Node-%d] 当选 Leader (Term=%d)，开始发送心跳！%n", id, currentTerm);
            }
        }

        boolean requestVote(int term, int candidateId) {
            if (term > currentTerm) {
                currentTerm = term;
                votedFor = candidateId;
                System.out.printf("    [Node-%d] 投票给 Node-%d (Term=%d)%n", id, candidateId, term);
                return true;
            }
            System.out.printf("    [Node-%d] 拒绝投票给 Node-%d (Term=%d ≤ currentTerm=%d)%n",
                    id, candidateId, term, currentTerm);
            return false;
        }

        void receiveHeartbeat(int term, int leaderId) {
            if (term >= currentTerm) {
                currentTerm = term;
                role = Role.FOLLOWER;
                System.out.printf("    [Node-%d] 收到 Leader-%d 心跳 (Term=%d)%n", id, leaderId, term);
            }
        }
    }

    static void demo_raft() {
        printSection("5. Raft 选举（Term + Vote — KRaft / DLedger 使用）");

        System.out.println("  原则：Follower 在 election_timeout 内未收到心跳 → Candidate");
        System.out.println("        → Term++ → 发送 RequestVote → 过半投票 → Leader");
        System.out.println("        → Leader 发 AppendEntries 心跳维持权威");
        System.out.println();

        List<RaftNode> cluster = new ArrayList<>();
        for (int i = 1; i <= 5; i++) cluster.add(new RaftNode(i));

        System.out.println("  模拟: Node-3 Follower→ Candidate，发起选举");
        cluster.get(2).startElection(cluster);
        if (cluster.get(2).role == RaftNode.Role.LEADER) {
            for (RaftNode n : cluster) {
                if (n != cluster.get(2)) {
                    n.receiveHeartbeat(cluster.get(2).currentTerm, cluster.get(2).id);
                }
            }
        }

        System.out.println();
        System.out.println("  \uD83D\uDCA1 面试: Term 和 Vote 是 Raft 核心——每个 Term 每个节点只能投一票");
        System.out.println("      Candidate 可能在同 Term 分裂选票（Split Vote）→ Term++ 重试");
        System.out.println("      KRaft(Kafka 3.3+) 和 DLedger(RocketMQ 4.5+) 均基于 Raft");
    }

    /* ==========================================================
     *  总结与面试 Q&A
     * ========================================================== */
    static void summary() {
        printSection("6. 算法对比与 MQ 对应");

        System.out.printf("  %-12s %-16s %-18s %-20s%n", "算法", "复杂度", "MQ 对应", "特点");
        System.out.println("  " + "-".repeat(66));
        printRow4("Bully", "O(n²)", "无", "最高 ID 获胜，简单但通信多");
        printRow4("Ring", "O(n)", "无", "令牌环，延迟高");
        printRow4("Paxos", "O(n)", "Pulsar BookKeeper", "共识理论基础，多数派");
        printRow4("ZAB", "O(n)", "ZooKeeper→Kafka旧", "ZXID 比较，FIFO 顺序");
        printRow4("Raft", "O(n)", "KRaft+DLedger", "Term+Vote，可理解性优");

        System.out.println();
        System.out.println("  选型建议:");
        System.out.println("    1. 生产系统 → Raft (成熟实现多: KRaft/DLedger/etcd)");
        System.out.println("    2. ZK 依赖 → ZAB (Kafka 旧版 / Hadoop 生态)");
        System.out.println("    3. 极致理论 → Paxos (理解 Raft 基础)");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");

        System.out.println("  Q: Raft 选举中什么情况下会出现 Split Vote？");
        System.out.println("  A: 多个 Follower 几乎同时超时 → 同时变成 Candidate");
        System.out.println("     → 每个 Candidate 只给自己投票（同一 Term 每个节点只投一票）");
        System.out.println("     → 谁都无法过半。解决：随机超时 150~300ms，拉开发起时机。");
        System.out.println();
        System.out.println("  Q: ZAB 为什么不需要 Prepare/Accept 两阶段？");
        System.out.println("  A: ZAB 简化——选举只看 ZXID（数据谁最新），选举成功后");
        System.out.println("      Leader 先同步差异 → 再正式服务。不并发提议，省去 Paxos 复杂度。");
        System.out.println();
        System.out.println("  Q: KRaft 相比 ZK Controller 选举有什么优势？");
        System.out.println("  A: ①去 ZK 依赖→降低运维复杂度");
        System.out.println("     ②元数据一致性由 Raft 保证（不像 ZK 异步同步可能不一致）");
        System.out.println("     ③Controller 故障恢复更快（秒级，ZK 需等待 session 超时）");
    }

    /* ==========================================================
     *  main / 工具方法
     * ========================================================== */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  分布式共识选举算法模拟 — Bully·Ring·Paxos·ZAB·Raft");
        System.out.println("=".repeat(70));

        demo_bully();
        demo_ring();
        demo_paxos();
        demo_zab();
        demo_raft();
        summary();
        interviewQA();

        System.out.printf("%n  \uD83C\uDFAF 关键结论: MQ 选主统一收敛到 Raft → KRaft + DLedger%n");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n%n", "\u2500".repeat(66));
    }

    static void printRow4(String alg, String complexity, String mq, String feature) {
        System.out.printf("  %-12s %-16s %-18s %-20s%n", alg, complexity, mq, feature);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/ElectionAlgorithmDemo.java
git commit -m "feat(kafka): add ElectionAlgorithmDemo — 5 consensus election algorithms"
```

---

### Task 2: KafkaLeaderSelectorDemo — Kafka 5 种 LeaderSelector

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/KafkaLeaderSelectorDemo.java`

- [ ] **Step 1: 创建完整文件**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】Kafka Leader 选举 —— 5 种 LeaderSelector + Controller 选举流程
 * 【描述】模拟 Kafka 源码 kafka.controller 包中的 5 种 LeaderSelector：
 *         NoOpLeaderSelector（元数据 Topic）、OfflinePartitionLeader（分区下线）、
 *         ReassignedPartitionLeader（重分配中选 Leader）、
 *         PreferredReplicaPartitionLeader（优先选首选副本）、
 *         ControlledShutdownLeader（优雅关闭迁移）。
 *         附带 Kafka Controller 选举流程（ZK 临时节点 vs KRaft Quorum）。
 * 【关键概念】LeaderSelector、PartitionLeaderElection、NoOp、Offline、
 *             Reassigned、PreferredReplica、ControlledShutdown、AR/ISR、
 *             auto.leader.rebalance、Preferred Replica
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *          @see com.nopr.mq.kafka.simulate.SplitBrainDemo
 *          @see com.nopr.mq.kafka.simulate.ElectionAlgorithmDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class KafkaLeaderSelectorDemo {

    /* ── 共享模型 ────────────────────────────────────────────── */
    static class Replica {
        int id;
        boolean isAlive;
        Replica(int id, boolean isAlive) { this.id = id; this.isAlive = isAlive; }
    }

    static class Partition {
        int id;
        List<Replica> replicas = new ArrayList<>();     // AR: Assigned Replicas
        List<Replica> isr = new ArrayList<>();           // ISR: In-Sync Replicas
        int leaderId = -1;
        int preferredReplicaId;

        Partition(int id) { this.id = id; }

        void setPreferred(int replicaId) { this.preferredReplicaId = replicaId; }

        void electLeader(String result) {
            if (result == null) {
                leaderId = -1;
                System.out.printf("  [Partition-%d] No Leader 选出%n", id);
            } else {
                leaderId = Integer.parseInt(result);
                System.out.printf("  [Partition-%d] \uD83D\uDC51 Leader=Replica-%d%n", id, leaderId);
            }
        }
    }

    /* ==========================================================
     *  Selector 1: NoOpLeaderSelector
     *  场景：元数据 Topic（__consumer_offsets / __transaction_state）
     *  不选 Leader，分区由内部管理器控制
     *  对应源码：NoOpLeaderSelector
     * ========================================================== */
    static String noOpSelector(Partition p) {
        System.out.println("  Type=NoOp → 元数据 Topic，不执行 Leader 选举（由内部管理器控制）");
        return null;
    }

    static void demo_noOp() {
        printSection("1. NoOpLeaderSelector — 不选 Leader");

        System.out.println("  使用场景: __consumer_offsets / __transaction_state 等内部 Topic");
        System.out.println("  这些 Topic 的分区 Leader 由 GroupCoordinator/TransactionCoordinator");
        System.out.println("  所在的 Broker 自动成为 Leader，不走通用选举流程。");
        System.out.println();

        Partition p = new Partition(0);
        p.replicas.add(new Replica(1, true));
        p.replicas.add(new Replica(2, true));
        p.electLeader(noOpSelector(p));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 源码位置: kafka.controller.NoOpLeaderSelector");
        System.out.println("     在 PartitionStateMachine 中，NoOp 的 Partition 跳过选举逻辑。");
    }

    /* ==========================================================
     *  Selector 2: OfflinePartitionLeader
     *  场景：分区 AR 中所有副本宕机 / ISR 为空
     *  无法选出 Leader → 分区进入 Offline 状态
     *  对应源码：OfflinePartitionLeaderSelector
     * ========================================================== */
    static String offlineSelector(Partition p) {
        long aliveInAR = p.replicas.stream().filter(r -> r.isAlive).count();
        if (aliveInAR == 0) {
            System.out.println("  Type=Offline → AR 中无存活副本，分区进入 Offline 状态");
            System.out.println("                需 Admin 手动干预（重启 Broker / reassign-partitions）");
            return null;
        }
        System.out.println("  AR 中还有存活副本，不应走 OfflineSelector");
        return null;
    }

    static void demo_offline() {
        printSection("2. OfflinePartitionLeader — 分区下线（无 Leader）");

        System.out.println("  触发条件: Partition AR (Assigned Replicas) 中所有 Broker 宕机");
        System.out.println("  结果: 分区无法选出 Leader，进入 Offline 状态");
        System.out.println("  恢复: Broker 重启后 Controller 自动重新选举");
        System.out.println();

        Partition p = new Partition(0);
        p.replicas.add(new Replica(1, false)); // 宕机
        p.replicas.add(new Replica(2, false));
        p.replicas.add(new Replica(3, false));
        p.isr.addAll(p.replicas);
        p.electLeader(offlineSelector(p));

        System.out.println();
        System.out.println("  \u26A0\uFE0F 面试: 线上出现 OfflinePartition → 检查 Broker 存活状态 →");
        System.out.println("    如果 unclean.leader.election.enable=true，ISR 空时也可能从非 ISR 选 Leader。");
    }

    /* ==========================================================
     *  Selector 3: ReassignedPartitionLeader
     *  场景：kafka-reassign-partitions.sh 执行分区重分配
     *  Controller 从新 AR 的第一个存活副本选 Leader
     *  对应源码：ReassignedPartitionLeaderSelector
     * ========================================================== */
    static String reassignedSelector(Partition p) {
        for (Replica r : p.replicas) {
            if (r.isAlive) {
                System.out.printf("  Type=Reassigned → 新 AR[0]=Replica-%d 存活 → 选为 Leader%n", r.id);
                return String.valueOf(r.id);
            }
        }
        System.out.println("  Type=Reassigned → 新 AR 中无存活副本");
        return null;
    }

    static void demo_reassigned() {
        printSection("3. ReassignedPartitionLeader — 分区重分配中选 Leader");

        System.out.println("  场景: Admin 执行 kafka-reassign-partitions.sh 重新分配分区副本");
        System.out.println("  结果: Controller 从新 AR (Assigned Replicas) 的第一个存活副本选 Leader");
        System.out.println();

        System.out.println("  原 AR: [1,2,3] → 新 AR: [4,5,6] （Broker-4,5,6 可能已在 ISR 也可能不在）");
        Partition p = new Partition(0);
        p.replicas.add(new Replica(4, true));
        p.replicas.add(new Replica(5, true));
        p.replicas.add(new Replica(6, false));
        p.electLeader(reassignedSelector(p));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 注意: Reassigned 场景下不要求新 Leader 在 ISR 中——");
        System.out.println("     只要活着的副本足够，重分配期间 Controller 直接指定 Leader。");
        System.out.println("     Follower 后续会追赶 Leader 加入 ISR。");
    }

    /* ==========================================================
     *  Selector 4: PreferredReplicaPartitionLeader
     *  场景：auto.leader.rebalance.enable=true 时自动均衡
     *       Leader 不在 Preferred Replica 上 → Controller 发起迁移
     *  对应源码：PreferredReplicaPartitionLeaderSelector
     * ========================================================== */
    static String preferredReplicaSelector(Partition p) {
        if (p.preferredReplicaId <= 0) {
            System.out.println("  Type=PreferredReplica → 无 Preferred，ISR 第一个作为 Leader");
            for (Replica r : p.isr) {
                if (r.isAlive) return String.valueOf(r.id);
            }
            return null;
        }
        for (Replica r : p.isr) {
            if (r.id == p.preferredReplicaId && r.isAlive) {
                System.out.printf("  Type=PreferredReplica → Preferred=Replica-%d 在 ISR 且存活 → \uD83D\uDC51%n", r.id);
                return String.valueOf(r.id);
            }
        }
        System.out.printf("  Type=PreferredReplica → Preferred=Replica-%d 不在 ISR/宕机 → ISR 中另选%n",
                p.preferredReplicaId);
        for (Replica r : p.isr) {
            if (r.isAlive) return String.valueOf(r.id);
        }
        return null;
    }

    static void demo_preferredReplica() {
        printSection("4. PreferredReplicaPartitionLeader — 优先选 Preferred Replica");

        System.out.println("  Preferred Replica: Partition 创建时 AR 的第一个副本");
        System.out.println("  意义: 让 Leader 分布回到初始均衡状态（每个 Broker 均匀承担 Leader）");
        System.out.println();

        System.out.println("  正常场景: Preferred=Replica-1 在 ISR 且存活");
        Partition p1 = new Partition(1);
        p1.replicas.add(new Replica(1, true));
        p1.replicas.add(new Replica(2, true));
        p1.isr.add(p1.replicas.get(0));
        p1.isr.add(p1.replicas.get(1));
        p1.setPreferred(1);
        p1.electLeader(preferredReplicaSelector(p1));

        System.out.println();
        System.out.println("  异常场景: Preferred=Replica-1 宕机，ISR=[2,3]，选 Replica-2");
        Partition p2 = new Partition(2);
        p2.replicas.add(new Replica(1, false));
        p2.replicas.add(new Replica(2, true));
        p2.replicas.add(new Replica(3, true));
        p2.isr.add(p2.replicas.get(1));
        p2.isr.add(p2.replicas.get(2));
        p2.setPreferred(1);
        p2.electLeader(preferredReplicaSelector(p2));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 生产建议: auto.leader.rebalance.enable=true（默认）→");
        System.out.println("     Controller 定期检查并迁移 Leader 到 Preferred Replica");
        System.out.println("     → Leader 分布均匀 → 各 Broker 负载均衡");
    }

    /* ==========================================================
     *  Selector 5: ControlledShutdownLeader
     *  场景：Broker 收到 SIGTERM（优雅关闭）
     *  Controller 将该 Broker 的 Leader 迁移到 ISR 中其他副本
     *  目标：分区零停机切换
     *  对应源码：ControlledShutdownLeaderSelector
     * ========================================================== */
    static String controlledShutdownSelector(Partition p, int shuttingDownBrokerId) {
        // Leader 在待关闭 Broker 上 → 需要迁移
        if (p.leaderId == shuttingDownBrokerId) {
            System.out.printf("  Type=ControlledShutdown → Leader=Replica-%d 在 Broker-%d 上 → 需要迁移%n",
                    p.leaderId, shuttingDownBrokerId);
            for (Replica r : p.isr) {
                if (r.id != shuttingDownBrokerId && r.isAlive) {
                    System.out.printf("    迁移到 ISR 中的 Replica-%d%n", r.id);
                    return String.valueOf(r.id);
                }
            }
            System.out.println("    \u26A0\uFE0F ISR 中无其他可用副本 → 分区将短暂不可用");
            return null;
        }
        System.out.printf("  Type=ControlledShutdown → Broker-%d 无 Leader 分区，无需迁移%n", shuttingDownBrokerId);
        return null;
    }

    static void demo_controlledShutdown() {
        printSection("5. ControlledShutdownLeader — 优雅关闭 Leader 迁移");

        System.out.println("  场景: 运维重启 Broker → kill -15 (SIGTERM) → Broker 通知 Controller");
        System.out.println("  Controller 收到 ControlledShutdown 请求后:");
        System.out.println("    ① 遍历该 Broker 上所有 Leader 分区");
        System.out.println("    ② 逐个从 ISR 中选新 Leader 迁移");
        System.out.println("    ③ 所有 Leader 迁移完成 → Broker 安全关闭");
        System.out.println();

        System.out.println("  模拟: Broker-1 优雅关闭，其上有 Partition-0 的 Leader");
        Partition p = new Partition(0);
        p.replicas.add(new Replica(1, true));
        p.replicas.add(new Replica(2, true));
        p.replicas.add(new Replica(3, true));
        p.isr.add(p.replicas.get(0));
        p.isr.add(p.replicas.get(1));
        p.isr.add(p.replicas.get(2));
        p.leaderId = 1; // 当前 Leader 在 Broker-1
        p.electLeader(controlledShutdownSelector(p, 1));

        System.out.println();
        System.out.println("  \uD83D\uDCA1 ControlledShutdown vs 硬宕机:");
        System.out.println("    - 优雅关闭: Leader 逐分区迁移 → 服务不中断 → ~秒级完成");
        System.out.println("    - 硬宕机: Controller 检测 Broker 失联 → ISR 收缩 → 重新选 Leader → ~30s");
    }

    /* ==========================================================
     *  6. Controller 选举流程（附赠）
     * ========================================================== */
    static void controllerElection() {
        printSection("6. Kafka Controller 选举流程");

        System.out.println("  [ZK 模式] (Kafka < 3.3):");
        System.out.println("    1. 所有 Broker 竞争创建 ZK 临时节点 /controller");
        System.out.println("    2. 创建成功者 → Controller（ZK ZAB 协议保证唯一）");
        System.out.println("    3. Controller 在 ZK 注册 Watch → 监听 Broker/Partition 变更");
        System.out.println("    4. Controller 宕机 → 临时节点消失 → 其他 Broker 抢创建 → 新 Controller");
        System.out.println("    选举速度: ZK session timeout (默认 18s)");
        System.out.println();
        System.out.println("  [KRaft 模式] (Kafka ≥ 3.3):");
        System.out.println("    1. 控制器节点组成 Quorum (3/5 奇数) → Raft 选举 Leader");
        System.out.println("    2. Raft Leader = Active Controller（替代 ZK 临时节点）");
        System.out.println("    3. 元数据日志由 Raft 复制到所有 Quorum 节点");
        System.out.println("    4. Controller 宕机 → Raft 自动选新 Leader（秒级）");
        System.out.println();
        System.out.println("  \uD83D\uDCA1 对比: ZK 模式依赖外部 ZK → session_timeout 延迟高；");
        System.out.println("     KRaft 模式内建 Raft → 故障恢复秒级 + 无需维护 ZK 集群");
    }

    /* ==========================================================
     *  总结与面试 Q&A
     * ========================================================== */
    static void summary() {
        printSection("7. 5 种 LeaderSelector 对比");

        System.out.printf("  %-28s %-18s %-20s%n", "Selector", "触发条件", "选 Leader 策略");
        System.out.println("  " + "-".repeat(66));
        printRow3("NoOpLeaderSelector", "元数据 Topic", "不参与选举");
        printRow3("OfflinePartitionLeader", "AR 全宕", "无法选 Leader");
        printRow3("ReassignedPartition", "kafka-reassign-partitions", "新 AR 第一个存活副本");
        printRow3("PreferredReplica", "auto.leader.rebalance", "优先选 AR[0]");
        printRow3("ControlledShutdown", "Broker SIGTERM", "ISR 中其他存活副本");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");

        System.out.println("  Q: Kafka 什么情况下分区会没有 Leader？");
        System.out.println("  A: ① AR 中所有副本宕机 → OfflinePartition");
        System.out.println("     ② ISR 为空且 unclean.leader.election.enable=false → 无 Leader");
        System.out.println("     ③ Controller 自身宕机中（KRaft 选主期间）→ 暂不可用");
        System.out.println();
        System.out.println("  Q: Preferred Replica 均衡是怎么触发的？");
        System.out.println("  A: auto.leader.rebalance.enable=true（默认）+");
        System.out.println("     leader.imbalance.check.interval.seconds=300（默认5分钟）");
        System.out.println("     → Controller 检查 Leader 不均衡比例 > leader.imbalance.per.broker.percentage=10%");
        System.out.println("     → 触发 PreferredReplicaPartitionLeader → 分批迁移");
        System.out.println();
        System.out.println("  Q: ControlledShutdown 失败会怎样？");
        System.out.println("  A: 超时后 Controller 视为硬宕机 → 从 ISR 重新选 Leader");
        System.out.println("     → Producer 短暂不可用（等待新 Leader 选出 + 元数据更新）→ ~30s");
    }

    /* ==========================================================
     *  main / 工具方法
     * ========================================================== */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka LeaderSelector 模拟 — 5 种选主策略");
        System.out.println("=".repeat(70));

        demo_noOp();
        demo_offline();
        demo_reassigned();
        demo_preferredReplica();
        demo_controlledShutdown();
        controllerElection();
        summary();
        interviewQA();
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n%n", "\u2500".repeat(66));
    }

    static void printRow3(String selector, String trigger, String strategy) {
        System.out.printf("  %-28s %-18s %-20s%n", selector, trigger, strategy);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS（两个新文件 + 现有 21 文件 = 23 文件）

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/ElectionAlgorithmDemo.java study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/KafkaLeaderSelectorDemo.java
git commit -m "feat(kafka): add election algorithm demos — consensus + Kafka LeaderSelector"
```

---

## 最终验证

- [ ] **全量编译**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS — 23 Java 文件

## 自检清单

- ✅ Spec 全覆盖（5 共识算法 + 5 LeaderSelector + Controller 选举）
- ✅ 无 TBD/TODO/占位符
- ✅ 类名/方法名一致性：ElectionAlgorithmDemo/KafkaLeaderSelectorDemo
- ✅ 延续现有 simulate 风格（内部类 + main + printSection）
- ✅ Javadoc 头部【模块】【分类】【主题】【描述】【关键概念】模板
- ✅ 2 个文件互不依赖，可并行创建