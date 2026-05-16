package base.redis;

import java.util.*;

/**
 * Redis 五种数据结构底层原理手写模拟
 * SDS (动态字符串) -- 二进制安全、O(1) 长度、预分配防溢出
 * Ziplist (压缩列表) -- 连续内存、省内存、适合小数据
 * Skiplist (跳跃表) -- ZSet 底层、O(logN) 查找、多层索引加速
 * Hash 渐进式 rehash -- 分批迁移、双表查询、避免阻塞
 */
public class DataStructureDemo {

    /**
     * 依次演示 SDS、Ziplist、Skiplist、渐进式 rehash 四种底层实现
     */
    public static void main(String[] args) {
        System.out.println("========== Redis 数据结构底层原理演示 ==========\n");

        sdsDemo();
        ziplistDemo();
        skiplistDemo();
        hashRehashDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    static void sdsDemo() {
        System.out.println("--- SDS (Simple Dynamic String) 模拟 ---");
        SDS sds = new SDS("hello");
        System.out.println("原始: " + sds + " | len=" + sds.len + " free=" + sds.free + " capacity=" + sds.capacity());

        sds.append(" world");
        System.out.println("追加后: " + sds + " | len=" + sds.len + " free=" + sds.free + " capacity=" + sds.capacity());

        sds.append("!");
        System.out.println("再追加: " + sds + " | len=" + sds.len + " free=" + sds.free + " capacity=" + sds.capacity());

        byte[] raw = sds.getBytes();
        System.out.println("获取二进制安全数据 (长度 " + raw.length + "): " + Arrays.toString(raw));
        System.out.println("C字符串 vs SDS: SDS O(1) 获取长度, 杜绝缓冲区溢出, 减少内存重分配\n");
    }

    static void ziplistDemo() {
        System.out.println("--- Ziplist 压缩列表编码模拟 ---");

        Ziplist zl = new Ziplist(64);
        zl.addEntry("name", "zhangsan");
        zl.addEntry("age", "25");
        zl.addEntry("score", "99.5");

        System.out.println("压缩列表总字节数: " + zl.zlbytes);
        System.out.println("尾部偏移量: " + zl.zltail);
        System.out.println("元素数量: " + zl.zllen);
        System.out.println("\n遍历所有 entry:");
        for (int i = 0; i < zl.entries.size(); i++) {
            Ziplist.Entry e = zl.entries.get(i);
            System.out.printf("  [%d] prevlen=%d encoding=%s data=%s%n",
                    i, e.prevlen, e.encoding, e.data);
        }
        System.out.println("Ziplist 特点: 连续内存紧凑存储, 适合小数据量, 省内存\n");
    }

    static void skiplistDemo() {
        System.out.println("--- Skiplist 跳跃表 (ZSet 底层) 模拟 ---");
        SkipList sl = new SkipList();
        sl.insert("zhangsan", 95.0);
        sl.insert("lisi", 88.0);
        sl.insert("wangwu", 92.5);
        sl.insert("zhaoliu", 88.0);
        sl.insert("sunqi", 97.0);

        System.out.println("跳跃表内容 (按分数排序):");
        sl.printAll();

        System.out.println("查找分数 [88.0, 95.0] 范围的元素:");
        sl.rangeSearch(88.0, 95.0);

        System.out.printf("平均查找复杂度 O(logN), 层级: %d\n", sl.maxLevel);
        System.out.println("Redis ZSet: 元素少时用 ziplist, 元素多时用 skiplist + dict\n");
    }

    static void hashRehashDemo() {
        System.out.println("--- Hash 渐进式 rehash 模拟 ---");
        HashDict dict = new HashDict(4);
        dict.put("key1", "value1");
        dict.put("key2", "value2");
        dict.put("key3", "value3");
        dict.put("key4", "value4");

        System.out.println("rehash 前: size=" + dict.used + " tableSize=" + dict.ht[0].size);
        dict.printTable(0);

        dict.expand(8);
        System.out.println("触发扩容, 新 table size=" + dict.ht[1].size);

        dict.rehashStep();
        System.out.println("渐进式 rehash 第1步后:");
        dict.printTable(0);
        dict.printTable(1);

        dict.rehashStep();
        System.out.println("渐进式 rehash 第2步后:");
        dict.printTable(0);
        dict.printTable(1);

        dict.rehashAll();
        System.out.println("全部 rehash 完成, ht[1] 变为 ht[0]:");
        dict.printTable(0);
        System.out.println("渐进式 rehash: 分批迁移, 避免阻塞, 期间双表查询\n");
    }
}

/**
 * SDS (Simple Dynamic String) 手写版 -- Redis 字符串底层实现
 * 记录 len/free/capacity 实现 O(1) 取长度, 预分配空间减少内存重分配,
 * 末尾 '\0' 兼容 C 字符串函数, 但存取按 len 保证二进制安全
 */
class SDS {
    int len;
    int free;
    char[] buf;

    SDS(String s) {
        len = s.length();
        free = Math.max(16, len * 2) - len;
        buf = new char[len + free + 1];
        s.getChars(0, len, buf, 0);
        buf[len] = '\0';
    }

    int capacity() { return len + free; }

    void append(String s) {
        int addLen = s.length();
        if (addLen > free) {
            int newTotal = (len + addLen) * 2;
            char[] newBuf = new char[newTotal + 1];
            System.arraycopy(buf, 0, newBuf, 0, len);
            buf = newBuf;
            free = newTotal - len;
        }
        s.getChars(0, addLen, buf, len);
        len += addLen;
        free -= addLen;
        buf[len] = '\0';
    }

    byte[] getBytes() {
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) bytes[i] = (byte) buf[i];
        return bytes;
    }

    @Override
    public String toString() { return new String(buf, 0, len); }
}

/**
 * Ziplist 压缩列表手写版 -- Redis Hash/ZSet 在小数据量时的底层结构
 * 连续内存紧凑存储, 省去指针开销, zlbytes/zltail/zllen 模拟元数据字段
 */
class Ziplist {
    int zlbytes;
    int zltail;
    int zllen;
    List<Entry> entries = new ArrayList<>();

    Ziplist(int initBytes) { zlbytes = initBytes; }

    void addEntry(String field, String value) {
        Entry e = new Entry();
        e.prevlen = zltail;
        e.encoding = "string";
        e.data = field + "=" + value;
        int entrySize = 1 + 1 + e.data.length();
        zltail += entrySize;
        zlbytes += entrySize;
        zllen++;
        entries.add(e);
    }

    /** 压缩列表中的一个 entry: prevlen 记录前驱长度, 支持反向遍历 */
    static class Entry {
        int prevlen;
        String encoding;
        String data;
    }
}

/**
 * SkipList 跳跃表手写版 -- ZSet 在大数据量时的底层结构
 * 多层索引加速查找: 最高层跨度大(快速定位), 下层精确查找, 平均 O(logN)
 * 插入时随机生成层数, 模拟 Redis 的 zslRandomLevel
 */
class SkipList {
    static final int MAX_LEVEL = 16;
    Node head;
    int maxLevel;
    Random random = new Random();

    SkipList() {
        head = new Node("", Double.NEGATIVE_INFINITY, MAX_LEVEL);
    }

    void insert(String member, double score) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = head;
        for (int i = maxLevel; i >= 0; i--) {
            while (x.next[i] != null &&
                    (x.next[i].score < score ||
                            (x.next[i].score == score && x.next[i].member.compareTo(member) < 0))) {
                x = x.next[i];
            }
            update[i] = x;
        }

        int level = randomLevel();
        if (level > maxLevel) {
            for (int i = maxLevel + 1; i <= level; i++) update[i] = head;
            maxLevel = level;
        }

        Node newNode = new Node(member, score, level);
        for (int i = 0; i <= level; i++) {
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }
    }

    void printAll() {
        Node x = head.next[0];
        while (x != null) {
            System.out.printf("  %s -> %.1f (level=%d)%n", x.member, x.score, x.level());
            x = x.next[0];
        }
    }

    void rangeSearch(double min, double max) {
        Node x = head.next[0];
        while (x != null && x.score < min) x = x.next[0];
        while (x != null && x.score <= max) {
            System.out.printf("  %s -> %.1f%n", x.member, x.score);
            x = x.next[0];
        }
    }

    private int randomLevel() {
        int level = 0;
        while (random.nextInt(4) == 0 && level < MAX_LEVEL - 1) level++;
        return level;
    }

    /** 跳跃表节点: 每层都有 next 指针, level 越高跨越范围越大 */
    static class Node {
        String member;
        double score;
        Node[] next;

        Node(String member, double score, int level) {
            this.member = member;
            this.score = score;
            this.next = new Node[level + 1];
        }

        int level() { return next.length - 1; }
    }
}

/**
 * Hash 字典 + 渐进式 rehash 手写版 -- Redis Hash 的底层实现
 * 双表结构 (ht[0] + ht[1]), 扩容时分批迁移 key, 迁移期间双表查询
 * 避免一次性 rehash 阻塞主线程 -- 这是 Redis 单线程高性能的关键设计
 */
class HashDict {
    static class HashTable {
        String[] keys;
        String[] vals;
        int size;
        int used;

        HashTable(int size) {
            this.size = size;
            keys = new String[size];
            vals = new String[size];
        }
    }

    HashTable[] ht = new HashTable[2];
    int used;
    int rehashIdx;

    HashDict(int size) {
        ht[0] = new HashTable(size);
    }

    void put(String key, String value) {
        if (ht[1] != null) {
            int idx = hash(key) & (ht[1].size - 1);
            ht[1].keys[idx] = key;
            ht[1].vals[idx] = value;
            ht[1].used++;
        } else {
            int idx = hash(key) & (ht[0].size - 1);
            ht[0].keys[idx] = key;
            ht[0].vals[idx] = value;
            ht[0].used++;
        }
        used++;
    }

    void expand(int newSize) {
        ht[1] = new HashTable(newSize);
        rehashIdx = 0;
    }

    void rehashStep() {
        if (ht[1] == null) return;
        int steps = 1;
        while (steps-- > 0 && ht[0].used > 0) {
            while (rehashIdx < ht[0].size && ht[0].keys[rehashIdx] == null) rehashIdx++;
            if (rehashIdx >= ht[0].size) break;
            String key = ht[0].keys[rehashIdx];
            String val = ht[0].vals[rehashIdx];
            int newIdx = hash(key) & (ht[1].size - 1);
            ht[1].keys[newIdx] = key;
            ht[1].vals[newIdx] = val;
            ht[0].keys[rehashIdx] = null;
            ht[0].vals[rehashIdx] = null;
            ht[0].used--;
            ht[1].used++;
            rehashIdx++;
        }
    }

    void rehashAll() {
        while (ht[0].used > 0) rehashStep();
        ht[0] = ht[1];
        ht[1] = null;
        rehashIdx = 0;
    }

    void printTable(int index) {
        HashTable t = ht[index];
        if (t == null) { System.out.println("  ht[" + index + "] = null"); return; }
        System.out.printf("  ht[%d]: size=%d used=%d ", index, t.size, t.used);
        for (int i = 0; i < t.size; i++) {
            if (t.keys[i] != null) System.out.printf("[%d:%s=%s] ", i, t.keys[i], t.vals[i]);
        }
        System.out.println();
    }

    private int hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        return h & 0x7fffffff;
    }
}