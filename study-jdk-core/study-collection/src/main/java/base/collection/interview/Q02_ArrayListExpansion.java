package base.collection.interview;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试题：ArrayList 扩容机制
 *
 * 核心知识点：
 * 1. ArrayList 底层是 Object[] elementData
 * 2. 无参构造：初始为空数组 DEFAULTCAPACITY_EMPTY_ELEMENTDATA，
 *    首次 add 时扩容到 DEFAULT_CAPACITY = 10
 * 3. 扩容公式：newCapacity = oldCapacity + (oldCapacity >> 1) ≈ 1.5 倍
 * 4. 如果 newCapacity 不够，直接用 minCapacity
 * 5. 如果 newCapacity > MAX_ARRAY_SIZE，调用 hugeCapacity()
 * 6. 最终通过 Arrays.copyOf 复制到新数组
 *
 * 完整调用链：
 * add(E e) → ensureCapacityInternal(size + 1) → ensureExplicitCapacity → grow
 *
 * grow(int minCapacity):
 *   oldCapacity = elementData.length
 *   newCapacity = oldCapacity + (oldCapacity >> 1)
 *   if (newCapacity - minCapacity < 0) → newCapacity = minCapacity
 *   if (newCapacity - MAX_ARRAY_SIZE > 0) → hugeCapacity(minCapacity)
 *   elementData = Arrays.copyOf(elementData, newCapacity)
 *
 * 对比 JDK 6：
 * JDK 6 扩容公式：newCapacity = (oldCapacity * 3) / 2 + 1，也是约 1.5 倍
 * JDK 7+ 改用位运算 oldCapacity + (oldCapacity >> 1)，更高效
 */
public class Q02_ArrayListExpansion {

    public static void main(String[] args) throws Exception {
        expansionProcess();
        expansionFormula();
        capacityVsSize();
        ensureCapacity();
    }

    /**
     * 演示扩容过程：从默认构造开始，逐步添加元素观察容量变化
     */
    static void expansionProcess() throws Exception {
        System.out.println("=== ArrayList 扩容过程演示 ===");

        List<String> list = new ArrayList<>();
        printCapacity(list, "new ArrayList()");

        list.add("A");
        printCapacity(list, "add 第 1 个");

        for (int i = 2; i <= 10; i++) {
            list.add("X");
        }
        printCapacity(list, "add 第 10 个（临界点）");

        list.add("Y");
        printCapacity(list, "add 第 11 个（触发扩容 10→15）");

        System.out.println();
    }

    /**
     * 扩容公式：oldCapacity + oldCapacity >> 1 即 1.5 倍
     * 各阶段：0→10→15→22→33→49→73→109→163→244→...
     */
    static void expansionFormula() {
        System.out.println("=== ArrayList 扩容公式 = oldCapacity + oldCapacity >> 1 ===");
        System.out.println("  (约 1.5 倍，即 1 + 0.5)");
        System.out.println();

        int capacity = 10;
        System.out.printf("  初始: %d → ", capacity);
        for (int i = 0; i < 8; i++) {
            int newCapacity = capacity + (capacity >> 1);
            System.out.printf("%d", newCapacity);
            capacity = newCapacity;
            if (i < 7) {
                System.out.print(" → ");
            }
        }
        System.out.println();
        System.out.println();
    }

    /**
     * 容量 vs 大小：list.size() 返回元素个数，不是数组容量
     */
    static void capacityVsSize() throws Exception {
        System.out.println("=== capacity vs size ===");

        List<Integer> list = new ArrayList<>(20);
        System.out.println("  new ArrayList(20)");
        System.out.println("    size:     " + list.size());
        System.out.println("    capacity: " + getCapacity(list));

        for (int i = 0; i < 10; i++) {
            list.add(i);
        }
        System.out.println("  add 10 个元素后");
        System.out.println("    size:     " + list.size());
        System.out.println("    capacity: " + getCapacity(list));
        System.out.println("  size() 返回的是元素个数，不是数组容量！");
        System.out.println();
    }

    /**
     * ensureCapacity：预分配容量，避免频繁扩容
     */
    static void ensureCapacity() throws Exception {
        System.out.println("=== ensureCapacity 预分配 ===");

        List<Integer> list = new ArrayList<>();
        System.out.println("  new ArrayList(): capacity = " + getCapacity(list));

        list.add(1);
        System.out.println("  add 1 个: capacity = " + getCapacity(list) + " (首次触发默认 10)");

        ((ArrayList<Integer>) list).ensureCapacity(1000);
        System.out.println("  ensureCapacity(1000): capacity = " + getCapacity(list));

        System.out.println("  已知数据量时用 ensureCapacity 或 new ArrayList(n) 避免扩容");
        System.out.println();
    }

    /**
     * 通过反射获取 ArrayList 内部 elementData 数组长度（真实容量）
     */
    static int getCapacity(List<?> list) throws Exception {
        Field field = ArrayList.class.getDeclaredField("elementData");
        field.setAccessible(true);
        Object[] elementData = (Object[]) field.get(list);
        return elementData.length;
    }

    static void printCapacity(List<?> list, String label) throws Exception {
        System.out.printf("  %-22s size=%-3d capacity=%-4d%n",
                label, list.size(), getCapacity(list));
    }
}