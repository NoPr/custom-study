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