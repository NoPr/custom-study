package base.generics;

import java.util.ArrayList;
import java.util.List;

/**
 * 泛型基础用法演示
 *
 * 核心知识点：
 * 1. 泛型类 — 编译期类型检查，避免 ClassCastException
 * 2. 泛型方法 — 方法级别的类型参数，独立于类的类型参数
 * 3. 泛型接口 — 定义类型参数的契约
 * 4. 为什么需要泛型：将运行时的 ClassCastException 提前到编译期
 */
public class GenericDemo {

    public static void main(String[] args) {
        withoutGenerics();
        withGenerics();
        genericMethodDemo();
        genericInterfaceDemo();
    }

    /**
     * 无泛型的痛点：运行时 ClassCastException
     * 编译期无法发现类型错误，运行时崩溃
     */
    static void withoutGenerics() {
        List list = new ArrayList();
        list.add("hello");
        list.add(123);
        for (Object obj : list) {
            try {
                String s = (String) obj;
                System.out.println(s);
            } catch (ClassCastException e) {
                System.out.println("ClassCastException caught as expected: " + e.getMessage());
            }
        }
    }

    /**
     * 使用泛型后：编译期类型检查
     */
    static void withGenerics() {
        List<String> list = new ArrayList<>();
        list.add("hello");
        for (String s : list) {
            System.out.println(s);
        }
    }

    /**
     * 泛型方法演示
     */
    static <T> T genericMethod(T input) {
        return input;
    }

    static void genericMethodDemo() {
        String result1 = GenericDemo.<String>genericMethod("hello");
        Integer result2 = genericMethod(42);
        System.out.println(result1);
        System.out.println(result2);
    }

    /**
     * 泛型接口演示
     * Processor<T> 定义处理泛型数据的契约
     */
    static void genericInterfaceDemo() {
        Processor<String> stringProcessor = new Processor<String>() {
            @Override
            public String process(String input) {
                return "processed: " + input.toUpperCase();
            }
        };
        String result = stringProcessor.process("hello");
        System.out.println("泛型接口处理结果: " + result);
    }
}

/**
 * 自定义泛型接口
 * Processor<T> 定义处理泛型数据的契约
 */
interface Processor<T> {
    T process(T input);
}