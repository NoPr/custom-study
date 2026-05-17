package base.collection;

/**
 * HashMap put 方法源码级分析（JDK 8）
 *
 * 完整调用链：
 * put(K key, V value)
 *   └─ putVal(hash(key), key, value, false, true)
 *        ├─ 1. table 为空 → resize() 初始化
 *        ├─ 2. (n-1)&hash 位置为空 → 直接插入
 *        ├─ 3. 头节点 key 相同 → 直接替换
 *        ├─ 4. 头节点是 TreeNode → 红黑树插入
 *        ├─ 5. 否则链表遍历：
 *        │    ├─ 找到相同 key → 替换
 *        │    └─ 遍历到末尾 → 尾插法（JDK 8）
 *        │         └─ 链表长度 >= 8 → treeifyBin()
 *        └─ 6. size > threshold → resize()
 *
 * resize() 扩容机制（JDK 8）：
 * 1. 容量翻倍（16 → 32 → 64 → ...）
 * 2. 每个桶的元素重新计算索引：(n-1)&hash
 *    新索引 = 旧索引 或 旧索引 + 旧容量（因为 n 扩大 1 位）
 * 3. 红黑树节点数 <= 6 时退化为链表
 *
 * 为什么容量必须是 2 的幂？
 * - (n-1) & hash 等价于 hash % n，位运算比取模快
 * - 扩容时重新分配简单：只需要判断 hash & oldCap 是否为 0
 *
 * 为什么负载因子是 0.75？
 * - 时间与空间的折中
 * - 0.75 时泊松分布下链表长度 > 8 的概率仅 0.00000006
 */
public class HashMapSourceAnalysis {

    /**
     * hash() 扰动函数 — JDK 8 HashMap.hash(Object key) 的等价实现
     *
     * 作用：让 hashCode 的高 16 位也参与低位运算
     * 因为 (n-1)&hash 只用到 hash 的低位（n 通常 < 2^16），
     * 如果直接在 hashCode 上取模，高位差异完全被忽略。
     * 异或运算让高位特征混入低位，减少碰撞。
     */
    static int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    /**
     * tableSizeFor(int cap) — 返回 >= cap 的最小 2 次幂
     *
     * 例如：cap=10 → 16, cap=17 → 32, cap=1 → 1
     *
     * 原理：通过 5 次无符号右移 + 按位或，将最高位 1 之后的所有位
     * 全部变成 1，最后 +1 得到 2 次幂。
     *
     * 步骤拆解（cap=10 为例）：
     * n = 10-1 = 9 = 1001
     * n |= n>>>1:  1001 | 0100 = 1101
     * n |= n>>>2:  1101 | 0011 = 1111
     * n |= n>>>4:  1111 | 0000 = 1111 = 15
     * return n+1 = 16
     */
    static int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : n + 1;
    }

    /**
     * 扩容时新索引计算 — JDK 8 的优化手段
     *
     * 不需要重新计算 hash，只需判断 hash & oldCap：
     * - 0 → 新索引 = 旧索引（留在原位置）
     * - 非 0 → 新索引 = 旧索引 + 旧容量（移到高位）
     *
     * 因为容量翻倍后，(n-1) 的二进制多了一位 1，
     * 该位刚好对应 oldCap 的最高位。
     */
    static int newIndexAfterResize(int hash, int oldCap) {
        return (hash & oldCap) == 0
                ? hash & (oldCap - 1)
                : (hash & (oldCap - 1)) + oldCap;
    }

    public static void main(String[] args) {
        System.out.println("=== HashMap 源码级分析 ===\n");

        System.out.println("【hash 扰动】");
        System.out.println("  hash(\"abc\"):  " + hash("abc"));
        System.out.println("  hash(\"abc\"):  " + hash("abc") + " (相同 key，hash 一致)");
        System.out.println("  hash(\"abd\"):  " + hash("abd") + " (不同 key)");
        System.out.println("  hash(null):    " + hash(null) + "   (null key hash = 0)");

        System.out.println("\n【tableSizeFor — 取 >= cap 的最小 2 次幂】");
        int[] testCaps = {1, 3, 10, 16, 17, 100, 1000, 1_000_000};
        for (int cap : testCaps) {
            System.out.printf("  tableSizeFor(%d) = %d%n", cap, tableSizeFor(cap));
        }

        System.out.println("\n【扩容后新索引 — 无需重新计算 hash】");
        int oldCap = 16;
        int[] hashes = {hash("a"), hash("b"), hash("c"), hash("d")};
        for (int h : hashes) {
            int oldIndex = h & (oldCap - 1);
            int newIdx = newIndexAfterResize(h, oldCap);
            int newIdxBruteForce = h & (oldCap * 2 - 1);
            System.out.printf("  hash=%-12d oldIndex=%-2d newIndex=%-2d (暴力计算=%-2d, 匹配=%b)%n",
                    h, oldIndex, newIdx, newIdxBruteForce, newIdx == newIdxBruteForce);
        }
    }
}
