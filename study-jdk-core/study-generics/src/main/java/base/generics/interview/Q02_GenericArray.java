package base.generics.interview;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试题 02：为什么不能创建泛型数组？如何绕过这个限制？
 *
 * 答案要点：
 * 1. 由于类型擦除，运行时无法知道 T 的具体类型，无法分配正确大小的内存
 * 2. new T[10] 编译错误，new ArrayList&lt;String&gt;[10] 也编译错误
 * 3. 如果允许泛型数组，将破坏数组的协变类型安全
 * 4. 解决方案：(1) ArrayList 替代 (2) 反射 Array.newInstance() (3) 通配符数组
 *
 * 这段代码用于面试现场演示，展示为什么泛型数组被禁止及各种绕过方式。
 */
public class Q02_GenericArray {

    public static void main(String[] args) {
        System.out.println("=== 面试题 02：泛型数组问题演示 ===\n");

        demoWhyProhibited();
        demoSolutions();
    }

    /**
     * 解释为什么泛型数组被禁止
     *
     * 假设 Java 允许 new ArrayList&lt;String&gt;[10]：
     * Object[] objArray = stringListArray;  // 数组协变，合法
     * objArray[0] = new ArrayList&lt;Integer&gt;(); // 运行时无法检测类型不匹配
     * String s = stringListArray[0].get(0); // ClassCastException！
     */
    static void demoWhyProhibited() {
        System.out.println("【原因】如果允许泛型数组，类型安全将被破坏：");
        System.out.println("  // 假设允许:");
        System.out.println("  // ArrayList<String>[] arr = new ArrayList<String>[10];");
        System.out.println("  // Object[] objArr = arr;  ← 数组协变");
        System.out.println("  // objArr[0] = new ArrayList<Integer>();  ← 放入错误类型");
        System.out.println("  // String s = arr[0].get(0);  ← 运行时 ClassCastException");
        System.out.println("  数组在运行时会记住自己的组件类型，但泛型在运行时已被擦除。");
        System.out.println("  这个矛盾是泛型数组被禁止的根本原因。");
        System.out.println();
    }

    /**
     * 三种绕过泛型数组限制的方案
     */
    @SuppressWarnings("unchecked")
    static void demoSolutions() {
        System.out.println("【方案 1】使用 ArrayList 替代数组（最推荐）");
        List<List<String>> listOfLists = new ArrayList<>();
        listOfLists.add(new ArrayList<>());
        System.out.println("  listOfLists 创建成功");

        System.out.println();

        System.out.println("【方案 2】反射创建泛型数组");
        String[] reflectArray = (String[]) Array.newInstance(String.class, 10);
        reflectArray[0] = "hello";
        System.out.println("  反射数组创建成功，reflectArray[0] = " + reflectArray[0]);

        System.out.println();

        System.out.println("【方案 3】通配符数组");
        ArrayList<?>[] wildcardArray = new ArrayList<?>[10];
        wildcardArray[0] = new ArrayList<String>();
        System.out.println("  通配符数组创建成功，长度 = " + wildcardArray.length);
    }
}