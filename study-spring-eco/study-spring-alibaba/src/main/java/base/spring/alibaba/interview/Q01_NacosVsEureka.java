package base.spring.alibaba.interview;

/**
 * 面试高频：Nacos vs Eureka vs Consul vs Zookeeper 四者对比。
 *
 * <p>考察重点：CAP 理论、一致性协议、健康检查机制、实际选型依据。
 *
 * <h3>核心结论</h3>
 * <ul>
 *   <li>Nacos：阿里开源，既支持 AP 也支持 CP（临时实例 AP + 永久实例 CP），国内首选</li>
 *   <li>Eureka：Netflix 开源，纯 AP，已停止维护，仅适合旧项目</li>
 *   <li>Consul：HashiCorp 开源，CP 模式，内置健康检查 + KV 存储</li>
 *   <li>Zookeeper：Apache 顶级项目，纯 CP，不适合服务发现（临时节点机制有限）</li>
 * </ul>
 *
 * @author study-tuling
 */
public class Q01_NacosVsEureka {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  面试：Nacos vs Eureka vs Consul vs ZK 对比  ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        /* ── 1. 综合对比表 ── */
        System.out.println("\n=== 1. 四者综合对比 ===");
        String[][] table = {
                {"维度",       "Nacos",              "Eureka",           "Consul",              "Zookeeper"},
                {"CAP",        "AP + CP（双模式）",   "AP",               "CP",                  "CP"},
                {"一致性协议", "Raft（CP）/Distro（AP）","无（Peer to Peer）","Raft",               "ZAB"},
                {"通信协议",   "gRPC（长连接）",       "HTTP（短轮询）",     "HTTP + gRPC",         "自定义 TCP"},
                {"健康检查",   "TCP/HTTP/MySQL",      "心跳（续约）",        "TCP/HTTP/Script",     "TCP + Session"},
                {"配置中心",   "内置",                "无",               "内置 KV",             "可做配置（无 UI）"},
                {"服务元数据", "支持",                "支持",               "支持",                "不支持"},
                {"雪崩保护",   "支持（保护阈值）",    "支持（自我保护）",    "不支持",              "不支持"},
                {"多数据中心", "支持",                "支持",               "支持",                "不支持"},
                {"维护状态",   "活跃（阿里维护）",    "停止维护（2.x）",      "活跃",                "活跃"},
        };
        // 计算列宽
        int[] colWidths = new int[5];
        for (String[] row : table) {
            for (int i = 0; i < row.length; i++) {
                colWidths[i] = Math.max(colWidths[i], row[i].length());
            }
        }
        for (String[] row : table) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                sb.append(String.format("  %-" + (colWidths[i] + 2) + "s", row[i]));
            }
            System.out.println(sb);
        }

        /* ── 2. CAP 理论详解 ── */
        System.out.println("\n=== 2. CAP 理论详解 ===");
        System.out.println("  C (Consistency) 强一致性：所有节点同一时刻数据一致");
        System.out.println("  A (Availability) 可用性：每次请求都能获得非错误的响应");
        System.out.println("  P (Partition Tolerance) 分区容忍性：网络分区发生时系统仍能工作");
        System.out.println();
        System.out.println("  为什么 Nacos 可以'既 AP 又 CP'？");
        System.out.println("    ├── 临时实例（ephemeral=true） → AP 模式（Distro 协议）");
        System.out.println("    │     - 基于自研的 Distro 一致性协议（最终一致）");
        System.out.println("    │     - 心跳续约，非健康实例自动剔除");
        System.out.println("    │     - 适合服务发现场景（可用性优先）");
        System.out.println("    └── 永久实例（ephemeral=false）→ CP 模式（Raft 协议）");
        System.out.println("          - 使用 SOFA-JRaft（Raft 协议的 Java 实现）");
        System.out.println("          - 需要多数节点确认才能完成注册");
        System.out.println("          - 适合配置管理场景（一致性优先）");

        /* ── 3. Eureka 自我保护 vs Nacos 保护阈值 ── */
        System.out.println("\n=== 3. 雪崩保护机制对比 ===");
        System.out.println("  Eureka 自我保护模式：");
        System.out.println("    - 触发条件：15 分钟内心跳续约比例低于 85%");
        System.out.println("    - 行为：不剔除任何实例（宁可保留坏的也不误删好的）");
        System.out.println("    - 问题：网络分区恢复后，可能保留大量过期实例");
        System.out.println();
        System.out.println("  Nacos 保护阈值：");
        System.out.println("    - 触发条件：健康实例比例 < protectionThreshold（默认 0.0，即不启用）");
        System.out.println("    - 行为：查询时返回全部实例（包含不健康的）");
        System.out.println("    - 优势：按比例控制，不会永久保留过期实例");

        /* ── 4. 通信协议对比 ── */
        System.out.println("\n=== 4. 通信协议对比 ===");
        System.out.println("  Nacos 1.x：HTTP 短轮询（客户端每 30s 拉一次配置）");
        System.out.println("  Nacos 2.x：gRPC 长连接（服务端主动推送变更，性能提升 10x）");
        System.out.println("    ├── gRPC 基于 HTTP/2：多路复用、双向流、Header 压缩");
        System.out.println("    └── 长连接减少 TCP 握手开销，延迟更低");
        System.out.println();
        System.out.println("  Eureka：HTTP 短轮询（client 30s 拉一次注册表）");
        System.out.println("  Consul：HTTP + gRPC（也支持 DNS 接口）");
        System.out.println("  Zookeeper：自定义 TCP（基于 Netty），客户端维护 Session");

        /* ── 5. 实际选型建议 ── */
        System.out.println("\n=== 5. 实际选型建议 ===");
        System.out.println("  1. 国内企业 + Spring Cloud Alibaba 生态 → Nacos（首选）");
        System.out.println("  2. 已有 Spring Cloud Netflix 旧项目 → Eureka（过渡使用）");
        System.out.println("  3. 需多数据中心 + 内置健康检查 → Consul");
        System.out.println("  4. 需要强一致性 + 简单集群 → Zookeeper（仅适合 Dubbo 场景）");
        System.out.println("  5. Kubernetes 环境 → CoreDNS + Service（无需额外注册中心）");

        System.out.println("\n=== 面试答题话术 ===");
        System.out.println("  \"Nacos 是阿里开源的动态服务发现、配置和服务管理平台。");
        System.out.println("   它最大的特点是既支持 AP 也支持 CP——临时实例走 AP（Distro 协议），");
        System.out.println("   永久实例走 CP（Raft 协议），可以根据场景灵活选择。");
        System.out.println("   相比 Eureka 已停止维护，Nacos 整合了配置中心，且 Nacos 2.x 使用 gRPC");
        System.out.println("   长连接替代了 HTTP 短轮询，性能和实时性都有大幅提升。\"");
    }
}