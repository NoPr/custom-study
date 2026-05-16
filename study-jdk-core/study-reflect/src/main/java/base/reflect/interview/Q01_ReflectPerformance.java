package base.reflect.interview;

import java.lang.reflect.Method;

/**
 * 面试题 01：反射的性能开销有多大？setAccessible 到底做了什么？
 *
 * 答案要点：
 * 1. 反射比直接调用慢几十倍到几百倍（取决于 JDK 版本和 JIT 优化）
 * 2. 主要性能开销来源：Method.invoke() 的参数装箱/拆箱、访问检查、安全检查
 * 3. setAccessible(true) 关闭访问检查，可显著提升性能（约 20 倍）
 * 4. JDK 17 的 MethodHandle 和 VarHandle 可作为反射的更高效替代方案
 * 5. 反射性能瓶颈主要在 invoke 阶段，Class/Field/Method 对象的获取可缓存
 *
 * 这段代码用于面试现场演示，运行即可看到性能数据。
 */
public class Q01_ReflectPerformance {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int TEST_ITERATIONS = 100_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 面试题 01：反射性能对比 ===\n");

        compareDirectVsReflect();
        compareAccessibleFalseVsTrue();
        compareClassGetVsCache();
    }

    /**
     * 对比：直接调用 vs 反射调用
     */
    static void compareDirectVsReflect() throws Exception {
        System.out.println("【测试 1】直接调用 vs 反射调用");
        System.out.println("测试次数: " + TEST_ITERATIONS + " 次\n");

        Target target = new Target();
        Method method = Target.class.getDeclaredMethod("compute", int.class, int.class);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.compute(1, 2);
            method.invoke(target, 1, 2);
        }

        long start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            target.compute(1, 2);
        }
        long directTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            method.invoke(target, 1, 2);
        }
        long reflectTime = System.nanoTime() - start;

        System.out.printf("直接调用耗时: %.0f ms\n", directTime / 1_000_000.0);
        System.out.printf("反射调用耗时: %.0f ms\n", reflectTime / 1_000_000.0);
        System.out.printf("反射比直接调用慢: %.1f 倍\n\n",
                (double) reflectTime / directTime);
    }

    /**
     * 对比：setAccessible(false) vs setAccessible(true)
     */
    static void compareAccessibleFalseVsTrue() throws Exception {
        System.out.println("【测试 2】setAccessible(false) vs setAccessible(true)");

        Target target = new Target();
        Method method = Target.class.getDeclaredMethod("compute", int.class, int.class);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            method.setAccessible(false);
            method.invoke(target, 1, 2);
            method.setAccessible(true);
            method.invoke(target, 1, 2);
        }

        method.setAccessible(false);
        long start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            method.invoke(target, 1, 2);
        }
        long noAccessTime = System.nanoTime() - start;

        method.setAccessible(true);
        start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            method.invoke(target, 1, 2);
        }
        long accessibleTime = System.nanoTime() - start;

        System.out.printf("setAccessible(false): %.0f ms\n", noAccessTime / 1_000_000.0);
        System.out.printf("setAccessible(true):  %.0f ms\n", accessibleTime / 1_000_000.0);
        System.out.printf("setAccessible(true) 快了: %.1f 倍\n\n",
                (double) noAccessTime / accessibleTime);
    }

    /**
     * 对比：每次查找 Method vs 缓存 Method
     */
    static void compareClassGetVsCache() throws Exception {
        System.out.println("【测试 3】每次 getDeclaredMethod() vs 缓存 Method");
        int iterations = 10_000_000;

        Target target = new Target();
        Method cachedMethod = Target.class.getDeclaredMethod("compute", int.class, int.class);

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Method m = Target.class.getDeclaredMethod("compute", int.class, int.class);
            m.invoke(target, 1, 2);
        }
        long noCacheTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cachedMethod.invoke(target, 1, 2);
        }
        long cachedTime = System.nanoTime() - start;

        System.out.printf("每次查找 Method: %.0f ms\n", noCacheTime / 1_000_000.0);
        System.out.printf("缓存 Method:      %.0f ms\n", cachedTime / 1_000_000.0);
        System.out.printf("缓存后快了: %.1f 倍\n\n",
                (double) noCacheTime / cachedTime);
    }

    /**
     * 被测试的目标类
     */
    static class Target {
        @SuppressWarnings("unused")
        public int compute(int a, int b) {
            return a + b;
        }
    }
}