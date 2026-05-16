package base.redis;

import java.util.*;
import java.util.concurrent.*;

/**
 * Redis 调优实战: Slowlog 慢查询分析 + Pipeline 批量 + Lua 原子脚本 + 过期删除策略
 * Slowlog: KEYS/SMEMBERS 阻塞 -> SCAN/SSCAN 替代
 * Pipeline: 打包发送减少 RTT, 但不保证原子性
 * Lua: 原子执行 + 减少网络往返, 库存扣减经典场景
 * 过期策略: 惰性删除 (访问时检查) + 定期删除 (定时抽查) 结合
 */
public class RedisTuningDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== Redis 调优实战演示 ==========\n");

        slowlogDemo();
        pipelineDemo();
        luaScriptDemo();
        expirationDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void slowlogDemo() {
        System.out.println("--- Slowlog 慢查询分析模拟 ---");

        List<SlowlogEntry> slowlog = new ArrayList<>();
        long slowlogThresholdUs = 10_000;

        SlowlogEntry[] commands = {
                new SlowlogEntry(1, "KEYS *", 150_000),
                new SlowlogEntry(2, "HGETALL user:profile:10086", 3_500),
                new SlowlogEntry(3, "SORT mylist BY weight DESC", 45_000),
                new SlowlogEntry(4, "SMEMBERS online_users", 120_000),
                new SlowlogEntry(5, "GET counter", 200),
                new SlowlogEntry(6, "ZRANGE scoreboard 0 -1", 25_000),
        };

        for (SlowlogEntry cmd : commands) {
            if (cmd.execTimeUs > slowlogThresholdUs) {
                slowlog.add(cmd);
            }
        }

        System.out.println("慢查询阈值: " + slowlogThresholdUs / 1000 + "ms, 共记录 " +
                slowlog.size() + " 条慢查询:");
        for (SlowlogEntry entry : slowlog) {
            System.out.printf("  [id=%d] %s -> %.1fms%n",
                    entry.id, entry.command, entry.execTimeUs / 1000.0);
        }

        System.out.println("\n优化建议:");
        System.out.println("  KEYS *   -> 改用 SCAN 游标迭代");
        System.out.println("  SMEMBERS -> 改用 SSCAN 分批获取");
        System.out.println("  SORT     -> 数据排序在写入时完成, 用 ZSet 替代");
        System.out.println("  ZRANGE 0 -1 -> 限制范围, 避免全量返回\n");
    }

    static void pipelineDemo() {
        System.out.println("--- Pipeline 批量执行模拟 ---");

        int batchSize = 1000;
        int singleRttMs = 1;

        long withoutPipeline = (long) batchSize * singleRttMs;
        long withPipeline = singleRttMs + 10L;

        System.out.printf("单条发送: %d 条 x %dms RTT = %dms%n",
                batchSize, singleRttMs, withoutPipeline);
        System.out.printf("Pipeline:  %d 条打包发送 = %dms%n",
                batchSize, withPipeline);
        System.out.printf("性能提升: %.0f 倍%n", (double) withoutPipeline / withPipeline);

        System.out.println("\nPipeline 使用注意:");
        System.out.println("  1. 不要一次 pipeline 太多命令 (建议 100~1000)");
        System.out.println("  2. pipeline 不保证原子性, 中间命令可能失败");
        System.out.println("  3. 需要原子性用 Lua 脚本");
        System.out.println("  4. 集群模式下所有 key 必须在同 slot (用 hash_tag)\n");
    }

    static void luaScriptDemo() {
        System.out.println("--- Lua 脚本原子性演示 ---");

        System.out.println("[场景] 扣减库存, 保证原子性:");
        String luaScript = """
                local key = KEYS[1]
                local amount = tonumber(ARGV[1])
                local stock = tonumber(redis.call('GET', key))
                if stock == nil then
                    return -1
                end
                if stock < amount then
                    return 0
                end
                redis.call('DECRBY', key, amount)
                return stock - amount
                """.trim();

        System.out.println("Lua 脚本:");
        for (String line : luaScript.split("\n")) {
            System.out.println("  " + line);
        }

        System.out.println("\nJava 端模拟 EVAL:");
        Map<String, Integer> redisSim = new ConcurrentHashMap<>();
        redisSim.put("stock:1001", 10);
        System.out.println("  当前库存: " + redisSim.get("stock:1001"));

        int result = evalLuaSim(redisSim, "stock:1001", 3);
        System.out.printf("  EVAL 扣减3件 -> 返回: %d, 剩余库存: %d%n", result,
                redisSim.get("stock:1001"));

        result = evalLuaSim(redisSim, "stock:1001", 8);
        System.out.printf("  EVAL 扣减8件 -> 返回: %d (库存不足)%n", result);

        System.out.println("\nLua 脚本优势: 原子执行, 减少网络往返, 复杂逻辑服务端完成\n");
    }

    static void expirationDemo() {
        System.out.println("--- 过期键删除策略模拟 ---");

        Map<String, Long> expires = new ConcurrentHashMap<>();
        Map<String, String> data = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();

        for (int i = 0; i < 20; i++) {
            data.put("key:" + i, "value:" + i);
            expires.put("key:" + i, now + ThreadLocalRandom.current().nextLong(0, 3000));
        }

        System.out.println("创建 20 个带过期时间的 key...");

        System.out.println("\n[惰性删除] 访问时检查是否过期:");
        for (int i = 0; i < 5; i++) {
            String key = "key:" + (i * 4);
            Long expireAt = expires.get(key);
            boolean isExpired = now > expireAt;
            System.out.printf("  访问 %s expireAt=%d -> %s%n", key, expireAt,
                    isExpired ? "已过期, 惰性删除" : "未过期, 返回数据");
        }

        System.out.println("\n[定期删除] 每秒随机抽取部分 key 检查:");
        int checked = 0;
        int deleted = 0;
        for (Map.Entry<String, Long> entry : expires.entrySet()) {
            if (checked >= 5) break;
            checked++;
            if (now > entry.getValue()) {
                data.remove(entry.getKey());
                expires.remove(entry.getKey());
                deleted++;
            }
        }
        System.out.printf("  抽查 %d 个 key, 删除 %d 个过期 key%n", checked, deleted);
        System.out.println("  剩余 key 数: " + data.size());

        System.out.println("\n过期策略总结:");
        System.out.println("  惰性删除: 访问 key 时检查, CPU 友好但可能内存浪费");
        System.out.println("  定期删除: 定时扫描, 内存和 CPU 平衡");
        System.out.println("  Redis 实际: 两者结合使用\n");
    }

    static int evalLuaSim(Map<String, Integer> data, String key, int amount) {
        Integer stock = data.get(key);
        if (stock == null) return -1;
        if (stock < amount) return 0;
        data.put(key, stock - amount);
        return stock - amount;
    }

    /** 慢查询日志条目: 记录命令、执行耗时, 超过阈值则入库 slowlog */
    static class SlowlogEntry {
        int id;
        String command;
        long execTimeUs;

        SlowlogEntry(int id, String command, long execTimeUs) {
            this.id = id; this.command = command; this.execTimeUs = execTimeUs;
        }
    }
}