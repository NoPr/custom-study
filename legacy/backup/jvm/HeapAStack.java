package jvm;

/*
{@code @Package} day.day03.jvm
{@code @Project} custom-collection-java
{@code @Filename} HeapAStack
{@code @Author} 18991/NoPr
{@code @Date} 2025/7/6  23:00
{@code @Description} 关于内存中堆栈的理解代码
*/

/*
| 场景                | 存储位置 | 是否共享 |
---------------------|----------|--------|
| 局部基本类型变量      | 栈       | 否      |
| 对象成员基本类型字段   | 堆       | 是*     |
| static基本类型字段    | 方法区   | 是      |
*/


import lombok.Data;

import java.util.LinkedList;
import java.util.Queue;

@Data
public class HeapAStack {

    public static void StringsMemory(){
        System.out.println("调用了我12333334");
        String s0 = "123346";
        String s1 = "123346";
        System.out.println(s0 == s1);   //true


        String s2 = "abc";

        String s3 = s1 + s2;
        String s4 = "123346abc";
        System.out.println(s3==s4);    //false

        String s5 = s1;
        System.out.println(s5==s1);    //true

        String s6 = new String("123346");
        System.out.println(s5==s6);    //false

        String s7 = new String("123346");
        System.out.println(s6==s7);    //false, new强制创建新对象，即使内容/类定义相同，也会分配不同的内存地址

        //扩展，如果想到获得同一实例，可以使用单例或者对象池复用


        String s8 = "123346";
        String s9 = "123346";
        System.out.println(s7==s8);    //false
        System.out.println(s8==s1);    //true
        System.out.println(s8==s9);    //true

        s8 = "777";
        System.out.println(s8==s1);    //false

    }

    public static void StringsMemory2(){
        System.out.println("其实调用的还是我");
        String s0 = "123346";
        String s1 = "123346";
        System.out.println(s0 == s1);   //true


        String s2 = "abc";

        String s3 = s1 + s2;
        String s4 = "123346abc";
        System.out.println(s3==s4);    //false

        String s5 = s1;
        System.out.println(s5==s1);    //true

        String s6 = new String("123346");
        System.out.println(s5==s6);    //false

        String s7 = new String("123346");
        System.out.println(s6==s7);    //false, new强制创建新对象，即使内容/类定义相同，也会分配不同的内存地址

        //扩展，如果想到获得同一实例，可以使用单例或者对象池复用


        String s8 = "123346";
        String s9 = "123346";
        System.out.println(s7==s8);    //false
        System.out.println(s8==s1);    //true
        System.out.println(s8==s9);    //true

        s8 = "777";
        System.out.println(s8==s1);    //false

    }

    private String ss = "1";

    //修改对象状态会影响所有引用该对象的变量
    public static void main(String[] args) {
        HeapAStack heapAStack = new HeapAStack();
        System.out.println(heapAStack.getSs());

        HeapAStack he = heapAStack;
        System.out.println(he.getSs());

        he.setSs("111");
        System.out.println(heapAStack.getSs());
    }

    // 示例1：局部变量在栈中
    void method() {
        int a = 10; // 栈中
        int b = a;  // 栈中创建新副本
    }

    // 示例2：成员变量在堆中
    class MyClass {
        int num = 10; // 随对象存在于堆中
    }
}


// 1.单例模式
class Singleton {
    private static Singleton instance;

    public static Singleton getInstance() {
        if(instance == null) {
            instance = new Singleton(); // 仅第一次new
        }
        return instance;
    }
}

// 2.对象池模式
class ObjectPool {
    private Queue<ObjectPool> pool = new LinkedList<>();

    public Object getObject() {
        if(pool.isEmpty()) {
            return new ObjectPool(); // 池空时才new
        }
        return pool.poll(); // 复用已有对象
    }
}