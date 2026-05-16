package base.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Redis 集群与分布式: CRC16 哈希槽 + 一致性哈希 + 主从复制 + Sentinel 故障转移
 * CRC16 取模 16384 槽: Gossip 心跳带宽友好 (2KB bitmap)
 * 一致性哈希 + 虚拟节点: 扩缩容时仅影响相邻节点, 减少数据迁移
 * PSYNC: replid + offset 判断增量/全量同步
 * Sentinel: SDOWN -> ODOWN -> 选举 -> 故障转移
 */
public class ClusterDemo {

    public static void main(String[] args) {
        System.out.println("========== Redis 集群与分布式演示 ==========\n");

        crc16SlotDemo();
        consistentHashDemo();
        masterSlaveReplicationDemo();
        sentinelFailoverDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void crc16SlotDemo() {
        System.out.println("--- CRC16 哈希槽计算 (Redis Cluster) ---");
        int totalSlots = 16384;

        String[] keys = {"user:1001", "order:5002", "product:888", "cache:temp",
                "session:abc", "{user}:profile", "{user}:settings"};

        for (String key : keys) {
            int hashSlot = hashSlot(key);
            System.out.printf("  key=%-20s -> hash_tag=%-12s -> slot=%-5d (节点 %d)%n",
                    key, hashTag(key), hashSlot, hashSlot / (totalSlots / 3));
        }

        System.out.println("16384 的原因: 2KB 心跳包可容纳, 网络开销合理 (65536 需要 8KB)\n");
    }

    static void consistentHashDemo() {
        System.out.println("--- 一致性哈希环模拟 (带虚拟节点) ---");
        int virtualNodesPerReal = 3;
        String[] realNodes = {"node-A", "node-B", "node-C"};

        TreeMap<Integer, String> ring = new TreeMap<>();
        for (String node : realNodes) {
            for (int i = 0; i < virtualNodesPerReal; i++) {
                int hash = fnv1a(node + "#v" + i);
                ring.put(hash, node);
            }
        }

        System.out.println("一致性哈希环 (虚拟节点数: " + virtualNodesPerReal + "):");
        ring.forEach((hash, node) ->
                System.out.printf("  hash=%-10d -> realNode=%s%n", hash, node));

        String[] testKeys = {"user:1", "order:99", "product:42", "session:abc", "cache:xyz"};
        System.out.println("\nKey 路由结果:");
        for (String key : testKeys) {
            int keyHash = fnv1a(key);
            Map.Entry<Integer, String> entry = ring.ceilingEntry(keyHash);
            if (entry == null) entry = ring.firstEntry();
            System.out.printf("  key=%-12s hash=%-10d -> %s%n", key, keyHash, entry.getValue());
        }

        System.out.println("\n增删节点模拟: 移除 node-B...");
        ring.entrySet().removeIf(e -> "node-B".equals(e.getValue()));
        for (String key : testKeys) {
            int keyHash = fnv1a(key);
            Map.Entry<Integer, String> entry = ring.ceilingEntry(keyHash);
            if (entry == null) entry = ring.firstEntry();
            System.out.printf("  key=%-12s -> %s%n", key, entry.getValue());
        }
        System.out.println("一致性哈希: 扩缩容时仅影响相邻节点数据\n");
    }

    static void masterSlaveReplicationDemo() {
        System.out.println("--- 主从复制 PSYNC 模拟 ---");
        long masterReplId = 0xABCD1234L;
        long masterOffset = 1000;
        long slaveOffset = 985;

        System.out.printf("Master: replid=%x offset=%d%n", masterReplId, masterOffset);
        System.out.printf("Slave:  offset=%d%n", slaveOffset);

        if (slaveOffset < masterOffset) {
            long lag = masterOffset - slaveOffset;
            System.out.printf("增量同步: 需要补发 %d 字节 (repl_backlog 中有数据)%n", lag);
            slaveOffset = masterOffset;
            System.out.printf("Slave offset 追上: %d%n", slaveOffset);
        }

        System.out.println("\n全量同步场景:");
        long newMasterReplId = 0xCDEF5678L;
        System.out.printf("Master 重启, replid 变为 %x (与 slave 的 %x 不匹配)%n",
                newMasterReplId, masterReplId);
        System.out.println("-> 触发全量同步: Master 生成 RDB, Slave 加载 RDB + 增量 buffer\n");
    }

    static void sentinelFailoverDemo() {
        System.out.println("--- Sentinel 故障转移简化模拟 ---");

        SentinelNode master = new SentinelNode("master-1", "192.168.1.10", 6379, true, 0);
        SentinelNode slave1 = new SentinelNode("slave-1", "192.168.1.11", 6379, false, 100);
        SentinelNode slave2 = new SentinelNode("slave-2", "192.168.1.12", 6379, false, 105);

        List<SentinelNode> nodes = Arrays.asList(master, slave1, slave2);
        System.out.println("初始状态:");
        nodes.forEach(System.out::println);

        System.out.println("\nMaster 主观下线 (SDOWN)...");
        master.online = false;
        System.out.println(master + " -> SDOWN");

        int quorum = 2;
        int sdownVotes = 3;
        System.out.printf("哨兵投票: %d/%d >= quorum(%d) -> ODOWN%n", sdownVotes, 3, quorum);

        System.out.println("\n选择新 Master:");
        SentinelNode newMaster = nodes.stream()
                .filter(n -> !n.isMaster && n.online)
                .min(Comparator.comparingInt(n -> n.replicationOffset))
                .orElse(null);

        if (newMaster != null) {
            newMaster.isMaster = true;
            System.out.println("新 Master: " + newMaster);
            nodes.stream().filter(n -> !n.isMaster && n.online).forEach(s -> {
                s.replicationOffset = newMaster.replicationOffset;
                System.out.println(s + " -> 指向新 Master");
            });
        }
        System.out.println("Sentinel 故障转移: 主观下线 -> 客观下线 -> 选主 -> 切换\n");
    }

    static int hashSlot(String key) {
        CRC32 crc = new CRC32();
        crc.update(hashTag(key).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 16384);
    }

    static String hashTag(String key) {
        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}', start);
            if (end != -1 && end > start + 1) {
                return key.substring(start + 1, end);
            }
        }
        return key;
    }

    static int fnv1a(String s) {
        int hash = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return Math.abs(hash);
    }

    /** Sentinel 管理下的节点: 包含角色 (isMaster)、在线状态、复制偏移量 */
    static class SentinelNode {
        String name;
        String ip;
        int port;
        boolean isMaster;
        boolean online = true;
        int replicationOffset;

        SentinelNode(String name, String ip, int port, boolean isMaster, int offset) {
            this.name = name; this.ip = ip; this.port = port;
            this.isMaster = isMaster; this.replicationOffset = offset;
        }

        @Override
        public String toString() {
            return String.format("  %s %s:%d master=%s offset=%d %s",
                    name, ip, port, isMaster, replicationOffset, online ? "ONLINE" : "SDOWN");
        }
    }
}