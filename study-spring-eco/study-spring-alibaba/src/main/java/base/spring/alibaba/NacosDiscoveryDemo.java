package base.spring.alibaba;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nacos 服务注册发现：临时实例 vs 永久实例 + 心跳机制 + 保护阈值。
 *
 * <p>核心考点：
 * <ol>
 *   <li>临时实例（ephemeral）：心跳续约，5s 检测一次，15s 无心跳标记不健康，30s 剔除</li>
 *   <li>永久实例（persistent）：由用户主动注销，不检查心跳</li>
 *   <li>保护阈值（ProtectionThreshold）：健康实例比例低于阈值时，返回所有实例防止雪崩</li>
 *   <li>客户端负载均衡：Ribbon/LoadBalancer 的 RoundRobin / Weighted 策略</li>
 * </ol>
 *
 * <p>本 Demo 用纯 Java 模拟 Nacos 服务注册中心的核心机制。
 *
 * @author study-tuling
 */
public class NacosDiscoveryDemo {

    /** 保护阈值：默认 0.0 表示不触发保护，设为 0.5 表示健康比例低于 50% 时触发 */
    private static final double PROTECTION_THRESHOLD = 0.0;

    /**
     * 服务实例。
     */
    static class ServiceInstance {
        String serviceName;
        String host;
        int port;
        String instanceId;
        /** true=临时实例（需要心跳），false=永久实例（不需要心跳） */
        boolean ephemeral;
        boolean healthy = true;
        /** 权重，用于带权负载均衡 */
        int weight;
        long lastHeartbeat;
        long registerTime;

        ServiceInstance(String serviceName, String host, int port, boolean ephemeral, int weight) {
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.instanceId = host + ":" + port;
            this.ephemeral = ephemeral;
            this.weight = weight;
            this.lastHeartbeat = System.currentTimeMillis();
            this.registerTime = System.currentTimeMillis();
        }
    }

    /**
     * 手写服务注册表（ServiceManager）：注册/注销/心跳续约。
     * <p>对应 Nacos 中的 naming 模块核心功能。
     */
    static class ServiceManager {
        /** serviceName → List<ServiceInstance> */
        final Map<String, List<ServiceInstance>> registry = new ConcurrentHashMap<>();
        /** 心跳间隔 ms */
        private static final long HEARTBEAT_INTERVAL_MS = 5000L;
        /** 不健康阈值 ms */
        private static final long UNHEALTHY_THRESHOLD_MS = 15000L;
        /** 剔除阈值 ms */
        private static final long EVICT_THRESHOLD_MS = 30000L;

        /** 保护阈值 */
        double protectionThreshold = PROTECTION_THRESHOLD;

        void register(ServiceInstance instance) {
            registry.computeIfAbsent(instance.serviceName, k -> new CopyOnWriteArrayList<>())
                    .add(instance);
            System.out.printf("  [注册] %s | %s | %s%n",
                    instance.serviceName, instance.instanceId,
                    instance.ephemeral ? "临时" : "永久");
        }

        void deregister(String serviceName, String instanceId) {
            List<ServiceInstance> instances = registry.get(serviceName);
            if (instances != null) {
                instances.removeIf(i -> i.instanceId.equals(instanceId));
                System.out.printf("  [注销] %s | %s%n", serviceName, instanceId);
            }
        }

        /**
         * 心跳续约：仅对临时实例生效，更新 lastHeartbeat 时间戳。
         *
         * @return true=续约成功，false=实例不存在
         */
        boolean heartbeat(String serviceName, String instanceId) {
            List<ServiceInstance> instances = registry.get(serviceName);
            if (instances == null) return false;
            for (ServiceInstance instance : instances) {
                if (instance.instanceId.equals(instanceId)) {
                    if (instance.ephemeral) {
                        instance.lastHeartbeat = System.currentTimeMillis();
                        instance.healthy = true;
                    }
                    return true;
                }
            }
            return false;
        }

        /**
         * 健康检查：遍历所有临时实例，标记超时未心跳的为不健康，剔除超长的。
         */
        void healthCheck() {
            long now = System.currentTimeMillis();
            for (List<ServiceInstance> instances : registry.values()) {
                for (ServiceInstance instance : instances) {
                    if (!instance.ephemeral) continue; // 永久实例不检查
                    long sinceLastHb = now - instance.lastHeartbeat;
                    if (sinceLastHb > EVICT_THRESHOLD_MS) {
                        System.out.printf("  [剔除] %s | %s (超过 %dms 无心跳)%n",
                                instance.serviceName, instance.instanceId, EVICT_THRESHOLD_MS);
                        instances.remove(instance);
                    } else if (sinceLastHb > UNHEALTHY_THRESHOLD_MS) {
                        instance.healthy = false;
                    }
                }
            }
        }

        /**
         * 获取服务实例列表（带保护阈值逻辑）。
         * <p>保护阈值：当健康实例占比低于阈值时，返回全部实例防止流量集中到少数健康实例导致雪崩。
         */
        List<ServiceInstance> getInstances(String serviceName) {
            List<ServiceInstance> all = registry.getOrDefault(serviceName, Collections.emptyList());
            if (all.isEmpty()) return all;

            List<ServiceInstance> healthy = all.stream()
                    .filter(i -> i.healthy)
                    .toList();

            double healthyRatio = (double) healthy.size() / all.size();
            System.out.printf("  [查询] %s | 总数=%d | 健康=%d | 健康比例=%.1f%%%n",
                    serviceName, all.size(), healthy.size(), healthyRatio * 100);

            if (protectionThreshold > 0 && healthyRatio < protectionThreshold) {
                System.out.printf("  [保护阈值] 健康比例 %.1f%% < 阈值 %.1f%% → 返回全部实例%n",
                        healthyRatio * 100, protectionThreshold * 100);
                return all; // 防止雪崩：返回全部实例（包含不健康的）
            }
            return healthy;
        }
    }

    /**
     * 客户端负载均衡模拟。
     */
    static class LoadBalancer {
        private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

        /** 轮询（RoundRobin） */
        ServiceInstance chooseRoundRobin(List<ServiceInstance> instances) {
            if (instances.isEmpty()) return null;
            int index = roundRobinCounter.getAndIncrement() % instances.size();
            return instances.get(index);
        }

        /** 加权随机（Weighted Random） */
        ServiceInstance chooseWeighted(List<ServiceInstance> instances, SplittableRandom random) {
            if (instances.isEmpty()) return null;
            int totalWeight = instances.stream().mapToInt(i -> i.weight).sum();
            if (totalWeight <= 0) return instances.get(random.nextInt(instances.size()));
            int offset = random.nextInt(totalWeight);
            for (ServiceInstance instance : instances) {
                offset -= instance.weight;
                if (offset < 0) return instance;
            }
            return instances.get(instances.size() - 1);
        }
    }

    // ==================== main ====================

    public static void main(String[] args) throws InterruptedException {
        ServiceManager manager = new ServiceManager();
        LoadBalancer loadBalancer = new LoadBalancer();

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   Nacos 服务注册发现 - 纯 Java 模拟演示      ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        /* ── 1. 注册服务 ── */
        System.out.println("\n=== 1. 服务注册：临时实例 vs 永久实例 ===");
        manager.register(new ServiceInstance("order-service", "192.168.1.10", 8080, true, 5));
        manager.register(new ServiceInstance("order-service", "192.168.1.11", 8080, true, 3));
        manager.register(new ServiceInstance("order-service", "192.168.1.12", 8080, false, 10));
        manager.register(new ServiceInstance("user-service", "192.168.1.20", 9090, true, 5));

        /* ── 2. 心跳续约 ── */
        System.out.println("\n=== 2. 心跳续约（临时实例，5s 间隔） ===");
        manager.heartbeat("order-service", "192.168.1.10:8080");
        manager.heartbeat("order-service", "192.168.1.11:8080");
        // 故意不给 192.168.1.12 发心跳（它是永久实例，不检查）
        manager.heartbeat("user-service", "192.168.1.20:9090");
        System.out.println("  [说明] 192.168.1.12:8080 是永久实例，不依赖心跳");

        /* ── 3. 查询实例 ── */
        System.out.println("\n=== 3. 查询服务实例（健康检查） ===");
        // 模拟超时：将 user-service 的最后心跳回退 20s 前
        ServiceInstance userInstance = manager.registry.get("user-service").get(0);
        try {
            java.lang.reflect.Field f = ServiceInstance.class.getDeclaredField("lastHeartbeat");
            f.setAccessible(true);
            f.set(userInstance, System.currentTimeMillis() - 20000);
        } catch (Exception ignored) {
        }
        manager.healthCheck();
        List<ServiceInstance> orderInstances = manager.getInstances("order-service");
        List<ServiceInstance> userInstances = manager.getInstances("user-service");

        /* ── 4. 负载均衡 ── */
        System.out.println("\n=== 4. 客户端负载均衡（Ribbon/LoadBalancer） ===");
        System.out.println("  --- 轮询（RoundRobin） ---");
        for (int i = 0; i < 6; i++) {
            ServiceInstance chosen = loadBalancer.chooseRoundRobin(orderInstances);
            if (chosen != null) {
                System.out.printf("    请求#%d → %s (weight=%d)%n", i + 1, chosen.instanceId, chosen.weight);
            }
        }

        System.out.println("  --- 加权（Weighted） ---");
        SplittableRandom random = new SplittableRandom(42);
        Map<String, Integer> weightCounter = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            ServiceInstance chosen = loadBalancer.chooseWeighted(
                    manager.getInstances("order-service"), random);
            weightCounter.merge(chosen.instanceId, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : weightCounter.entrySet()) {
            System.out.printf("    %s → 命中 %d 次%n", entry.getKey(), entry.getValue());
        }

        /* ── 5. 保护阈值演示 ── */
        System.out.println("\n=== 5. 保护阈值（ProtectionThreshold）演示 ===");
        System.out.println("  设置保护阈值 = 0.6，制造大量不健康实例...");
        manager.protectionThreshold = 0.6;
        // 注册 3 个新实例，它们都是健康的
        manager.register(new ServiceInstance("payment-service", "10.0.0.1", 8081, true, 5));
        manager.register(new ServiceInstance("payment-service", "10.0.0.2", 8081, true, 5));
        manager.register(new ServiceInstance("payment-service", "10.0.0.3", 8081, true, 5));
        // 将 2 个实例的心跳回退（模拟不健康）
        List<ServiceInstance> paymentInstances = manager.registry.get("payment-service");
        if (paymentInstances != null) {
            try {
                java.lang.reflect.Field f = ServiceInstance.class.getDeclaredField("lastHeartbeat");
                f.setAccessible(true);
                f.set(paymentInstances.get(1), 0); // 第二个实例标记不健康
                f.set(paymentInstances.get(2), 0); // 第三个实例标记不健康
            } catch (Exception ignored) {
            }
        }
        manager.healthCheck();
        manager.getInstances("payment-service");

        System.out.println("\n=== 演示结束 ===");
    }
}