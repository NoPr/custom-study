package base.generics.interview;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试题 01：什么是类型擦除？泛型信息在运行时还存在吗？
 *
 * 答案要点：
 * 1. Java 泛型通过类型擦除实现，编译器将&lt;T&gt;替换为 Object 或上界类型
 * 2. 运行时 JVM 看不到泛型类型参数，ArrayList&lt;String&gt; 和 ArrayList&lt;Integer&gt; 的 Class 完全相同
 * 3. 泛型信息保存在 class 文件的 Signature 属性中，供编译器和反射 API 使用
 * 4. 擦除带来了向后兼容性（Java 5 之前没有泛型，老代码可以直接用泛型类库）
 *
 * 这段代码用于面试现场演示，运行即可看到擦除证据。
 */
public class Q01_TypeErasure {

    public static void main(String[] args) {
        System.out.println("=== 面试题 01：类型擦除演示 ===\n");

        demoClassEquality();
        demoGenericClassInfo();
        demoErasureConsequence();
    }

    static void demoClassEquality() {
        System.out.println("【证明 1】ArrayList<String> 和 ArrayList<Integer> 的 Class 相同");
        ArrayList<String> stringList = new ArrayList<>();
        ArrayList<Integer> intList = new ArrayList<>();
        System.out.println("  stringList.getClass(): " + stringList.getClass());
        System.out.println("  intList.getClass():    " + intList.getClass());
        System.out.println("  它们是同一个 Class 吗？ " + (stringList.getClass() == intList.getClass()));
        System.out.println();
    }

    static void demoGenericClassInfo() {
        System.out.println("【证明 2】运行时无法获取泛型实际类型参数");
        ArrayList<String> list = new ArrayList<String>() {
        };
        System.out.println("  匿名子类可以保留泛型信息吗？");
        System.out.println("  匿名子类泛型超类: " + list.getClass().getGenericSuperclass());
        System.out.println();
    }

    @SuppressWarnings("unchecked")
    static void demoErasureConsequence() {
        System.out.println("【证明 3】类型擦除的「堆污染」- 绕过编译期检查");

        ArrayList<String> stringList = new ArrayList<>();
        stringList.add("hello");

        ArrayList rawList = stringList;
        rawList.add(42);

        try {
            String value = stringList.get(1);
            System.out.println("  " + value);
        } catch (ClassCastException e) {
            System.out.println("  运行时 ClassCastException！擦除了编译期保护。");
        }
    }
}