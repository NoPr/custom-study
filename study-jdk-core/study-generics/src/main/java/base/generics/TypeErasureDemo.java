package base.generics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 类型擦除深度演示
 *
 * 核心知识点：
 * 1. JVM 层面泛型信息被擦除，ArrayList&lt;String&gt; 和 ArrayList&lt;Integer&gt; 的 Class 对象相同
 * 2. 桥方法（bridge method）：编译器为维持多态自动生成
 * 3. 擦除的后果：无法用 instanceof 检查泛型类型、无法创建泛型数组
 * 4. 泛型信息保存位置：类签名（Signature 属性）
 */
public class TypeErasureDemo {

    public static void main(String[] args) throws Exception {
        eraseEquality();
        bridgeMethodDemo();
        limitationDemo();
    }

    /**
     * 证明：ArrayList&lt;String&gt; 和 ArrayList&lt;Integer&gt; 运行时 Class 对象相同
     * 类型参数被完全擦除
     */
    static void eraseEquality() {
        List<String> stringList = new ArrayList<>();
        List<Integer> intList = new ArrayList<>();
        System.out.println(stringList.getClass() == intList.getClass());
    }

    /**
     * 桥方法演示
     */
    static void bridgeMethodDemo() throws Exception {
        Method[] methods = MyNode.class.getDeclaredMethods();
        for (Method m : methods) {
            if (m.getName().equals("setData")) {
                System.out.println(
                    m.getName() + "(" + m.getParameterTypes()[0].getSimpleName() + ")" +
                    (m.isBridge() ? " [桥方法]" : "")
                );
            }
        }
    }

    static class Node<T> {
        private T data;
        public void setData(T data) { this.data = data; }
    }

    static class MyNode extends Node<Integer> {
        @Override
        public void setData(Integer data) { super.setData(data); }
    }

    /**
     * 类型擦除的局限性演示
     */
    static void limitationDemo() {
        ArrayList<?>[] arr = new ArrayList<?>[10];
        System.out.println("通配符数组创建成功，长度：" + arr.length);
    }
}