package base.spring.alibaba;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Feign 声明式 RPC：动态代理 + 负载均衡 + 熔断降级。
 *
 * <p>核心考点：
 * <ol>
 *   <li>JDK 动态代理：Feign.newInstance() → Proxy.newProxyInstance → FeignInvocationHandler</li>
 *   <li>请求拦截器 RequestInterceptor：在发送 HTTP 请求前添加 Header（Token）</li>
 *   <li>Feign + Sentinel 熔断降级：FallbackFactory 在熔断时返回兜底实现</li>
 *   <li>超时配置：connectTimeout（建立连接） / readTimeout（等待响应）</li>
 * </ol>
 *
 * <p>本 Demo 用纯 Java 模拟 Feign 声明式 RPC 调用核心机制。
 *
 * @author study-tuling
 */
public class FeignDemo {

    // ======================== 1. 简化版注解 ========================

    /**
     * 简版 @FeignClient：指定服务名、路径、降级工厂。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface SimulatedFeignClient {
        String name();
        String path() default "";
        Class<?> fallbackFactory() default void.class;
    }

    /**
     * 简版 @RequestMapping，用于标记接口方法。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface SimulatedRequestMapping {
        String value();
        String method() default "GET";
    }

    /**
     * 简版 @RequestParam。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface SimulatedRequestParam {
        String value();
    }

    // ======================== 2. Feign 请求模型 ========================

    /**
     * Feign 请求封装。
     */
    static class FeignRequest {
        String serviceName;
        String path;
        String method;
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();

        String buildUrl() {
            StringBuilder sb = new StringBuilder("http://").append(serviceName).append(path);
            if (!queryParams.isEmpty()) {
                sb.append('?');
                queryParams.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
                sb.deleteCharAt(sb.length() - 1);
            }
            return sb.toString();
        }
    }

    /**
     * Feign 响应封装。
     */
    static class FeignResponse {
        int status;
        String body;

        FeignResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // ======================== 3. 请求拦截器 ========================

    /**
     * 请求拦截器：在发送请求前添加 Header（模拟 Token 传递）。
     */
    interface RequestInterceptor {
        void apply(FeignRequest request);
    }

    /**
     * 默认拦截器：添加 Authorization Token。
     */
    static class AuthTokenInterceptor implements RequestInterceptor {
        private final String token;

        AuthTokenInterceptor(String token) {
            this.token = token;
        }

        @Override
        public void apply(FeignRequest request) {
            request.headers.put("Authorization", "Bearer " + token);
            System.out.printf("    [拦截器] 添加 Authorization Header: Bearer %s...%n",
                    token.length() > 10 ? token.substring(0, 8) + "..." : token);
        }
    }

    // ======================== 4. FallbackFactory 熔断降级 ========================

    /**
     * FallbackFactory：当 Feign 调用失败时，生成降级实现。
     */
    @FunctionalInterface
    interface FallbackFactory<T> {
        T create(Throwable cause);
    }

    // ======================== 5. Feign 动态代理工厂 ========================

    /**
     * Feign 代理工厂：核心是 JDK 动态代理。
     * <p>对应 Feign.Builder.target() → ReflectiveFeign.newInstance()。
     */
    static class FeignProxyFactory {
        private final Map<String, String> serviceRegistry = new ConcurrentHashMap<>();
        private final List<RequestInterceptor> requestInterceptors = new ArrayList<>();
        /** connectTimeout ms */
        private int connectTimeout = 3000;
        /** readTimeout ms */
        private int readTimeout = 5000;

        FeignProxyFactory() {
            /* 模拟服务注册表 */
            serviceRegistry.put("user-service", "192.168.1.100:8080");
            serviceRegistry.put("order-service", "192.168.1.200:8081");
        }

        FeignProxyFactory addInterceptor(RequestInterceptor interceptor) {
            requestInterceptors.add(interceptor);
            return this;
        }

        FeignProxyFactory connectTimeout(int ms) {
            this.connectTimeout = ms;
            return this;
        }

        FeignProxyFactory readTimeout(int ms) {
            this.readTimeout = ms;
            return this;
        }

        /**
         * 创建代理实例。
         * <p>核心：Proxy.newProxyInstance + InvocationHandler。
         */
        @SuppressWarnings("unchecked")
        <T> T create(Class<T> apiType) {
            SimulatedFeignClient annotation = apiType.getAnnotation(SimulatedFeignClient.class);
            if (annotation == null) {
                throw new IllegalArgumentException(apiType.getName() + " 缺少 @SimulatedFeignClient");
            }
            String serviceName = annotation.name();
            String basePath = annotation.path();

            /* 创建 JDK 动态代理 */
            return (T) Proxy.newProxyInstance(
                    apiType.getClassLoader(),
                    new Class<?>[]{apiType},
                    new FeignInvocationHandler(serviceName, basePath, this));
        }

        /**
         * 模拟发送 HTTP 请求。
         */
        FeignResponse sendRequest(FeignRequest request) {
            /* 1. 请求拦截器 */
            for (RequestInterceptor interceptor : requestInterceptors) {
                interceptor.apply(request);
            }

            /* 2. 服务发现 */
            String address = serviceRegistry.get(request.serviceName);
            if (address == null) {
                throw new RuntimeException("服务未注册: " + request.serviceName);
            }

            /* 3. 模拟 HTTP 调用 */
            System.out.printf("    [Feign] %s %s (connectTimeout=%dms, readTimeout=%dms)%n",
                    request.method, request.buildUrl(), connectTimeout, readTimeout);
            System.out.printf("    [Feign] Headers: %s%n", request.headers);

            /* 模拟响应 */
            String body = "{\"code\":200,\"data\":\"" + request.path + " response\"}";
            return new FeignResponse(200, body);
        }
    }

    /**
     * Feign 调用处理器（JDK InvocationHandler）。
     * <p>所有对 FeignClient 接口方法的调用都会被拦截到这里的 invoke 方法。
     */
    static class FeignInvocationHandler implements InvocationHandler {
        private final String serviceName;
        private final String basePath;
        private final FeignProxyFactory factory;
        private Object fallbackInstance;

        FeignInvocationHandler(String serviceName, String basePath, FeignProxyFactory factory) {
            this.serviceName = serviceName;
            this.basePath = basePath;
            this.factory = factory;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            /* Object 类的方法直接在本地方执行 */
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }

            /* 构建 Feign 请求 */
            FeignRequest request = new FeignRequest();
            request.serviceName = serviceName;

            SimulatedRequestMapping mapping = method.getAnnotation(SimulatedRequestMapping.class);
            if (mapping != null) {
                request.path = basePath + mapping.value();
                request.method = mapping.method();
            }

            /* 处理 @RequestParam 参数 */
            if (args != null && method.getParameterCount() > 0) {
                Parameter[] parameters = method.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    SimulatedRequestParam param = parameters[i].getAnnotation(SimulatedRequestParam.class);
                    if (param != null && args[i] != null) {
                        request.queryParams.put(param.value(), args[i].toString());
                    }
                }
            }

            /* 发送请求 + 熔断兜底 */
            try {
                FeignResponse response = factory.sendRequest(request);
                return response.body;
            } catch (Exception e) {
                /* 降级：FallbackFactory 或 Fallback */
                if (fallbackInstance != null) {
                    System.out.printf("    [Fallback] Feign 调用失败，使用降级实现: %s%n", e.getMessage());
                    return method.invoke(fallbackInstance, args);
                }
                throw e;
            }
        }

        void setFallbackInstance(Object fallbackInstance) {
            this.fallbackInstance = fallbackInstance;
        }
    }

    // ======================== 6. 模拟业务接口 ========================

    /**
     * 用户服务 Feign 接口。
     */
    @SimulatedFeignClient(name = "user-service", path = "/api/user")
    interface UserServiceFeign {
        @SimulatedRequestMapping(value = "/getById", method = "GET")
        String getUserById(@SimulatedRequestParam("id") Long id);

        @SimulatedRequestMapping(value = "/list", method = "GET")
        String listUsers();
    }

    /**
     * UserServiceFeign 的降级实现。
     */
    static class UserServiceFallback implements UserServiceFeign {
        @Override
        public String getUserById(Long id) {
            return "{\"code\":500,\"data\":\"fallback: 用户服务不可用\"}";
        }

        @Override
        public String listUsers() {
            return "{\"code\":500,\"data\":\"fallback: 用户列表不可用\"}";
        }
    }

    // ==================== main ====================

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   Feign 声明式 RPC - 纯 Java 模拟演示        ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        /* ── 1. JDK 动态代理创建 FeignClient ── */
        System.out.println("\n=== 1. JDK 动态代理创建 FeignClient ===");
        FeignProxyFactory factory = new FeignProxyFactory()
                .addInterceptor(new AuthTokenInterceptor("eyJhbGciOiJIUzI1NiJ9.mock-token"))
                .connectTimeout(3000)
                .readTimeout(5000);

        UserServiceFeign userService = factory.create(UserServiceFeign.class);
        System.out.println("  代理类: " + userService.getClass().getName());
        System.out.println("  代理接口: " + Arrays.toString(userService.getClass().getInterfaces()));

        /* ── 2. 正常调用 ── */
        System.out.println("\n=== 2. Feign 正常调用 ===");
        String result1 = userService.getUserById(1001L);
        System.out.println("  响应: " + result1);

        String result2 = userService.listUsers();
        System.out.println("  响应: " + result2);

        /* ── 3. 熔断降级 Fallback ── */
        System.out.println("\n=== 3. Feign + Sentinel 熔断降级 ===");
        System.out.println("  配置 FallbackFactory → 当 Feign 调用失败时返回降级结果");
        // 获取 InvocationHandler 设置降级实例（简化做法）
        InvocationHandler handler = Proxy.getInvocationHandler(userService);
        if (handler instanceof FeignInvocationHandler feignHandler) {
            feignHandler.setFallbackInstance(new UserServiceFallback());
        }
        // 模拟失败：注册表中不存在
        FeignProxyFactory badFactory = new FeignProxyFactory() {
            @Override
            FeignResponse sendRequest(FeignRequest request) {
                System.out.printf("    [Feign] %s → 服务不可用，触发降级%n", request.buildUrl());
                throw new RuntimeException("Connection refused: " + request.serviceName);
            }
        };
        UserServiceFeign badService = badFactory.create(UserServiceFeign.class);
        InvocationHandler badHandler = Proxy.getInvocationHandler(badService);
        if (badHandler instanceof FeignInvocationHandler feignHandler2) {
            feignHandler2.setFallbackInstance(new UserServiceFallback());
        }
        String fallbackResult = badService.getUserById(2002L);
        System.out.println("  降级响应: " + fallbackResult);

        /* ── 4. 超时配置 ── */
        System.out.println("\n=== 4. Feign 超时配置 ===");
        System.out.println("  connectTimeout: 建立 TCP 连接的超时时间（默认 3s）");
        System.out.println("  readTimeout:   等待响应数据的超时时间（默认 5s）");
        System.out.println("  配置方式：");
        System.out.println("    feign.client.config.default.connectTimeout=5000");
        System.out.println("    feign.client.config.default.readTimeout=10000");
        System.out.println("    feign.client.config.user-service.connectTimeout=2000");

        /* ── 5. Feign 原理总结 ── */
        System.out.println("\n=== 5. Feign 原理总结 ===");
        System.out.println("  调用链：");
        System.out.println("    1. 调用接口方法 (UserServiceFeign.getUserById)");
        System.out.println("    2. Proxy.newProxyInstance → FeignInvocationHandler.invoke()");
        System.out.println("    3. 构建 RequestTemplate（解析 @RequestMapping + @RequestParam）");
        System.out.println("    4. RequestInterceptor.apply() → 添加 Header");
        System.out.println("    5. 服务发现 + 负载均衡（Ribbon/LoadBalancer）→ 选择实例");
        System.out.println("    6. HttpClient 发送 HTTP 请求");
        System.out.println("    7. 解析响应 → 返回结果");

        System.out.println("\n=== 演示结束 ===");
    }
}