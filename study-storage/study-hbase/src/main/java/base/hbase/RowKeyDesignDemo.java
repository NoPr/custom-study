package base.hbase;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * HBase RowKey 设计核心方案纯 Java 模拟。
 * 散列法 (MD5 前 4 位反转, 打散热点) + 加盐法 (随机前缀 N 个 bucket, 区间查询需跨桶 Scan)
 * + 反转法 (手机号反转, 高位区分度提升) + 字符串逆序 (时间戳反转防止 Region 热点写)
 * + 预分区设计 (splitKeys 规划 Region 边界, 避免自动 split 开销)。
 *
 * <p>设计原则:
 * 1. 长度不宜过长 (50~100 字节为宜) —— 每个 Cell 都存储 RowKey
 * 2. 散列性 (分散写入到不同 Region, 避免热点)
 * 3. 业务查询导向 (把高频查询条件放到 RowKey 高位)
 * 4. 唯一性 (RowKey 同时也是主键)
 * 5. 避免单调递增 (时间戳裸拼会导致写入 Region 热点)
 */
public class RowKeyDesignDemo {

    public static void main(String[] args) {
        System.out.println("========== HBase RowKey 设计演示 ==========\n");

        hashMethodDemo();
        saltMethodDemo();
        reverseMethodDemo();
        timestampReverseDemo();
        preSplitRegionDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /* ===================== 1. 散列法 -- MD5 前 4 位反转 ===================== */

    /**
     * 对原始 RowKey 做 MD5 哈希, 取前 4 个十六进制字符, 反转后拼接到原 RowKey 前面。
     * 反转是为了进一步打散相邻业务的 RowKey, 避免同一前缀堆积在相邻 Region。
     *
     * <p>例如: userId="10001" -> md5="c4ca4..." -> 取前4位"c4ca" -> 反转"ac4c" -> RowKey="ac4c_10001"
     */
    static void hashMethodDemo() {
        System.out.println("--- 1. 散列法: MD5 前 4 位反转 ---");

        String[] originalKeys = {"user_10001", "user_10002", "user_10003",
                                  "user_20001", "user_30001", "user_99999"};

        List<String> rowKeys = new ArrayList<>();
        for (String key : originalKeys) {
            String hashPrefix = md5PrefixReverse(key, 4);
            String rowKey = hashPrefix + "_" + key;
            rowKeys.add(rowKey);
        }

        /* 按 RowKey 排序, 模拟 HBase 字典序存储, 观察散列效果 */
        Collections.sort(rowKeys);
        System.out.println("原始 Key -> MD5前4位反转 -> 最终 RowKey (按字典序排列):");
        for (String rk : rowKeys) {
            System.out.printf("  %s%n", rk);
        }
        System.out.println("  效果: 相邻 userId (10001~10003) 的 RowKey 被打散到不同区域, 写入分散到多个 Region\n");
    }

    /**
     * 计算字符串的 MD5, 取前 {@code length} 个十六进制字符, 反转后返回。
     */
    static String md5PrefixReverse(String input, int length) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            String prefix = hex.substring(0, length);
            return new StringBuilder(prefix).reverse().toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /* ===================== 2. 加盐法 -- 随机前缀 + 区间查询 ===================== */

    /**
     * 在 RowKey 前拼接随机盐值 (salt), 将数据分散到 N 个 bucket。
     * 查询时需要遍历所有 bucket, 即对 RowKey 前缀做多前缀 Scan。
     *
     * <p>盐值范围: [0, BUCKET_COUNT - 1]。
     * 写入: RowKey = salt + "_" + originalKey
     * 读取: 扫描 "0_", "1_", "2_" 所有前缀 (或根据业务缩小范围)
     */
    static void saltMethodDemo() {
        System.out.println("--- 2. 加盐法: 随机前缀 + 区间查询 ---");

        final int BUCKET_COUNT = 4;
        Random random = new Random(42); // 固定种子便于演示

        System.out.printf("  桶数量: %d%n", BUCKET_COUNT);

        /* 模拟写入: 将 8 条数据随机分配到不同桶 */
        String[] orders = {"order_101", "order_102", "order_103", "order_104",
                            "order_201", "order_202", "order_301", "order_302"};
        List<RowKeyEntry> table = new ArrayList<>();

        for (String order : orders) {
            int salt = random.nextInt(BUCKET_COUNT);
            String rowKey = salt + "_" + order;
            table.add(new RowKeyEntry(rowKey, order));
        }

        table.sort(Comparator.comparing(e -> e.rowKey));
        System.out.println("  写入后的数据 (按 RowKey 字典序):");
        for (RowKeyEntry e : table) {
            System.out.printf("    RowKey=%s%n", e.rowKey);
        }

        /* 模拟查询全部: 需要 Scan 所有桶前缀 */
        System.out.println("  查询流程: 需要 Scan 所有前缀 ->");
        for (int i = 0; i < BUCKET_COUNT; i++) {
            String prefix = i + "_";
            System.out.printf("    第%d次 Scan: startRow=%s, stopRow=%s%n", i + 1, prefix, prefix + "~");
        }
        System.out.println("  缺点: 查询放大 N 倍, 适合写入多读取少的场景\n");
    }

    /** RowKey 条目辅助类 */
    record RowKeyEntry(String rowKey, String originalKey) {}

    /* ===================== 3. 反转法 -- 手机号反转 ===================== */

    /**
     * 将手机号反转后作为 RowKey, 使手机号的低位变高位, 提高前缀区分度。
     *
     * <p>原始手机号: 13800001111, 13800002222, 13800003333
     * 前 7 位完全相同 "1380000", 按字典序会堆积在同一个 Region。
     * 反转后: "11110000831", "22220000831", "33330000831"
     * 高位变为 1/2/3, 写入立即分散到不同 Region。
     */
    static void reverseMethodDemo() {
        System.out.println("--- 3. 反转法: 手机号反转 ---");

        String[] phones = {"13800001111", "13800002222", "13800003333",
                            "13700001111", "13900001111"};

        System.out.println("  原始手机号 -> 反转后 RowKey (按字典序排列):");
        List<String> reversedKeys = new ArrayList<>();
        for (String phone : phones) {
            String reversed = new StringBuilder(phone).reverse().toString();
            reversedKeys.add(reversed);
        }
        Collections.sort(reversedKeys);
        for (String rk : reversedKeys) {
            /* 还原显示 */
            String original = new StringBuilder(rk).reverse().toString();
            System.out.printf("    %s -> %s (前缀 '%s' 区分不同号段)%n", original, rk, rk.substring(0, 4));
        }
        System.out.println("  优点: 低开销, 仅反转字符串, 不增加 RowKey 长度\n");
    }

    /* ===================== 4. 字符串逆序 -- 时间戳反转防热点 ===================== */

    /**
     * 针对时间序列数据, 将时间戳进行反转 (或使用 Long.MAX_VALUE - timestamp) 再拼接到 RowKey。
     * 避免连续时间戳的 RowKey 写入同一个 Region (热点写)。
     *
     * <p>原始: timestamp=1700000000 -> RowKey="sensor_01_1700000000"
     * 随时间增加, 新写入始终落在最后一个 Region。
     * 反转: timestamp=1700000000 -> reversed=0000000071 -> RowKey="sensor_01_0000000071"
     * 新写入的 RowKey 前缀更有随机性, 分散到不同 Region。
     */
    static void timestampReverseDemo() {
        System.out.println("--- 4. 字符串逆序: 时间戳反转防热点 ---");

        long baseTs = 1700000000L;
        /* 模拟连续 6 个时间点的传感器数据 */
        String[] sensorIds = {"sensor_01", "sensor_02", "sensor_01", "sensor_01",
                               "sensor_03", "sensor_02"};
        List<String> originalKeys = new ArrayList<>();
        List<String> reversedKeys = new ArrayList<>();

        System.out.println("  方案对比 (按 RowKey 字典序):");
        for (int i = 0; i < 6; i++) {
            long ts = baseTs + i * 100;
            String originalRk = sensorIds[i] + "_" + ts;
            originalKeys.add(originalRk);

            /* 两种反转策略 */
            String reversedTs1 = String.format("%010d", Long.MAX_VALUE - ts);
            String reversedTs2 = new StringBuilder(String.format("%010d", ts)).reverse().toString();
            String reversedRk = sensorIds[i] + "_" + reversedTs2;
            reversedKeys.add(reversedRk);
        }

        Collections.sort(originalKeys);
        System.out.println("    原始 (按字典序, 同 sensor 连续堆积):");
        for (String rk : originalKeys.subList(0, 3)) {
            System.out.printf("      %s%n", rk);
        }
        System.out.println("    ... (全部写入同一 Region)");

        Collections.sort(reversedKeys);
        System.out.println("    反转时间戳后 (按字典序, 写入分散):");
        for (String rk : reversedKeys.subList(0, 4)) {
            System.out.printf("      %s%n", rk);
        }
        System.out.println("  策略: 时间戳反转 或 Long.MAX_VALUE - timestamp\n");
    }

    /* ===================== 5. 预分区设计 -- splitKeys ===================== */

    /**
     * 预分区: 在建表时通过 splitKeys 指定 Region 切分边界,
     * 避免自动 Region Split 的性能开销。
     *
     * <p>RowKey 范围 [00, FF] (两位十六进制), 预分 4 个 Region:
     * Region-0: (-inf,  40]
     * Region-1: (40,    80]
     * Region-2: (80,    C0]
     * Region-3: (C0,   +inf)
     *
     * <p>建表命令示意:
     * create 'table_name', 'cf', SPLITS => ['40', '80', 'C0']
     */
    static void preSplitRegionDemo() {
        System.out.println("--- 5. 预分区设计 ---");

        /* 假设 RowKey 为 2 位十六进制散列前缀 + "_" + 业务 ID */
        String[] hexPrefixes = {"00", "40", "80", "C0", "FF"};
        String[][] boundaries = {
                {"-inf", "40"},
                {"40", "80"},
                {"80", "C0"},
                {"C0", "+inf"}
        };

        System.out.println("  建表 splitKeys: ['40', '80', 'C0']");
        System.out.println("  Region 分布:");
        for (int i = 0; i < boundaries.length; i++) {
            System.out.printf("    Region-%d: RowKey 范围 [%s, %s]%n", i, boundaries[i][0], boundaries[i][1]);
        }

        /* 模拟数据路由 */
        String[][] testKeys = {
                {"1a_张三", "Region-0"},
                {"5f_李四", "Region-1"},
                {"a3_王五", "Region-2"},
                {"dd_赵六", "Region-3"},
        };
        System.out.println("  数据路由示例:");
        for (String[] test : testKeys) {
            String rowKey = test[0];
            String prefix = rowKey.substring(0, 2);
            int region;
            if (prefix.compareTo("40") <= 0) region = 0;
            else if (prefix.compareTo("80") <= 0) region = 1;
            else if (prefix.compareTo("C0") <= 0) region = 2;
            else region = 3;
            System.out.printf("    RowKey=%s -> Region-%d (预期=%s)%n", rowKey, region, test[1]);
        }
        System.out.println("  预分区优势: 建表即确定边界, 避免自动 split 造成的 IO 抖动和读写阻塞\n");
    }
}