package base.design_patterns;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 动态代理模式 — JDK 动态代理与 CGLIB 对比
 *
 * 核心知识点：
 * 1. JDK 动态代理：基于接口，Proxy.newProxyInstance() + InvocationHandler
 *    运行时动态生成代理类（$Proxy0），实现目标接口
 * 2. CGLIB：基于继承，通过 ASM 生成目标类的子类
 *    Spring AOP 默认用 JDK 代理，类没有实现接口时用 CGLIB
 * 3. 代理模式 vs 装饰器模式：
 *    - 代理：控制访问（权限、延迟加载、日志）
 *    - 装饰器：增强功能（动态添加职责）
 * 4. 静态代理 vs 动态代理：
 *    - 静态代理：编译期生成代理类，一个接口一个代理类
 *    - 动态代理：运行时生成，一个 InvocationHandler 代理多个接口
 */
public class ProxyDemo {

    public static void main(String[] args) {
        System.out.println("=== JDK 动态代理 ===");

        UserService target = new UserServiceImpl();
        UserService proxy = (UserService) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{UserService.class},
                new LogInvocationHandler(target)
        );

        proxy.addUser("张三");
        String name = proxy.getUser(1);
        System.out.println("查询结果: " + name);

        System.out.println("\n=== 性能对比代理 ===");
        UserService timingProxy = (UserService) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{UserService.class},
                new TimingInvocationHandler(target)
        );
        timingProxy.addUser("李四");

        System.out.println("\n=== 多重代理（日志 + 计时） ===");
        UserService multiProxy = (UserService) Proxy.newProxyInstance(
                ProxyDemo.class.getClassLoader(),
                new Class<?>[]{UserService.class},
                new TimingInvocationHandler(
                        (UserService) Proxy.newProxyInstance(
                                ProxyDemo.class.getClassLoader(),
                                new Class<?>[]{UserService.class},
                                new LogInvocationHandler(target)
                        )
                )
        );
        multiProxy.addUser("王五");

        System.out.println("\n=== 代理类生成的 class 是什么？ ===");
        System.out.println("代理类名: " + proxy.getClass().getName());
        System.out.println("代理类父类: " + proxy.getClass().getSuperclass().getName());
        System.out.println("实现接口: " + java.util.Arrays.toString(proxy.getClass().getInterfaces()));
    }
}

/** 目标接口 */
interface UserService {
    void addUser(String name);
    String getUser(int id);
}

/** 目标实现 */
class UserServiceImpl implements UserService {
    @Override
    public void addUser(String name) {
        System.out.println("  [真实对象] 添加用户: " + name);
    }

    @Override
    public String getUser(int id) {
        System.out.println("  [真实对象] 查询用户 id=" + id);
        return "User-" + id;
    }
}

/** 日志代理处理器 */
class LogInvocationHandler implements InvocationHandler {
    private final Object target;

    LogInvocationHandler(Object target) {
        this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        System.out.println("  [代理-日志] 方法调用前: " + method.getName()
                + ", 参数: " + java.util.Arrays.toString(args));
        Object result = method.invoke(target, args);
        System.out.println("  [代理-日志] 方法调用后: " + method.getName()
                + ", 返回值: " + result);
        return result;
    }
}

/** 计时代理处理器 */
class TimingInvocationHandler implements InvocationHandler {
    private final Object target;

    TimingInvocationHandler(Object target) {
        this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long start = System.nanoTime();
        Object result = method.invoke(target, args);
        long elapsed = System.nanoTime() - start;
        System.out.println("  [代理-计时] " + method.getName() + " 耗时: "
                + String.format("%.3f", elapsed / 1_000_000.0) + " ms");
        return result;
    }
}