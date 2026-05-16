package base.generics;

import java.util.ArrayList;
import java.util.List;

/**
 * PECS 实战：用泛型设计一个通用的数据处理器
 *
 * PECS = Producer Extends, Consumer Super
 * - 从集合读取数据 → ? extends T（生产者）
 * - 向集合写入数据 → ? super T（消费者）
 */
public class PECSDemo {

    public static void main(String[] args) {
        List<Number> numbers = new ArrayList<>();
        List<Integer> integers = new ArrayList<>();
        integers.add(1);
        integers.add(2);
        integers.add(3);

        transferData(integers, numbers);
        System.out.println("转移结果: " + numbers);
    }

    /**
     * 从 src 读取（生产者）→ ? extends T
     * 向 dest 写入（消费者）→ ? super T
     */
    static <T> void transferData(List<? extends T> src, List<? super T> dest) {
        for (T item : src) {
            dest.add(item);
        }
    }
}