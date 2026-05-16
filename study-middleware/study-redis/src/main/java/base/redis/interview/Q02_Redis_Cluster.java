package base.redis.interview;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 面试题: Redis 集群架构 -- 主从/哨兵/Cluster 对比 + 16384 槽设计原因 + 数据倾斜
 * 为什么 16384 个槽? 心跳包 bitmap 仅 2KB vs 65536 需要 8KB
 * Gossip 协议节点间频繁交换心跳, 2KB 是网络开销可接受的临界点
 * 数据倾斜: hash_tag 将关联数据集中, 或 Prefix+随机后缀分散热点
 */
public class Q02_Redis_Cluster {

    public static void main(String[] args) {
        System.out.println("========== 面试题: Redis 集群 ==========\n");

        clusterComparison();
        why16384Slots();
        dataSkewDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void clusterComparison() {
        System.out.println("--- 集群分片 vs 哨兵 vs 主从 对比 ---");

        System.out.println("""
                +------------------+-------------+----------------+------------------+
                | 特性             | 主从模式     | 哨兵模式        | Cluster模式      |
                +------------------+-------------+----------------+------------------+
                | 数据分片         | 不支持       | 不支持          | 支持(16384槽)    |
                | 高可用           | 手动切换     | 自动故障转移     | 自动故障转移     |
                | 扩展性           | 写单点       | 写单点          | 水平扩展          |
                | 运维复杂度       | 低           | 中              | 高               |
                | 客户端要求       | 简单         | 感知哨兵        | Smart Client     |
                | 适用场景         | 读多写少     | 高可用要求      | 大数据量+高并发   |
                | 最小节点数       | 2            | 3               | 6 (3主3从)       |
                +------------------+-------------+----------------+------------------+
                """);

        System.out.println("场景选择:");
        System.out.println("  数据量<10G + 读多写少  -> 主从 + 哨兵");
        System.out.println("  数据量>100G + 高并发   -> Cluster 分片");
        System.out.println("  QPS<5万 + 高可用要求   -> 哨兵");
        System.out.println("  QPS>10万 + 需水平扩展  -> Cluster\n");
    }

    static void why16384Slots() {
        System.out.println("--- Redis Cluster 为什么用 16384 个槽? ---");

        System.out.println("心跳包大小分析:");
        int slots16384 = 16384;
        int slots65536 = 65536;

        double heartbeat16384 = slots16384 / 8.0;
        double heartbeat65536 = slots65536 / 8.0;

        System.out.printf("  16384 槽: 心跳包 bitmap = %.0f bytes (%.1f KB)%n",
                heartbeat16384, heartbeat16384 / 1024);
        System.out.printf("  65536 槽: 心跳包 bitmap = %.0f bytes (%.1f KB)%n",
                heartbeat65536, heartbeat65536 / 1024);

        System.out.println("\n关键原因:");
        System.out.println("  1. 心跳包大小: 16384槽 -> 2KB, 65536槽 -> 8KB");
        System.out.println("  2. Gossip 协议: 节点间频繁交换心跳, 2KB 网络开销可接受");
        System.out.println("  3. 节点数上限: 官方建议 <=1000 节点, 16384 槽足够分配");
        System.out.println("  4. CRC16 算法: 对 16384 取模, 分布均匀性良好");

        System.out.println("\nSlot 分布示例 (3 主节点):");
        int[] masterSlots = {0, 5461, 10923};
        String[] keys = {"user:1001", "order:5002", "product:888",
                "session:abc", "{user}:profile", "{user}:settings"};
        for (String key : keys) {
            int slot = crc16Slot(key);
            int masterIdx = slot / (16384 / 3);
            System.out.printf("  %-20s slot=%-5d -> Master%d%n", key, slot, masterIdx + 1);
        }
        System.out.println();
    }

    static void dataSkewDemo() {
        System.out.println("--- 数据倾斜问题 ---");

        System.out.println("[场景] 电商大促, 热门商品数据集中在某个 slot:");
        String[] hotSkuIds = {"sku:100001", "sku:100002", "sku:100003"};

        for (String skuId : hotSkuIds) {
            int slot = crc16Slot(skuId);
            System.out.printf("  %s -> slot=%d%n", skuId, slot);
        }
        System.out.println("  热点商品都落在同一节点 -> 该节点 CPU/内存/网络 成瓶颈");

        System.out.println("\n[解决] hash_tag 将相关 key 落到同一 slot:");
        String[] taggedKeys = {"{hot}:sku:100001", "{hot}:sku:100002", "{hot}:sku:100003"};
        for (String key : taggedKeys) {
            int slot = crc16Slot(key);
            System.out.printf("  %s -> slot=%d%n", key, slot);
        }
        System.out.println("  hash_tag 确保同一业务 key 在同一节点");

        System.out.println("\n倾斜解决方案:");
        System.out.println("  1. hash_tag: 业务上拆分热点数据到不同 tag");
        System.out.println("  2. 业务拆分: 热 key 前缀加随机后缀分散到多 slot");
        System.out.println("  3. Proxy 层: 热 key 请求在 Proxy 层做本地缓存");
        System.out.println("  4. 读写分离: 热 key 读取分散到多个 Slave");
        System.out.println("  5. 迁移 slot: 将热点 slot 迁移到更高配置节点\n");
    }

    static int crc16Slot(String key) {
        String effectiveKey = key;
        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}', start);
            if (end != -1 && end > start + 1) {
                effectiveKey = key.substring(start + 1, end);
            }
        }
        CRC32 crc = new CRC32();
        crc.update(effectiveKey.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 16384);
    }
}