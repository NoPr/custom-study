package base.spring.alibaba;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nacos 配置中心：动态刷新 + 配置优先级 + Namespace/Group/DataId 三级定位。
 *
 * <p>核心考点：
 * <ol>
 *   <li>DataId 命名规则：${prefix}-${spring.profiles.active}.${file-extension}</li>
 *   <li>长轮询（Long Polling）：客户端发起 HTTP 请求，服务端 hold 30s，有变更立即返回</li>
 *   <li>@RefreshScope 原理：ContextRefresher.refreshEnvironment() → 重新绑定 @Value</li>
 *   <li>配置优先级链：命令行 > 环境变量 > Nacos > application.yml</li>
 * </ol>
 *
 * <p>本 Demo 用纯 Java 模拟 Nacos 配置中心的核心机制，无需启动真实 Nacos 服务。
 *
 * @author study-tuling
 */
public class NacosConfigDemo {

    /**
     * 模拟的 Nacos 配置服务端（带长轮询支持）。
     */
    static class SimulatedNacosConfigServer {
        /** 所有配置存储：key = "namespace/group/dataId", value = 配置内容 */
        private final Map<String, String> configStore = new ConcurrentHashMap<>();
        /** 每个配置的版本号，用于长轮询变更检测 */
        private final Map<String, AtomicLong> configVersions = new ConcurrentHashMap<>();

        SimulatedNacosConfigServer() {
            /* ── 三级定位：Namespace + Group + DataId ── */
            String defaultNs = "public";
            String defaultGroup = "DEFAULT_GROUP";
            putConfig(defaultNs, defaultGroup, "application-dev.yml",
                    "server.port=8080\napp.name=myApp\napp.version=1.0.0");
            putConfig(defaultNs, defaultGroup, "application-dev.properties",
                    "db.url=jdbc:mysql://localhost:3306/mydb\ndb.maxActive=20");
            putConfig(defaultNs, "CUSTOM_GROUP", "datasource.yml",
                    "spring.datasource.url=jdbc:mysql://prod:3306/db\nspring.datasource.hikari.maximumPoolSize=50");
        }

        void putConfig(String namespace, String group, String dataId, String content) {
            String key = buildKey(namespace, group, dataId);
            configStore.put(key, content);
            configVersions.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }

        String getConfig(String namespace, String group, String dataId) {
            return configStore.get(buildKey(namespace, group, dataId));
        }

        long getVersion(String namespace, String group, String dataId) {
            return configVersions.getOrDefault(buildKey(namespace, group, dataId),
                    new AtomicLong(0)).get();
        }

        /**
         * 长轮询拉取配置。
         * <p>客户端持有上一次的版本号发起请求，服务端 hold 最多 30 秒，
         * 若期间版本变化则立即返回新配置；否则超时后返回 null（无变化）。
         */
        String longPolling(String namespace, String group, String dataId, long lastVersion) {
            long start = System.currentTimeMillis();
            long timeout = 30000L; // 30 秒超时
            System.out.printf("    [长轮询] DataId=%s, 请求版本=%d, 等待变更...%n", dataId, lastVersion);
            while (System.currentTimeMillis() - start < timeout) {
                long currentVersion = getVersion(namespace, group, dataId);
                if (currentVersion > lastVersion) {
                    System.out.printf("    [长轮询] DataId=%s 检测到变更, 新版本=%d%n", dataId, currentVersion);
                    return getConfig(namespace, group, dataId);
                }
                try {
                    Thread.sleep(500); // 每 500ms 检查一次
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            System.out.println("    [长轮询] 30s 超时, 无变更");
            return null;
        }

        /**
         * 模拟配置变更推送：更新配置并触发长轮询返回。
         */
        void publishChange(String namespace, String group, String dataId, String newContent) {
            putConfig(namespace, group, dataId, newContent);
        }

        private String buildKey(String namespace, String group, String dataId) {
            return namespace + "/" + group + "/" + dataId;
        }
    }

    /**
     * 模拟 Nacos 配置客户端。
     * <p>持有本地配置缓存 + 版本号，通过长轮询监听远端变更。
     */
    static class SimulatedNacosConfigClient {
        private final SimulatedNacosConfigServer server;
        private final Properties localCache = new Properties();
        /** 每个 dataId 对应的本地版本号 */
        private final Map<String, Long> localVersions = new ConcurrentHashMap<>();

        SimulatedNacosConfigClient(SimulatedNacosConfigServer server) {
            this.server = server;
        }

        String getProperty(String key) {
            return localCache.getProperty(key);
        }

        /**
         * DataId 命名规则演示：
         * DataId = ${prefix}-${spring.profiles.active}.${file-extension}
         */
        static String buildDataId(String prefix, String profile, String extension) {
            return prefix + "-" + profile + "." + extension;
        }

        /**
         * 初始拉取配置。
         */
        void loadConfig(String namespace, String group, String dataId) {
            String content = server.getConfig(namespace, group, dataId);
            applyConfig(dataId, content);
        }

        /**
         * 开启长轮询监听。
         */
        void startLongPolling(String namespace, String group, String dataId) {
            long lastVersion = server.getVersion(namespace, group, dataId);
            String changed = server.longPolling(namespace, group, dataId, lastVersion);
            if (changed != null) {
                applyConfig(dataId, changed);
            }
        }

        private void applyConfig(String dataId, String content) {
            if (content == null) return;
            try {
                Properties props = new Properties();
                // 仅支持 key=value 格式演示
                for (String line : content.split("\n")) {
                    String[] kv = line.split("=", 2);
                    if (kv.length == 2) {
                        props.setProperty(kv[0].trim(), kv[1].trim());
                    }
                }
                localCache.putAll(props);
                System.out.printf("    [配置刷新] DataId=%s, 内容=%s%n", dataId, content);
            } catch (Exception e) {
                System.err.println("配置解析失败: " + e.getMessage());
            }
        }
    }

    /**
     * 模拟 @RefreshScope 的刷新机制。
     * <p>原理：Spring Cloud ContextRefresher.refreshEnvironment()
     * → 重新绑定所有 @RefreshScope Bean 的 @Value 属性。
     */
    static class RefreshScopeSimulator {
        private final SimulatedNacosConfigClient client;

        RefreshScopeSimulator(SimulatedNacosConfigClient client) {
            this.client = client;
        }

        /**
         * 模拟 refresh 操作：清除本地缓存 → 重新拉取。
         */
        void refresh(String namespace, String group, String... dataIds) {
            System.out.println("    [@RefreshScope] 触发 ContextRefresher.refreshEnvironment()");
            for (String dataId : dataIds) {
                client.loadConfig(namespace, group, dataId);
            }
            System.out.println("    [@RefreshScope] 重新绑定 @Value 完成");
        }
    }

    // ==================== main ====================

    public static void main(String[] args) {
        SimulatedNacosConfigServer server = new SimulatedNacosConfigServer();
        SimulatedNacosConfigClient client = new SimulatedNacosConfigClient(server);
        RefreshScopeSimulator refresher = new RefreshScopeSimulator(client);

        String namespace = "public";
        String group = "DEFAULT_GROUP";

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   Nacos 配置中心 - 纯 Java 模拟演示          ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        /* ── 1. DataId 命名规则 ── */
        System.out.println("\n=== 1. DataId 三级定位：Namespace/Group/DataId ===");
        String dataId1 = SimulatedNacosConfigClient.buildDataId("application", "dev", "yml");
        String dataId2 = SimulatedNacosConfigClient.buildDataId("application", "dev", "properties");
        System.out.printf("  Namespace=%s, Group=%s%n", namespace, group);
        System.out.printf("  DataId1=%s%n  DataId2=%s%n", dataId1, dataId2);
        System.out.printf("  三级唯一 Key = %s/%s/%s%n", namespace, group, dataId1);

        /* ── 2. 配置拉取 ── */
        System.out.println("\n=== 2. 初始配置拉取 ===");
        client.loadConfig(namespace, group, dataId1);
        System.out.printf("  app.name = %s%n", client.getProperty("app.name"));
        System.out.printf("  server.port = %s%n", client.getProperty("server.port"));

        client.loadConfig(namespace, "CUSTOM_GROUP", "datasource.yml");
        System.out.printf("  spring.datasource.url = %s%n",
                client.getProperty("spring.datasource.url"));

        /* ── 3. 长轮询模拟 ── */
        System.out.println("\n=== 3. 长轮询（Long Polling）模拟 ===");
        // 启动一个线程模拟异步配置变更
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                System.out.println("  >>> 管理员修改了配置 (模拟)");
                server.publishChange(namespace, group, dataId1,
                        "server.port=9090\napp.name=myApp\napp.version=2.0.0");
            } catch (InterruptedException ignored) {
            }
        }).start();
        client.startLongPolling(namespace, group, dataId1);
        System.out.printf("  新配置 app.version = %s%n", client.getProperty("app.version"));

        /* ── 4. @RefreshScope 原理 ── */
        System.out.println("\n=== 4. @RefreshScope 模拟（ContextRefresher） ===");
        server.publishChange(namespace, group, dataId2,
                "db.url=jdbc:mysql://newhost:3306/mydb\ndb.maxActive=50");
        refresher.refresh(namespace, group, dataId2);
        System.out.printf("  db.url = %s%n", client.getProperty("db.url"));
        System.out.printf("  db.maxActive = %s%n", client.getProperty("db.maxActive"));

        /* ── 5. 配置优先级链 ── */
        System.out.println("\n=== 5. 配置优先级链（从高到低） ===");
        System.out.println("  1. 命令行参数 (--server.port=9090)         ← 最高");
        System.out.println("  2. 环境变量 (SERVER_PORT=9090)");
        System.out.println("  3. Nacos 配置中心 (DataId=xxx)");
        System.out.println("  4. application.yml / application.properties");
        System.out.println("  5. bootstrap.yml (旧版，Spring Cloud 2020 起移除) ← 最低");
        System.out.println("  优先级规则：外部化配置 > 内部配置，远程 > 本地");

        System.out.println("\n=== 演示结束 ===");
    }
}