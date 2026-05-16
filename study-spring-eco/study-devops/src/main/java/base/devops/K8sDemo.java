package base.devops;

import java.util.*;

/**
 * Kubernetes 核心概念大合集: Pod + Deployment + Service + Ingress + ConfigMap/Secret + HPA
 *
 * <p>Pod: K8s 最小调度单元, 包含一个或多个容器, 共享网络命名空间和存储卷.
 * Sidecar 模式: 辅助容器与主容器共存, 如日志收集 (filebeat), 服务网格代理 (Envoy)
 * InitContainer: 在主容器启动前运行的初始化容器, 按顺序执行, 常用于数据库初始化
 *
 * <p>Deployment: 管理 Pod 副本, 支持滚动更新 (RollingUpdate) 和回滚 (rollback undo).
 * 滚动更新: 逐步替换旧 Pod 为新 Pod, maxSurge (峰值超出数) + maxUnavailable (最大不可用数) 控制速率
 * 回滚: kubectl rollout undo deployment/xxx --to-revision=N
 *
 * <p>Service: 为 Pod 提供稳定的访问入口 (ClusterIP), 通过 Label Selector 匹配 Pod.
 * ClusterIP (默认): 集群内访问, 虚拟 IP
 * NodePort: 在每个节点上开放端口, 外部可通过 NodeIP:NodePort 访问
 * LoadBalancer: 云厂商提供的负载均衡器, 外部流量 → LB → NodePort → Pod
 *
 * <p>Ingress: 七层 HTTP/HTTPS 路由规则, 将外部流量路由到集群内 Service.
 * 需要 Ingress Controller (如 Nginx Ingress, Traefik) 解析规则
 *
 * <p>ConfigMap/Secret: 将配置与镜像解耦, 通过环境变量或 Volume 挂载注入 Pod.
 * Secret 数据 base64 编码存储 (非加密, 生产需配合 etcd 加密或外部密钥管理)
 *
 * <p>HPA (HorizontalPodAutoscaler): 根据 CPU/内存/自定义指标自动调整 Pod 副本数
 */
public class K8sDemo {

    /** K8s 核心资源对象一览 */
    static void printCoreResources() {
        System.out.println("=== Kubernetes 核心资源对象 ===");
        String fmt = "| %-16s | %-10s | %-38s |%n";
        System.out.printf(fmt, "资源", "层级", "职责");
        System.out.println("|------------------|------------|----------------------------------------|");
        System.out.printf(fmt, "Pod", "工作负载", "最小调度单元, 1+容器共享网络/存储");
        System.out.printf(fmt, "ReplicaSet", "工作负载", "维护指定数量的 Pod 副本");
        System.out.printf(fmt, "Deployment", "工作负载", "声明式管理 Pod/ReplicaSet, 滚动更新/回滚");
        System.out.printf(fmt, "StatefulSet", "工作负载", "有状态应用: 稳定网络标识+持久存储+有序部署");
        System.out.printf(fmt, "DaemonSet", "工作负载", "每个节点运行一个 Pod (日志/监控 Agent)");
        System.out.printf(fmt, "Job/CronJob", "工作负载", "一次性/定时任务");
        System.out.printf(fmt, "Service", "服务发现", "Pod 稳定访问入口, 负载均衡");
        System.out.printf(fmt, "Ingress", "服务发现", "HTTP(S) 七层路由, 域名/路径转发");
        System.out.printf(fmt, "ConfigMap", "配置", "非敏感配置键值对");
        System.out.printf(fmt, "Secret", "配置", "敏感数据 base64 编码存储");
        System.out.printf(fmt, "PersistentVolume", "存储", "集群级存储资源抽象 (PV)");
        System.out.printf(fmt, "PersistentVolumeClaim", "存储", "用户对 PV 的申请 (PVC)");
        System.out.printf(fmt, "HPA", "自动伸缩", "根据 CPU/内存/自定义指标扩缩 Pod");
    }

    /** Pod 模型: Sidecar + InitContainer */
    static void simulatePodPattern() {
        System.out.println("\n=== Pod 设计模式: Sidecar + InitContainer ===");

        class Pod {
            String name;
            List<String> initContainers = new ArrayList<>();
            List<String> containers = new ArrayList<>();

            Pod(String name) {
                this.name = name;
            }

            void addInitContainer(String name) {
                initContainers.add(name);
            }

            void addContainer(String name) {
                containers.add(name);
            }

            void start() {
                System.out.println("\nPod [" + name + "] 启动流程:");
                for (int i = 0; i < initContainers.size(); i++) {
                    System.out.println("  [" + (i + 1) + "] InitContainer: " + initContainers.get(i) + " → 运行完成");
                }
                System.out.println("  --- 所有 InitContainer 完成, 启动主容器 ---");
                for (String c : containers) {
                    System.out.println("  [Container] " + c + " → 运行中");
                }
            }
        }

        Pod pod = new Pod("web-app-pod");
        pod.addInitContainer("init-db — 等待数据库就绪并执行 schema 迁移");
        pod.addInitContainer("init-config — 从远程拉取配置文件");
        pod.addContainer("app — Spring Boot 主应用 (port 8080)");
        pod.addContainer("filebeat — 日志采集 Sidecar (收集 /app/logs/*.log)");
        pod.start();

        System.out.println("\nSidecar 模式优势:");
        System.out.println("  1. 解耦: 日志采集逻辑与业务应用独立部署");
        System.out.println("  2. 复用: filebeat 镜像可跨多个应用使用");
        System.out.println("  3. 共享: 通过共享 Volume 传递日志文件");

        System.out.println("\nInitContainer 典型用途:");
        System.out.println("  1. 等待依赖服务就绪 (数据库/Redis)");
        System.out.println("  2. 数据库 schema 迁移 / 种子数据导入");
        System.out.println("  3. 从 Git/远程拉取配置文件或静态资源");
        System.out.println("  4. 权限设置: chown / chmod 挂载的卷");
    }

    /** Deployment 滚动更新 + 回滚 */
    static void simulateRollingUpdate() {
        System.out.println("\n=== Deployment 滚动更新 (RollingUpdate) 模拟 ===");

        class Deployment {
            String name;
            int replicas;
            int maxSurge;
            int maxUnavailable;

            Deployment(String name, int replicas, int maxSurge, int maxUnavailable) {
                this.name = name;
                this.replicas = replicas;
                this.maxSurge = maxSurge;
                this.maxUnavailable = maxUnavailable;
            }

            void rollingUpdate(String oldVersion, String newVersion) {
                System.out.println("Deployment [" + name + "]: " + oldVersion + " → " + newVersion);
                System.out.println("  策略: maxSurge=" + maxSurge + " (允许超出副本数), maxUnavailable=" + maxUnavailable + " (允许不可用数)");
                System.out.println("  当前副本: " + replicas);

                int oldPods = replicas;
                int newPods = 0;
                int step = 0;
                while (oldPods > 0) {
                    step++;
                    int create = Math.min(maxSurge, oldPods);
                    int remove = Math.min(maxUnavailable, oldPods - (newPods > 0 ? 1 : 0));
                    newPods += create;
                    oldPods -= remove;
                    int total = newPods + oldPods;
                    System.out.println("  步骤" + step + ": 新建" + create + "个" + newVersion + "Pod, 停掉" + remove + "个" + oldVersion + "Pod → 总计" + total + "个Pod (新" + newPods + "/旧" + oldPods + ")");
                    if (oldPods <= 0) break;
                }
                System.out.println("  滚动更新完成! 全部 " + replicas + " 个 Pod 已更新为 " + newVersion);
            }

            void rollback(int toRevision) {
                System.out.println("\n  回滚: kubectl rollout undo deployment/" + name + " --to-revision=" + toRevision);
                System.out.println("  查看历史: kubectl rollout history deployment/" + name);
            }
        }

        Deployment deploy = new Deployment("myapp", 5, 2, 1);
        deploy.rollingUpdate("v1.0", "v2.0");
        deploy.rollback(1);
    }

    /** Service 类型对比 */
    static void printServiceTypes() {
        System.out.println("\n=== Service 类型对比 ===");
        String fmt = "| %-16s | %-16s | %-36s | %-22s |%n";
        System.out.printf(fmt, "类型", "访问范围", "原理", "典型场景");
        System.out.println("|------------------|------------------|--------------------------------------|------------------------|");
        System.out.printf(fmt, "ClusterIP (默认)", "集群内部", "分配虚拟 IP, kube-proxy iptables/IPVS", "微服务间内部调用");
        System.out.printf(fmt, "NodePort", "节点 IP+端口", "在每个 Node 上开放端口(30000-32767)", "开发测试暴露服务");
        System.out.printf(fmt, "LoadBalancer", "外部 LB IP", "云厂商分配 LB, 流量→LB→NodePort→Pod", "生产对外暴露服务");
        System.out.printf(fmt, "ExternalName", "DNS CNAME", "返回 CNAME 记录, 无代理", "跨命名空间/外部服务引用");
        System.out.printf(fmt, "Headless", "Pod IP 直连", "clusterIP=None, DNS 返回所有 Pod IP", "StatefulSet 点对点通信");
        System.out.println();
        System.out.println("流量路径: External Client → LoadBalancer → NodePort (所有节点) → ClusterIP (iptables/IPVS) → Pod");
    }

    /** Ingress 路由规则 */
    static void simulateIngressRouting() {
        System.out.println("\n=== Ingress 路由规则模拟 ===");

        class IngressRule {
            String host;
            String path;
            String backendService;
            int backendPort;

            IngressRule(String host, String path, String backendService, int backendPort) {
                this.host = host;
                this.path = path;
                this.backendService = backendService;
                this.backendPort = backendPort;
            }
        }

        List<IngressRule> rules = List.of(
                new IngressRule("api.example.com", "/users", "user-service", 8080),
                new IngressRule("api.example.com", "/orders", "order-service", 8081),
                new IngressRule("api.example.com", "/products", "product-service", 8082),
                new IngressRule("admin.example.com", "/", "admin-ui", 80)
        );

        System.out.println("Ingress Controller: Nginx Ingress / Traefik");
        System.out.println("域名解析: *.example.com → LoadBalancer IP");
        System.out.println();

        System.out.println("路由表:");
        String fmt = "| %-20s | %-12s | %-18s | %-8s |%n";
        System.out.printf(fmt, "Host", "Path", "Backend Service", "Port");
        System.out.println("|----------------------|--------------|--------------------|----------|");
        for (IngressRule rule : rules) {
            System.out.printf(fmt, rule.host, rule.path, rule.backendService, rule.backendPort);
        }

        System.out.println("\n模拟请求:");
        System.out.println("  GET https://api.example.com/users    → user-service:8080");
        System.out.println("  GET https://api.example.com/orders   → order-service:8081");
        System.out.println("  GET https://admin.example.com/       → admin-ui:80");
    }

    /** ConfigMap/Secret 注入方式 */
    static void simulateConfigInjection() {
        System.out.println("\n=== ConfigMap / Secret 注入方式 ===");
        System.out.println("ConfigMap 和 Secret 可通过以下方式注入 Pod:");

        System.out.println("\n1. 环境变量方式:");
        System.out.println("   env:");
        System.out.println("     - name: DB_HOST");
        System.out.println("       valueFrom:");
        System.out.println("         configMapKeyRef:");
        System.out.println("           name: app-config");
        System.out.println("           key: database.host");

        System.out.println("\n2. 全部环境变量注入:");
        System.out.println("   envFrom:");
        System.out.println("     - configMapRef:");
        System.out.println("         name: app-config");

        System.out.println("\n3. Volume 挂载方式 (支持热更新):");
        System.out.println("   volumes:");
        System.out.println("     - name: config-volume");
        System.out.println("       configMap:");
        System.out.println("         name: app-config");
        System.out.println("   volumeMounts:");
        System.out.println("     - name: config-volume");
        System.out.println("       mountPath: /etc/config");

        System.out.println("\nSecret 额外注意事项:");
        System.out.println("  - Secret 数据 base64 编码, 非加密");
        System.out.println("  - etcd 静态加密 (EncryptionConfiguration) 可保护 Secret");
        System.out.println("  - 生产环境建议: 外部密钥管理 (Vault, AWS Secrets Manager)");
        System.out.println("  - RBAC 控制 Secret 访问权限");
    }

    /** HPA 自动扩缩容模拟 */
    static void simulateHPA() {
        System.out.println("\n=== HPA 水平自动扩缩容模拟 ===");

        class HPASimulation {
            int currentReplicas = 2;
            int minReplicas = 2;
            int maxReplicas = 10;
            int targetCPUUtilization = 60;

            void evaluate(int currentCPUPercent) {
                System.out.println("当前副本数: " + currentReplicas + ", CPU 使用率: " + currentCPUPercent + "%, 目标: " + targetCPUUtilization + "%");
                int desiredReplicas = (int) Math.ceil((double) currentReplicas * currentCPUPercent / targetCPUUtilization);
                desiredReplicas = Math.max(minReplicas, Math.min(maxReplicas, desiredReplicas));

                if (desiredReplicas != currentReplicas) {
                    String action = desiredReplicas > currentReplicas ? "扩容" : "缩容";
                    System.out.println("  → " + action + ": " + currentReplicas + " → " + desiredReplicas + " 个副本");
                    System.out.println("  计算公式: ceil(" + currentReplicas + " * " + currentCPUPercent + " / " + targetCPUUtilization + ") = " + desiredReplicas);
                    currentReplicas = desiredReplicas;
                } else {
                    System.out.println("  → 副本数不变: " + currentReplicas);
                }
                System.out.println();
            }
        }

        HPASimulation hpa = new HPASimulation();
        System.out.println("HPA 配置: minReplicas=2, maxReplicas=10, targetCPU=60%");
        System.out.println();
        hpa.evaluate(30);  // 低负载, 可能缩容 (但已达 min)
        hpa.evaluate(85);  // 高负载, 扩容
        hpa.evaluate(40);  // 负载降低, 缩容

        System.out.println("HPA 常用命令:");
        System.out.println("  kubectl autoscale deployment myapp --min=2 --max=10 --cpu-percent=60");
        System.out.println("  kubectl get hpa");
        System.out.println("  kubectl describe hpa myapp");
        System.out.println("  kubectl delete hpa myapp");
    }

    /** 常用 kubectl 命令速查 */
    static void printCommonCommands() {
        System.out.println("\n=== 常用 kubectl 命令速查 ===");
        System.out.println("kubectl get pods -o wide                    — 查看 Pod 及所在节点");
        System.out.println("kubectl describe pod <name>                 — Pod 详细信息 (包含 Events)");
        System.out.println("kubectl logs -f <pod> -c <container>         — 实时查看容器日志");
        System.out.println("kubectl exec -it <pod> -- sh                — 进入容器 shell");
        System.out.println("kubectl apply -f deployment.yaml            — 声明式创建/更新");
        System.out.println("kubectl delete -f deployment.yaml           — 删除资源");
        System.out.println("kubectl rollout restart deployment/<name>   — 重启 Deployment");
        System.out.println("kubectl rollout status deployment/<name>    — 查看更新状态");
        System.out.println("kubectl rollout history deployment/<name>   — 查看历史版本");
        System.out.println("kubectl rollout undo deployment/<name>      — 回滚到上一版本");
        System.out.println("kubectl get events --sort-by=.metadata.creationTimestamp — 查看事件");
        System.out.println("kubectl top pods / kubectl top nodes         — 资源使用统计");
        System.out.println("kubectl config get-contexts                 — 查看集群上下文");
        System.out.println("kubectl config use-context <name>           — 切换集群");
    }

    public static void main(String[] args) {
        printCoreResources();
        simulatePodPattern();
        simulateRollingUpdate();
        printServiceTypes();
        simulateIngressRouting();
        simulateConfigInjection();
        simulateHPA();
        printCommonCommands();
    }
}