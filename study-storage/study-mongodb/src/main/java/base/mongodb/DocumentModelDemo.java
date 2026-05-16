package base.mongodb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * MongoDB 文档模型核心原理手写模拟
 * BSON vs JSON: BSON 是二进制编码格式, 支持更多类型 (Date/Binary/ObjectId 等), 解析比 JSON 快
 * 嵌套文档 vs 引用 (DBRef): 嵌套为一对一/一对少场景, 一并读取; 引用用于多对多/大数据, 手动关联
 * 集合等价关系: MongoDB 的 collection 相当于 RDBMS 的 table + 行可变 schema
 * ObjectId 12 字节: 4B Unix 时间戳 + 5B 随机值 (曾含 3B MAC+2B PID) + 3B 递增计数器
 */
public class DocumentModelDemo {

    public static void main(String[] args) {
        System.out.println("========== MongoDB 文档模型核心原理演示 ==========\n");

        bsonVsJsonDemo();
        nestedVsRefDemo();
        collectionEquivalenceDemo();
        objectIdDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 1. BSON vs JSON ====================

    /**
     * 模拟 BSON 二进制编码与 JSON 文本编码的对比
     * BSON 核心优势: 长度前缀快速跳过, 类型标记快速解析, 可存储二进制数据
     */
    static void bsonVsJsonDemo() {
        System.out.println("--- BSON vs JSON 编码对比 ---");

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", 1);
        doc.put("name", "zhangsan");
        doc.put("age", 28);
        doc.put("score", 99.5);
        doc.put("tags", Arrays.asList("java", "mongodb"));

        // JSON 文本编码
        String json = jsonEncode(doc);
        System.out.println("JSON 编码 (" + json.getBytes(StandardCharsets.UTF_8).length + " bytes): " + json);

        // BSON 二进制编码
        byte[] bson = bsonEncode(doc);
        System.out.println("BSON 编码 (" + bson.length + " bytes): " + bytesToHex(bson));
        System.out.println("BSON 优势: 长度前缀可快速跳过文档, 类型标记直接解析, 可内嵌二进制数据\n");
    }

    /** JSON 简易编码 -- 递归转字符串 */
    static String jsonEncode(Object value) {
        if (value instanceof String) {
            return "\"" + value + "\"";
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(jsonEncode(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        } else if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            Map<?, ?> map = (Map<?, ?>) value;
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(", ");
                sb.append("\"").append(e.getKey()).append("\": ").append(jsonEncode(e.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /**
     * BSON 简易编码 -- 每条记录: [类型1B][键名\0][值...], 文档以 \x00 结尾
     * 类型: 0x10=int32, 0x02=string, 0x01=double, 0x04=array
     */
    static byte[] bsonEncode(Map<String, Object> doc) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.putInt(0); // 总长度占位

        for (Map.Entry<String, Object> e : doc.entrySet()) {
            Object val = e.getValue();
            byte[] keyBytes = e.getKey().getBytes(StandardCharsets.UTF_8);

            if (val instanceof Integer) {
                buf.put((byte) 0x10); // int32 类型
                buf.put(keyBytes);
                buf.put((byte) 0); // 键名结束
                buf.putInt((Integer) val);
            } else if (val instanceof Double) {
                buf.put((byte) 0x01); // double 类型
                buf.put(keyBytes);
                buf.put((byte) 0);
                buf.putDouble((Double) val);
            } else if (val instanceof String) {
                buf.put((byte) 0x02); // string 类型
                buf.put(keyBytes);
                buf.put((byte) 0);
                byte[] strBytes = ((String) val).getBytes(StandardCharsets.UTF_8);
                buf.putInt(strBytes.length + 1);
                buf.put(strBytes);
                buf.put((byte) 0);
            } else if (val instanceof List) {
                buf.put((byte) 0x04); // array 类型
                buf.put(keyBytes);
                buf.put((byte) 0);
                List<?> list = (List<?>) val;
                int arrStart = buf.position();
                buf.putInt(0); // 数组长度占位
                for (int i = 0; i < list.size(); i++) {
                    String idx = String.valueOf(i);
                    Object item = list.get(i);
                    if (item instanceof String) {
                        buf.put((byte) 0x02);
                        buf.put(idx.getBytes(StandardCharsets.UTF_8));
                        buf.put((byte) 0);
                        byte[] s = ((String) item).getBytes(StandardCharsets.UTF_8);
                        buf.putInt(s.length + 1);
                        buf.put(s);
                        buf.put((byte) 0);
                    }
                }
                buf.put((byte) 0); // 数组结束
                int arrEnd = buf.position();
                buf.putInt(arrStart, arrEnd - arrStart);
            }
        }
        buf.put((byte) 0); // 文档结束标记
        int totalLen = buf.position();
        buf.putInt(0, totalLen);

        byte[] result = new byte[totalLen];
        buf.flip();
        buf.get(result);
        return result;
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    // ==================== 2. 嵌套文档 vs 引用 ====================

    /**
     * 嵌套文档 vs 引用 (DBRef) 对照
     * 嵌套: 子文档完整嵌入父文档中, 一次查询全量获取, 适合一对一/一对少量
     * 引用: 只存关联 ID, 需要二次查询, 适合多对多/大数据量
     * MongoDB 建议: 高频一起读的用嵌套, 独立频繁更新的用引用
     */
    static void nestedVsRefDemo() {
        System.out.println("--- 嵌套文档 vs 引用 (DBRef) ---");

        // 嵌套文档方式: 用户及其地址一起存储
        Map<String, Object> userNested = new LinkedHashMap<>();
        userNested.put("_id", 1);
        userNested.put("name", "zhangsan");
        Map<String, Object> addr = new LinkedHashMap<>();
        addr.put("city", "Beijing");
        addr.put("street", "ChangAn");
        addr.put("zip", "100000");
        userNested.put("address", addr); // 嵌套子文档

        System.out.println("嵌套文档模型 (1 次查询全量获取):");
        System.out.println("  " + jsonEncode(userNested));
        System.out.println("  适用: 订单-收货地址 (一对一, 经常一起读)");

        // 引用方式: 用户只存地址 ID
        Map<String, Object> userRef = new LinkedHashMap<>();
        userRef.put("_id", 1);
        userRef.put("name", "zhangsan");
        userRef.put("address_id", "addr_001"); // 引用外键

        Map<String, Object> addressDoc = new LinkedHashMap<>();
        addressDoc.put("_id", "addr_001");
        addressDoc.put("city", "Beijing");
        addressDoc.put("street", "ChangAn");

        System.out.println("\n引用模型 (2 次查询: 先查用户, 再按 address_id 查地址):");
        System.out.println("  用户文档: " + jsonEncode(userRef));
        System.out.println("  地址文档: " + jsonEncode(addressDoc));
        System.out.println("  适用: 商品-分类 (多对多, 分类独立更新)\n");
    }

    // ==================== 3. 集合等价关系 ====================

    /**
     * Collection 与 RDBMS 的等价关系对照
     * MongoDB 集合无 schema 约束, 同一集合中文档字段可以不同 (灵活但需自行约束)
     */
    static void collectionEquivalenceDemo() {
        System.out.println("--- MongoDB 集合 vs RDBMS 表 ---");

        TableEquivalence eq = new TableEquivalence();

        System.out.println("RDBMS 表: " + eq.rdbmsEquivalent());
        System.out.println("MongoDB 集合: " + eq.mongoEquivalent());
        System.out.println("关键差异: MongoDB 集合无 schema, 同一集合内文档结构可不同");
        System.out.println("  db.users.insertOne({\"name\":\"zhangsan\", \"age\": 28})");
        System.out.println("  db.users.insertOne({\"name\":\"lisi\", \"age\": 30, \"email\":\"lisi@test.com\"})");
        System.out.println("  以上两条文档字段不同但可共存于同一集合\n");
    }

    static class TableEquivalence {
        String rdbmsEquivalent() {
            return "Table users  { id INT, name VARCHAR, age INT, ... } -- 固定列, 新增列需 ALTER TABLE";
        }
        String mongoEquivalent() {
            return "Collection users  { _id, name, age, ... } -- 无 schema, 文档结构灵活";
        }
    }

    // ==================== 4. ObjectId 生成 ====================

    /**
     * ObjectId 12 字节结构: [4B 秒级时间戳] + [5B 随机值] + [3B 自增计数器]
     * 历史: 5B 曾分解为 3B 机器标识 (MAC 哈希) + 2B 进程 ID
     * MongoDB 5.0+: 5B 改为纯随机值, 提升安全性 (不暴露机器信息)
     */
    static void objectIdDemo() {
        System.out.println("--- ObjectId 12 字节生成 ---");

        ObjectIdGenerator gen = new ObjectIdGenerator();

        for (int i = 0; i < 4; i++) {
            byte[] oid = gen.next();
            System.out.printf("ObjectId [%d]: %s%n", i, hexBytes(oid));
            System.out.printf("  -> timestamp=%d (%s)%n", extractTimestamp(oid), Instant.ofEpochSecond(extractTimestamp(oid)));
        }

        System.out.println("\nObjectId 特点:");
        System.out.println("  1. 时间戳在前, 天然按时间排序 (无需额外的 created_at 索引)");
        System.out.println("  2. 全局唯一, 无需中心 ID 生成器");
        System.out.println("  3. 12 字节, 比 UUID (36 字符) 小很多");
        System.out.println("  4. 客户端生成, 无需 DB 往返\n");
    }

    static long extractTimestamp(byte[] oid) {
        return ((oid[0] & 0xFFL) << 24) | ((oid[1] & 0xFFL) << 16) |
               ((oid[2] & 0xFFL) << 8) | (oid[3] & 0xFFL);
    }

    static String hexBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /** ObjectId 生成器: 4B timestamp + 5B random + 3B counter */
    static class ObjectIdGenerator {
        private final SecureRandom random = new SecureRandom();
        private int counter = new SecureRandom().nextInt(0xFFFFFF);

        byte[] next() {
            byte[] oid = new byte[12];
            int timestamp = (int) (System.currentTimeMillis() / 1000);
            // 4 字节时间戳 (大端)
            oid[0] = (byte) (timestamp >> 24);
            oid[1] = (byte) (timestamp >> 16);
            oid[2] = (byte) (timestamp >> 8);
            oid[3] = (byte) timestamp;
            // 5 字节随机值
            byte[] randomBytes = new byte[5];
            random.nextBytes(randomBytes);
            System.arraycopy(randomBytes, 0, oid, 4, 5);
            // 3 字节计数器 (大端)
            int cnt = counter++ & 0xFFFFFF;
            oid[9] = (byte) (cnt >> 16);
            oid[10] = (byte) (cnt >> 8);
            oid[11] = (byte) cnt;
            return oid;
        }
    }
}