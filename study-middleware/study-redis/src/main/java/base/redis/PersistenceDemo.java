package base.redis;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Redis 持久化机制: RDB 快照 + AOF 命令日志 + AOF 重写 + 混合持久化
 * RDB: fork 子进程 COW 写快照, 恢复快但可能丢数据
 * AOF: 每条写命令追加, 更安全但文件大恢复慢
 * AOF 重写: 基于当前内存状态生成最小命令集, 去重中间过程
 * 混合模式 (Redis 4.0+): RDB 头部 + AOF 尾部, 兼顾恢复速度和数据完整性
 */
public class PersistenceDemo {

    private static final String AOF_FILE = "target/demo_appendonly.aof";
    private static final String RDB_FILE = "target/demo_dump.rdb";

    public static void main(String[] args) throws Exception {
        System.out.println("========== Redis 持久化机制演示 ==========\n");

        rdbDemo();
        aofDemo();
        aofRewriteDemo();
        hybridDemo();

        cleanup();
        System.out.println("\n========== 演示完毕 ==========");
    }

    static void rdbDemo() {
        System.out.println("--- RDB 快照持久化模拟 ---");
        Map<String, String> memory = new LinkedHashMap<>();
        memory.put("user:1", "zhangsan");
        memory.put("user:2", "lisi");
        memory.put("user:3", "wangwu");
        memory.put("counter", "100");

        System.out.println("当前内存数据: " + memory);

        System.out.println("主进程 fork() 子进程... (模拟 COW)");
        Map<String, String> snapshot = new LinkedHashMap<>(memory);

        memory.put("user:4", "zhaoliu");
        System.out.println("fork 后主进程写入 user:4=zhaoliu (COW 页复制)");
        System.out.println("主进程内存: " + memory);
        System.out.println("子进程快照: " + snapshot + " (不受影响)");

        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {}

        String rdbContent = snapshot.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        try {
            Files.writeString(Path.of(RDB_FILE), rdbContent);
            System.out.println("RDB 快照写入完成 -> " + RDB_FILE);
        } catch (IOException e) {
            System.out.println("RDB 写入失败: " + e.getMessage());
        }
        System.out.println("RDB 优点: 紧凑二进制, 恢复快; 缺点: 可能丢失最后快照后数据\n");
    }

    static void aofDemo() {
        System.out.println("--- AOF 命令追加持久化模拟 ---");
        StringBuilder aofBuffer = new StringBuilder();

        aofAppend(aofBuffer, "SET", "name", "zhangsan");
        aofAppend(aofBuffer, "SET", "age", "25");
        aofAppend(aofBuffer, "INCR", "counter", null);
        aofAppend(aofBuffer, "HSET", "user:1", "name=zhangsan age=25");
        aofAppend(aofBuffer, "DEL", "tempkey", null);

        System.out.println("AOF 缓冲区内容:");
        System.out.println(aofBuffer);

        try {
            Files.writeString(Path.of(AOF_FILE), aofBuffer.toString());
            System.out.println("AOF fsync 刷盘完成 -> " + AOF_FILE);
        } catch (IOException e) {
            System.out.println("AOF 写入失败: " + e.getMessage());
        }
        System.out.println("AOF 策略: always(每条fsync) / everysec(每秒) / no(OS控制)\n");
    }

    static void aofRewriteDemo() {
        System.out.println("--- AOF 重写模拟 ---");

        StringBuilder originalAof = new StringBuilder();
        aofAppend(originalAof, "SET", "counter", "1");
        aofAppend(originalAof, "SET", "counter", "2");
        aofAppend(originalAof, "SET", "counter", "3");
        aofAppend(originalAof, "SET", "counter", "100");
        aofAppend(originalAof, "SET", "name", "oldname");
        aofAppend(originalAof, "SET", "name", "newname");

        System.out.println("原始 AOF (6条命令):");
        System.out.println(originalAof);

        Map<String, String> currentData = new LinkedHashMap<>();
        currentData.put("counter", "100");
        currentData.put("name", "newname");

        StringBuilder rewrittenAof = new StringBuilder();
        for (Map.Entry<String, String> e : currentData.entrySet()) {
            aofAppend(rewrittenAof, "SET", e.getKey(), e.getValue());
        }

        System.out.println("AOF 重写后 (合并为 2 条):");
        System.out.println(rewrittenAof);
        double reduction = (1.0 - (double) rewrittenAof.length() / originalAof.length()) * 100;
        System.out.printf("体积缩小约 %.0f%%\n", reduction);
        System.out.println("重写基于当前内存状态, 去掉中间过程, 不阻塞主进程\n");
    }

    static void hybridDemo() {
        System.out.println("--- 混合持久化 (RDB-preamble) 模拟 ---");
        System.out.println("Redis 4.0+ 混合模式:");

        Map<String, String> memory = new LinkedHashMap<>();
        memory.put("key1", "val1");
        memory.put("key2", "val2");
        memory.put("key3", "val3");

        String rdbPart = memory.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "|" + b).orElse("");

        String aofPart = "SET key4 val4\nINCR counter\n";

        String hybridContent = "[RDB_PREAMBLE]" + rdbPart + "[AOF_TAIL]" + aofPart;

        System.out.println("混合持久化文件结构:");
        System.out.println(hybridContent);
        System.out.println("优势: 前半部分 RDB 恢复快, 后半部分 AOF 保证完整性");
        System.out.println("重启加载: 先读 RDB 部分快速恢复, 再重放 AOF 尾部增量\n");
    }

    static void aofAppend(StringBuilder buf, String cmd, String key, String value) {
        buf.append(String.format("*%d\r\n", value == null ? 2 : 3));
        buf.append(String.format("$%d\r\n%s\r\n", cmd.length(), cmd));
        buf.append(String.format("$%d\r\n%s\r\n", key.length(), key));
        if (value != null) {
            buf.append(String.format("$%d\r\n%s\r\n", value.length(), value));
        }
    }

    static void cleanup() {
        try { Files.deleteIfExists(Path.of(AOF_FILE)); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Path.of(RDB_FILE)); } catch (IOException ignored) {}
    }
}