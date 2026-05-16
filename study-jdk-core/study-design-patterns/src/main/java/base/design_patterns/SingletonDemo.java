package base.design_patterns;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * 单例模式 — 6 种实现方式对比
 *
 * 核心知识点：
 * 1. DCL（Double-Checked Locking）— 必须加 volatile，禁止指令重排序
 *    instance = new Singleton() 三步：①分配内存 ②初始化 ③引用指向内存
 *    ②③可能重排序 → 其他线程拿到未初始化对象 → volatile 禁止重排序
 * 2. 静态内部类 — 类加载机制的延迟初始化 + 线程安全（推荐）
 * 3. 枚举 — 最安全，防反射攻击和序列化破坏（Effective Java 推荐）
 * 4. 饿汉式 — 类加载时创建，简单但可能浪费资源
 * 5. 懒汉式（无锁）— 多线程不安全，不可用
 * 6. 懒汉式（synchronized）— 线程安全但性能差
 */
public class SingletonDemo {

    public static void main(String[] args) {
        System.out.println("=== 饿汉式单例 ===");
        System.out.println("HungrySingleton: " + HungrySingleton.getInstance());

        System.out.println("\n=== 懒汉式（无锁、线程不安全）===");
        testThreadSafety(LazyUnsafeSingleton::getInstance);

        System.out.println("\n=== 懒汉式（synchronized、线程安全但性能差）===");
        testThreadSafety(LazySafeSingleton::getInstance);

        System.out.println("\n=== DCL 双重检查锁 ===");
        testThreadSafety(DCLSingleton::getInstance);

        System.out.println("\n=== 静态内部类 ===");
        testThreadSafety(InnerClassSingleton::getInstance);

        System.out.println("\n=== 枚举单例 ===");
        testThreadSafety(() -> EnumSingleton.INSTANCE);

        System.out.println("\n=== 反射攻击测试 ===");
        reflectionAttackTest();
    }

    static void testThreadSafety(Supplier<Object> supplier) {
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        java.util.Set<Object> set = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                set.add(supplier.get());
                latch.countDown();
            }).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("  实例数量: " + set.size() + " (期望 1)");
    }

    static void reflectionAttackTest() {
        try {
            java.lang.reflect.Constructor<DCLSingleton> constructor =
                    DCLSingleton.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            DCLSingleton reflectionInstance = constructor.newInstance();
            System.out.println("  DCLSingleton 反射创建: " + reflectionInstance);
            System.out.println("  getInstance(): " + DCLSingleton.getInstance());
        } catch (Exception e) {
            System.out.println("  DCLSingleton 反射攻击被阻止: " + e.getCause().getMessage());
        }

        try {
            java.lang.reflect.Constructor<EnumSingleton> constructor =
                    EnumSingleton.class.getDeclaredConstructor(String.class, int.class);
            constructor.setAccessible(true);
            EnumSingleton reflectionInstance = constructor.newInstance("INSTANCE", 0);
            System.out.println("  EnumSingleton 反射创建: " + reflectionInstance);
        } catch (Exception e) {
            System.out.println("  EnumSingleton 反射攻击被阻止 (JDK 反射保护): "
                    + e.getCause().getMessage());
        }
    }
}

/** 饿汉式 — 类加载时创建，线程安全（JVM 保证），可能浪费资源 */
class HungrySingleton {
    private static final HungrySingleton INSTANCE = new HungrySingleton();

    private HungrySingleton() {}

    public static HungrySingleton getInstance() {
        return INSTANCE;
    }
}

/** 懒汉式（无锁）— 多线程同时进入 if 块，可能创建多个实例 */
class LazyUnsafeSingleton {
    private static LazyUnsafeSingleton instance;

    private LazyUnsafeSingleton() {}

    public static LazyUnsafeSingleton getInstance() {
        if (instance == null) {
            instance = new LazyUnsafeSingleton();
        }
        return instance;
    }
}

/** 懒汉式（synchronized 方法）— 线程安全但每次调用都获取锁，性能差 */
class LazySafeSingleton {
    private static LazySafeSingleton instance;

    private LazySafeSingleton() {}

    public static synchronized LazySafeSingleton getInstance() {
        if (instance == null) {
            instance = new LazySafeSingleton();
        }
        return instance;
    }
}

/** DCL 双重检查锁 — volatile 防止指令重排序 */
class DCLSingleton {
    private static volatile DCLSingleton instance;

    private DCLSingleton() {
        if (instance != null) {
            throw new RuntimeException("反射攻击被阻止");
        }
    }

    public static DCLSingleton getInstance() {
        if (instance == null) {
            synchronized (DCLSingleton.class) {
                if (instance == null) {
                    instance = new DCLSingleton();
                }
            }
        }
        return instance;
    }
}

/** 静态内部类 — 类加载机制保证线程安全 + 延迟初始化 */
class InnerClassSingleton {
    private InnerClassSingleton() {}

    private static class Holder {
        static final InnerClassSingleton INSTANCE = new InnerClassSingleton();
    }

    public static InnerClassSingleton getInstance() {
        return Holder.INSTANCE;
    }
}

/** 枚举单例 — 防反射、防序列化、线程安全 */
enum EnumSingleton {
    INSTANCE;

    public void doSomething() {
        System.out.println("枚举单例执行");
    }
}