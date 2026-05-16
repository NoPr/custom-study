package base.reflect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * JDK 动态代理深层原理演示
 *
 * 核心知识点：
 * 1. Proxy.newProxyInstance() 的 3 个参数含义
 *    - ClassLoader: 定义代理类的类加载器
 *    - interfaces: 代理类要实现的接口列表
 *    - InvocationHandler: 方法调用处理器
 * 2. $Proxy 类的生成机制 — 运行时生成字节码，继承 Proxy，实现指定接口
 * 3. InvocationHandler.invoke() 的 3 个参数
 *    - proxy: 代理对象自身（在 invoke 内部调用 proxy 的方法会死循环！）
 *    - method: 被调用的方法对象（java.lang.reflect.Method）
 *    - args: 方法参数
 * 4. 代理类结构分析 — 深入查看 $Proxy0 的继承关系和接口
 * 5. JDK 动态代理的限制 — 只能代理接口
 */
public class ProxyDemo {

    public static void main(String[] args) {
        proxyBasicApi();
        proxyClassDeepAnalysis();
        proxyMultipleInterfaces();
        proxyMethodFilter();
        proxySelfCallWarning();
    }

    /**
     * 基础 API 演示：创建代理 → 调用方法 → 观察 InvocationHandler 执行
     */
    static void proxyBasicApi() {
        System.out.println("=== 基础 API：创建 JDK 动态代理 ===\n");

        Calculator target = new CalculatorImpl();
        Calculator proxy = (Calculator) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{Calculator.class},
                new LoggingHandler(target)
        );

        int sum = proxy.add(3, 5);
        System.out.println("计算结果: " + sum + "\n");

        int diff = proxy.subtract(10, 4);
        System.out.println("计算结果: " + diff + "\n");
    }

    /**
     * 深入分析代理类结构
     * 关键洞察：$Proxy0 extends Proxy implements Calculator
     */
    static void proxyClassDeepAnalysis() {
        System.out.println("=== 代理类结构深入分析 ===\n");

        Calculator target = new CalculatorImpl();
        Calculator proxy = (Calculator) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{Calculator.class},
                new LoggingHandler(target)
        );

        Class<?> proxyClass = proxy.getClass();
        System.out.println("代理类名:          " + proxyClass.getName());
        System.out.println("代理类是 Proxy 子类? " + Proxy.class.isAssignableFrom(proxyClass));
        System.out.println("代理类父类:        " + proxyClass.getSuperclass().getName());
        System.out.println("代理类实现接口:    " + Arrays.toString(proxyClass.getInterfaces()));
        System.out.println("代理类是 Calculator? " + (proxy instanceof Calculator));
        System.out.println("代理类是 Proxy?     " + (proxy instanceof Proxy));

        System.out.println("\n代理类所有方法:");
        for (Method method : proxyClass.getDeclaredMethods()) {
            System.out.println("  " + method);
        }

        System.out.println("\n生成的代理类 class 文件的 'h' 字段 (InvocationHandler):");
        try {
            java.lang.reflect.Field hField = Proxy.class.getDeclaredField("h");
            hField.setAccessible(true);
            Object handler = hField.get(proxy);
            System.out.println("  h = " + handler);
        } catch (Exception e) {
            System.out.println("  (JDK 9+ 模块系统限制: 需要 --add-opens java.base/java.lang.reflect=ALL-UNNAMED)");
        }

        System.out.println();
    }

    /**
     * 一个代理类实现多个接口
     */
    static void proxyMultipleInterfaces() {
        System.out.println("=== 多接口代理 ===\n");

        MultiImpl target = new MultiImpl();
        Object proxy = Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{Calculator.class, Greeter.class},
                (proxyObj, method, args) -> {
                    System.out.println("  [Handler] 调用方法: " + method.getName());
                    return method.invoke(target, args);
                }
        );

        Calculator calc = (Calculator) proxy;
        System.out.println("Calculator.add(2, 3) = " + calc.add(2, 3));

        Greeter greeter = (Greeter) proxy;
        greeter.sayHello("世界");

        System.out.println();
    }

    /**
     * 根据方法名选择性拦截 — AOP 的 around advice 原形
     */
    static void proxyMethodFilter() {
        System.out.println("=== 方法选择性拦截（AOP 原形） ===\n");

        Calculator target = new CalculatorImpl();
        Calculator proxy = (Calculator) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{Calculator.class},
                (proxyObj, method, args) -> {
                    if (method.getName().startsWith("add")) {
                        System.out.println("  [AOP-环绕] 拦截 add 类方法，执行前后增强");
                    }
                    long start = System.nanoTime();
                    Object result = method.invoke(target, args);
                    long elapsed = System.nanoTime() - start;
                    System.out.println("  [AOP-计时] " + method.getName() + "() 耗时: "
                            + String.format("%.3f", elapsed / 1_000_000.0) + " ms");
                    return result;
                }
        );

        proxy.add(1, 2);
        proxy.subtract(5, 3);
        System.out.println();
    }

    /**
     * 警告：在 InvocationHandler 中调用 proxy 自身的方法会导致无限递归
     * 正确做法：调用 method.invoke(target, args) 而不是 method.invoke(proxy, args)
     */
    static void proxySelfCallWarning() {
        System.out.println("=== 陷阱：代理对象自调用导致死循环 ===\n");

        Calculator target = new CalculatorImpl();
        Calculator proxy = (Calculator) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{Calculator.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxyObj, Method method, Object[] args)
                            throws Throwable {
                        System.out.println("  Handler 被调用: " + method.getName());
                        return method.invoke(target, args);
                    }
                }
        );

        proxy.add(1, 2);

        System.out.println("\n  关键陷阱：如果在 Handler 中使用 method.invoke(proxy, args)");
        System.out.println("  代替 method.invoke(target, args)，会形成无限递归：");
        System.out.println("  proxy.method() → Handler.invoke() → method.invoke(proxy, args)");
        System.out.println("  → proxy.method() → ... → StackOverflowError");
        System.out.println();
    }
}

/** 计算器接口 */
interface Calculator {
    int add(int a, int b);
    int subtract(int a, int b);
}

/** 目标实现 */
class CalculatorImpl implements Calculator {
    @Override
    public int add(int a, int b) {
        System.out.println("  [真实实现] add(" + a + ", " + b + ")");
        return a + b;
    }

    @Override
    public int subtract(int a, int b) {
        System.out.println("  [真实实现] subtract(" + a + ", " + b + ")");
        return a - b;
    }
}

/** 问候接口 — 演示多接口代理 */
interface Greeter {
    void sayHello(String name);
}

/** 实现多个接口的目标类 */
class MultiImpl implements Calculator, Greeter {
    @Override
    public int add(int a, int b) {
        return a + b;
    }

    @Override
    public int subtract(int a, int b) {
        return a - b;
    }

    @Override
    public void sayHello(String name) {
        System.out.println("  [MultiImpl] Hello, " + name + "!");
    }
}

/** 日志 InvocationHandler */
class LoggingHandler implements InvocationHandler {
    private final Object target;

    LoggingHandler(Object target) {
        this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        System.out.println("  [InvocationHandler.invoke] 被触发");
        System.out.println("    proxy  : " + proxy.getClass().getName());
        System.out.println("    method : " + method.getName());
        System.out.println("    args   : " + Arrays.toString(args));

        Object result = method.invoke(target, args);

        System.out.println("    result : " + result);
        return result;
    }
}