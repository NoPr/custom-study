package base.generics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通配符与 PECS 原则
 *
 * 核心知识点：
 * 1. ? extends T — 上界通配符，只能读不能写（Producer Extends）
 * 2. ? super T   — 下界通配符，只能写不能读（Consumer Super）
 * 3. ?           — 无界通配符，等同于 ? extends Object
 * 4. PECS: Producer Extends, Consumer Super
 */
public class WildcardDemo {

    public static void main(String[] args) {
        extendsDemo();
        superDemo();
        pecsSummary();
    }

    /**
     * ? extends T — 生产者，只能取出（读）
     * List&lt;Dog&gt; 是 List&lt;? extends Animal&gt; 的子类型
     */
    static void extendsDemo() {
        List<Dog> dogs = new ArrayList<>();
        dogs.add(new Dog());
        List<? extends Animal> animals = dogs;
        Animal animal = animals.get(0);
        System.out.println("读取成功: " + animal);
    }

    /**
     * ? super T — 消费者，只能写入（写）
     */
    static void superDemo() {
        List<Animal> animals = new ArrayList<>();
        List<? super Dog> consumer = animals;
        consumer.add(new Dog());
        Object obj = consumer.get(0);
        System.out.println("读取为Object: " + obj);
    }

    static void pecsSummary() {
        List<Integer> src = Arrays.asList(1, 2, 3);
        List<Number> dest = new ArrayList<>();
        MyCollections.copy(dest, src);
        System.out.println("复制结果: " + dest);
    }

    static class Animal {
        @Override public String toString() { return "Animal"; }
    }
    static class Dog extends Animal {
        @Override public String toString() { return "Dog"; }
    }
    static class Cat extends Animal {
        @Override public String toString() { return "Cat"; }
    }
}

class MyCollections {
    public static <T> void copy(List<? super T> dest, List<? extends T> src) {
        for (T item : src) {
            dest.add(item);
        }
    }
}