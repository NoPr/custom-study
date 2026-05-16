package base.devops.interview;

import java.util.*;

/**
 * 面试题: DevOps 综合面试 -- Dockerfile/Docker-Compose 最佳实践 + K8s 调度流程 +
 * Git 多人协作解决冲突 + Linux 一条命令查 CPU + 滚动更新 vs 蓝绿 vs 金丝雀
 */
public class Q01_DevOps {

    // ==================== 1. Dockerfile / Docker-Compose 最佳实践 ====================

    static void dockerfileBestPractices() {
        System.out.println("=== Q1: Dockerfile / Docker-Compose 最佳实践 ===");
        System.out.println();

        System.out.println("【Dockerfile 最佳实践 6 条】");
        System.out.println("  1. 使用多阶段构建 (multi-stage build) 减小镜像体积");
        System.out.println("     第一阶段: maven/gradle 编译 → 第二阶段: 只复制 jar, 丢弃编译工具");
        System.out.println("     效果: 镜像从 500MB → 150MB");
        System.out.println();
        System.out.println("  2. 最小化层数: 合并 RUN 命令, 清理缓存");
        System.out.println("     RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*");
        System.out.println();
        System.out.println("  3. 使用 .dockerignore 排除不需要的文件 (target/, .git/, *.md)");
        System.out.println("     减小构建上下文, 加速构建, 防止敏感文件打入镜像");
        System.out.println();
        System.out.println("  4. 选择最小基础镜像 (alpine/slim/distroless), 减少攻击面");
        System.out.println("     FROM eclipse-temurin:17-jre-alpine  ← 仅 JRE, 无 JDK");
        System.out.println();
        System.out.println("  5. 以非 root 用户运行: USER appuser (安全最佳实践)");
        System.out.println("     RUN addgroup -S appgroup && adduser -S appuser -G appgroup");
        System.out.println("     USER appuser");
        System.out.println();
        System.out.println("  6. HEALTHCHECK 定义健康检查, 配合 K8s liveness/readiness probe");
        System.out.println("     HEALTHCHECK --interval=30s CMD curl -f http://localhost:8080/actuator/health || exit 1");

        System.out.println();
        System.out.println("【Docker-Compose 最佳实践 4 条】");
        System.out.println("  1. 使用命名卷 (named volumes) 持久化数据, 不要依赖容器内存储");
        System.out.println("  2. 通过 depends_on + healthcheck 控制启动顺序 (depends_on 仅等容器启动, 不等服务就绪)");
        System.out.println("  3. 环境变量分离: .env 文件 + environment: 字段, 不要硬编码密码");
        System.out.println("  4. 使用自定义网络 (networks) 实现服务间 DNS 发现, 避免使用 links (已废弃)");
    }

    // ==================== 2. K8s 调度流程 ====================

    /**
     * K8s 调度流程: Pod 从创建到运行的 6 个步骤
     *
     * <p>Step 1: 用户/Controller 提交 Pod spec 到 API Server, 写入 etcd
     * Step 2: Scheduler 通过 Watch 机制监听到未调度的 Pod
     * Step 3: Scheduler 预选 (Predicate/Filter): 过滤掉不满足条件的 Node (资源不足/污点/端口冲突)
     * Step 4: Scheduler 优选 (Priority/Score): 对剩余 Node 打分, 选最高分 Node 绑定
     * Step 5: Scheduler 将绑定结果写回 API Server → etcd
     * Step 6: 目标 Node 的 kubelet Watch 到绑定事件, 调用 CRI 拉镜像/创建容器
     */
    static void k8sSchedulingFlow() {
        System.out.println("\n=== Q2: K8s 调度流程 (Pod 创建到运行 6 步) ===");
        System.out.println();

        class SchedulingStep {
            int step;
            String actor;
            String action;
            String detail;

            SchedulingStep(int step, String actor, String action, String detail) {
                this.step = step;
                this.actor = actor;
                this.action = action;
                this.detail = detail;
            }
        }

        List<SchedulingStep> steps = List.of(
                new SchedulingStep(1, "API Server", "接收 Pod 创建请求",
                        "kubectl apply / Deployment Controller 提交 Pod spec → 写入 etcd"),
                new SchedulingStep(2, "Scheduler", "Watch 未调度 Pod",
                        "Informer 机制 Watch etcd, 发现 nodeName 为空的 Pod"),
                new SchedulingStep(3, "Scheduler", "预选 (Predicate)",
                        "过滤: 资源不足? 有污点(Taint)? 端口冲突? NodeSelector 不匹配? 卷限制?"),
                new SchedulingStep(4, "Scheduler", "优选 (Priority)",
                        "打分: LeastRequestedPriority(资源空闲多→高), BalancedResourceAllocation(均衡), ImageLocality(已有镜像→高)"),
                new SchedulingStep(5, "Scheduler", "绑定 (Bind)",
                        "将 nodeName 写入 Pod spec → API Server → etcd"),
                new SchedulingStep(6, "Kubelet", "创建容器",
                        "Watch 到绑定事件 → CRI 拉镜像 → 创建容器 → 启动 → 更新 Pod Status")
        );

        String fmt = "| %-4s | %-12s | %-16s | %-52s |%n";
        System.out.printf(fmt, "Step", "角色", "动作", "详情");
        System.out.println("|------|--------------|------------------|------------------------------------------------------|");
        for (SchedulingStep s : steps) {
            System.out.printf(fmt, s.step, s.actor, s.action, s.detail);
        }

        System.out.println();
        System.out.println("预选阶段常见过滤条件:");
        System.out.println("  - NodeSelector / NodeAffinity 不匹配");
        System.out.println("  - Pod 请求的资源 (CPU/Memory) 超过 Node 可分配量");
        System.out.println("  - Node 存在 Pod 不能容忍的 Taint (污点)");
        System.out.println("  - hostPort 已被占用");
        System.out.println("  - PV 的 NodeAffinity 不匹配");
        System.out.println("  - Node Condition: MemoryPressure / DiskPressure / PIDPressure");

        System.out.println();
        System.out.println("优选阶段常见打分策略:");
        System.out.println("  - LeastRequestedPriority: 资源空闲越多分越高, 实现负载均衡");
        System.out.println("  - BalancedResourceAllocation: CPU/内存使用比例均衡的分高");
        System.out.println("  - ImageLocality: 已有所需镜像的 Node 分高, 减少拉镜像时间");
        System.out.println("  - NodeAffinityPriority: 满足亲和性规则的 Node 分高");
        System.out.println("  - TaintTolerationPriority: 容忍污点多的 Node 分高");
    }

    // ==================== 3. Git 多人协作解决冲突 ====================

    static void gitConflictResolution() {
        System.out.println("\n=== Q3: Git 多人协作解决冲突 ===");
        System.out.println();

        System.out.println("【场景】两个开发者同时修改同一文件的同一行:");
        System.out.println();
        System.out.println("  开发者 A (feature/order):         开发者 B (feature/payment):");
        System.out.println("  git checkout -b feature/order     git checkout -b feature/payment");
        System.out.println("  修改 UserService.java:58          修改 UserService.java:58");
        System.out.println("  git commit -m \"订单模块\"          git commit -m \"支付模块\"");
        System.out.println("  git push origin feature/order     git push origin feature/payment");
        System.out.println("  创建 PR → 合并到 develop          自动合并成功 (不同文件)");
        System.out.println("                                    git checkout develop");
        System.out.println("      此时 develop 包含 A 的修改    git pull origin develop");
        System.out.println("                                    CONFLICT! UserService.java");
        System.out.println();

        System.out.println("【冲突解决流程】");
        System.out.println("  1. git status  ← 查看冲突文件列表");
        System.out.println("     both modified: UserService.java");
        System.out.println();
        System.out.println("  2. 打开冲突文件, 看到冲突标记:");
        System.out.println("     <<<<<<< HEAD (develop)");
        System.out.println("     private String module = \"order\";    ← A 的修改 (已在 develop)");
        System.out.println("     =======");
        System.out.println("     private String module = \"payment\";  ← B 的修改");
        System.out.println("     >>>>>>> feature/payment");
        System.out.println();
        System.out.println("  3. 沟通后决定保留两者, 修改为:");
        System.out.println("     private String module = \"order,payment\";  ← 协商后合并");
        System.out.println();
        System.out.println("  4. 标记已解决:");
        System.out.println("     git add UserService.java");
        System.out.println("     git commit -m \"merge: 合并订单和支付模块, 解决UserService冲突\"");
        System.out.println("     git push origin feature/payment");
        System.out.println();

        System.out.println("【减少冲突的团队规范】");
        System.out.println("  1. 小步提交, 频繁合并: 每天向 develop 合并, 不堆积大 PR");
        System.out.println("  2. 模块化拆分: 不同功能用不同包/文件, 减少同一文件冲突概率");
        System.out.println("  3. 提前同步: 开始编码前 git pull --rebase origin develop");
        System.out.println("  4. 代码审查: PR Review 时注意潜在冲突区域");
        System.out.println("  5. 明确分工: 同文件修改需提前沟通");
    }

    // ==================== 4. Linux 一条命令查 CPU 占用前10 ====================

    static void linuxTopCpuCommand() {
        System.out.println("\n=== Q4: Linux 一条命令查 CPU 占用前10 ===");
        System.out.println();

        System.out.println("【命令】");
        System.out.println("  ps aux --sort=-%cpu | head -11");
        System.out.println();
        System.out.println("  解释:");
        System.out.println("    ps aux              ← 列出所有进程的详细信息");
        System.out.println("    --sort=-%cpu        ← 按 CPU 使用率降序排列 (减号=降序)");
        System.out.println("    head -11            ← 取前 11 行 (1 行表头 + 10 行数据)");
        System.out.println();

        System.out.println("【输出字段含义】");
        String fmt = "| %-6s | %-20s |%n";
        System.out.printf(fmt, "字段", "含义");
        System.out.println("|--------|----------------------|");
        System.out.printf(fmt, "USER", "进程所有者");
        System.out.printf(fmt, "PID", "进程 ID");
        System.out.printf(fmt, "%CPU", "CPU 使用率 (多核可>100%)");
        System.out.printf(fmt, "%MEM", "物理内存使用率");
        System.out.printf(fmt, "VSZ", "虚拟内存大小 (KB)");
        System.out.printf(fmt, "RSS", "常驻物理内存 (KB)");
        System.out.printf(fmt, "TTY", "终端类型 (? = 无终端)");
        System.out.printf(fmt, "STAT", "进程状态 (S=睡眠, R=运行, Z=僵尸)");
        System.out.printf(fmt, "START", "启动时间");
        System.out.printf(fmt, "TIME", "累计 CPU 时间");
        System.out.printf(fmt, "COMMAND", "命令行");

        System.out.println();
        System.out.println("【替代命令】");
        System.out.println("  top -b -n 1 | head -17          ← top 批处理模式, 取一次快照");
        System.out.println("  pidstat 1                        ← 持续监控 CPU (sysstat 包)");
        System.out.println("  htop                             ← 交互式彩色 top, 更友好");
    }

    // ==================== 5. 滚动更新 vs 蓝绿 vs 金丝雀 ====================

    static void deploymentStrategiesComparison() {
        System.out.println("\n=== Q5: 滚动更新 vs 蓝绿部署 vs 金丝雀发布 ===");
        System.out.println();

        String fmt = "| %-14s | %-28s | %-26s | %-28s |%n";
        System.out.printf(fmt, "策略", "滚动更新 (Rolling)", "蓝绿部署 (Blue-Green)", "金丝雀发布 (Canary)");
        System.out.println("|----------------|------------------------------|----------------------------|------------------------------|");
        System.out.printf(fmt, "原理", "逐步替换旧 Pod 为新 Pod", "新/旧两套完整环境, 流量切换", "少量新版本, 逐步增大流量");
        System.out.printf(fmt, "资源消耗", "低, 峰值=副本数+maxSurge", "高, 需要双倍资源", "中, 按流量比例增加");
        System.out.printf(fmt, "回滚速度", "逐步回滚 (kubectl undo)", "秒级: 流量切回旧环境", "秒级: 停止金丝雀流量");
        System.out.printf(fmt, "用户影响", "短暂混合: 部分新部分旧", "无感知: 切换瞬间完成", "小部分用户受影响");
        System.out.printf(fmt, "版本并存", "过渡期有新旧并存", "切换瞬间, 只有一套活跃", "新旧长期并存");
        System.out.printf(fmt, "流量控制", "无, 按 Pod 比例", "无, 全量切换", "精确: 按 header/cookie/权重");
        System.out.printf(fmt, "K8s 实现", "Deployment RollingUpdate", "两套 Deployment+Service 切换", "Ingress/ServiceMesh 分流");
        System.out.printf(fmt, "适合场景", "常规业务更新, 默认策略", "重大版本, 需要快速回滚", "A/B 测试, 灰度验证");
        System.out.printf(fmt, "缺点", "回滚需逐 Pod, 缓慢", "双倍资源, 数据迁移复杂", "流量控制复杂, 监控要求高");

        System.out.println();
        System.out.println("【滚动更新 K8s 配置】");
        System.out.println("  spec:");
        System.out.println("    strategy:");
        System.out.println("      type: RollingUpdate");
        System.out.println("      rollingUpdate:");
        System.out.println("        maxSurge: 1         ← 允许超出副本数");
        System.out.println("        maxUnavailable: 0   ← 不允许不可用 (一个都不少)");

        System.out.println();
        System.out.println("【蓝绿部署 K8s 实现思路】");
        System.out.println("  1. Deployment-v2 (green) 创建, 与 v1 (blue) 完全独立");
        System.out.println("  2. Service selector 从 app=v1 改为 app=v2 → 流量切换");
        System.out.println("  3. 验证 green 正常后, 删除 blue (或保留用于快速回滚)");

        System.out.println();
        System.out.println("【金丝雀发布 Istio 实现思路】");
        System.out.println("  VirtualService:");
        System.out.println("    http:");
        System.out.println("      - match:");
        System.out.println("          - headers:");
        System.out.println("              canary: \"true\"");
        System.out.println("        route:");
        System.out.println("          - destination:");
        System.out.println("              host: myapp");
        System.out.println("              subset: v2      ← 金丝雀版本");
        System.out.println("      - route:");
        System.out.println("          - destination:");
        System.out.println("              host: myapp");
        System.out.println("              subset: v1");
        System.out.println("              weight: 95       ← 95% 稳定版");
        System.out.println("          - destination:");
        System.out.println("              host: myapp");
        System.out.println("              subset: v2");
        System.out.println("              weight: 5        ← 5% 金丝雀");
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        dockerfileBestPractices();
        k8sSchedulingFlow();
        gitConflictResolution();
        linuxTopCpuCommand();
        deploymentStrategiesComparison();
    }
}