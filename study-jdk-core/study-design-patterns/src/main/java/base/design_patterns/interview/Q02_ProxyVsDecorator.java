package base.design_patterns.interview;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 面试题：代理模式 vs 装饰器模式
 *
 * 结论：
 * 代理模式（Proxy）和装饰器模式（Decorator）在代码结构上很相似，
 * 都持有目标对象的引用，都实现相同的接口。
 * 但它们的意图（intent）完全不同。
 *
 * 对比总结：
 * | 维度       | 代理模式                          | 装饰器模式                         |
 * |-----------|----------------------------------|-----------------------------------|
 * | 关注点     | 控制访问（权限/延迟/缓存）          | 增强功能（动态添加职责）              |
 * | 关系       | 代理与目标通常在编译期确定           | 装饰器可在运行时动态组合              |
 * | 创建方式   | 代理内部创建/隐藏目标               | 装饰器通过构造器传入目标              |
 * | 多层嵌套   | 一般单层                           | 可以多层嵌套装饰                     |
 * | 典型应用   | Spring AOP、RPC 存根、延迟加载      | Java IO (Buffered/DataInputStream)  |
 */
public class Q02_ProxyVsDecorator {

    public static void main(String[] args) {
        System.out.println("=== 代理模式：控制访问 ===");
        proxyPatternDemo();

        System.out.println("\n=== 装饰器模式：增强功能 ===");
        decoratorPatternDemo();

        System.out.println("\n=== 对比总结 ===");
        comparisonSummary();
    }

    static void proxyPatternDemo() {
        // 代理：客户端不知道真实对象的存在（或代理负责创建真实对象）
        // 典型场景：延迟加载、权限控制、远程调用
        Service serviceProxy = (Service) Proxy.newProxyInstance(
                Q02_ProxyVsDecorator.class.getClassLoader(),
                new Class<?>[]{Service.class},
                new AccessControlHandler("admin")
        );
        serviceProxy.execute();

        Service deniedProxy = (Service) Proxy.newProxyInstance(
                Q02_ProxyVsDecorator.class.getClassLoader(),
                new Class<?>[]{Service.class},
                new AccessControlHandler("guest")
        );
        deniedProxy.execute();
    }

    static void decoratorPatternDemo() {
        // 装饰器：客户端主动选择装饰组合，层层嵌套
        // 典型场景：Java IO、功能叠加
        Service rawService = new RealService();

        Service loggedService = new LoggingDecorator(rawService);
        Service timedService = new TimingDecorator(rawService);
        Service loggedAndTimedService = new TimingDecorator(new LoggingDecorator(rawService));

        System.out.println("-- 原始服务 --");
        rawService.execute();

        System.out.println("-- 日志装饰 --");
        loggedService.execute();

        System.out.println("-- 计时装饰 --");
        timedService.execute();

        System.out.println("-- 日志+计时双层装饰 --");
        loggedAndTimedService.execute();
    }

    static void comparisonSummary() {
        System.out.println("| 维度       | 代理                          | 装饰器                       |");
        System.out.println("|-----------|------------------------------|-----------------------------|");
        System.out.println("| 关注点     | 控制访问（权限/延迟/缓存）      | 增强功能（动态添加职责）        |");
        System.out.println("| 关系       | 编译期确定                     | 运行时动态组合                 |");
        System.out.println("| 创建方式   | 代理内部创建目标               | 通过构造器传入目标              |");
        System.out.println("| 多层嵌套   | 一般单层                      | 可多层嵌套                    |");
        System.out.println("| 典型应用   | Spring AOP、RPC、懒加载        | Java IO、Collections 包装     |");
    }
}

/** 服务接口 */
interface Service {
    void execute();
}

/** 真实服务 */
class RealService implements Service {
    @Override
    public void execute() {
        System.out.println("  [真实服务] 执行核心业务逻辑");
    }
}

/** 访问控制代理 — 代理模式的典型实现 */
class AccessControlHandler implements InvocationHandler {
    private final String role;
    private final Service target;

    AccessControlHandler(String role) {
        this.role = role;
        this.target = new RealService();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("admin".equals(role)) {
            System.out.println("  [代理-权限] 通过，允许执行");
            return method.invoke(target, args);
        } else {
            System.out.println("  [代理-权限] 拒绝，角色 " + role + " 无权执行");
            return null;
        }
    }
}

/** 日志装饰器 — 装饰器模式的典型实现 */
class LoggingDecorator implements Service {
    private final Service delegate;

    LoggingDecorator(Service delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute() {
        System.out.println("  [装饰器-日志] 方法调用前: " + System.currentTimeMillis());
        delegate.execute();
        System.out.println("  [装饰器-日志] 方法调用后: " + System.currentTimeMillis());
    }
}

/** 计时装饰器 — 装饰器模式的典型实现 */
class TimingDecorator implements Service {
    private final Service delegate;

    TimingDecorator(Service delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute() {
        long start = System.nanoTime();
        delegate.execute();
        long elapsed = System.nanoTime() - start;
        System.out.println("  [装饰器-计时] 耗时: "
                + String.format("%.3f", elapsed / 1_000_000.0) + " ms");
    }
}