package base.websocket.interview;

/**
 * 面试题：WebSocket 协议原理 + 心跳 + 重连 + 集群方案。
 *
 * <p>本类以问答形式覆盖 WebSocket 面试高频考点：</p>
 * <ul>
 *   <li>HTTP vs WebSocket 长连接对比（协议升级、帧格式、全双工）</li>
 *   <li>Nginx 代理 WebSocket 配置要点（proxy_http_version 1.1 / Upgrade / Connection）</li>
 *   <li>WebSocket 集群方案：Redis Pub/Sub 跨节点广播 + Kafka 消息中转</li>
 *   <li>连接数（10w+）时的内存优化（Session 数据压缩/序列化）</li>
 * </ul>
 *
 * @see WebSocketCoreDemo 协议原理演示
 * @see HeartbeatDemo 心跳机制演示
 * @see ReconnectDemo 断线重连演示
 */
public class Q01_WebSocket_Principle {

    // ======================== Q1: WebSocket 握手原理 ========================

    /**
     * <h3>Q1：WebSocket 握手过程是怎样的？和 HTTP 有什么关系？</h3>
     *
     * <p>WebSocket 基于 HTTP 协议升级（Upgrade）实现：</p>
     * <ol>
     *   <li>客户端发起 HTTP GET 请求，携带特殊头：
     *     <ul>
     *       <li>{@code Upgrade: websocket} —— 告诉服务端要升级协议</li>
     *       <li>{@code Connection: Upgrade} —— 连接需要升级</li>
     *       <li>{@code Sec-WebSocket-Key: <base64随机16字节>} —— 握手验证密钥</li>
     *       <li>{@code Sec-WebSocket-Version: 13} —— 协议版本</li>
     *     </ul>
     *   </li>
     *   <li>服务端验证后返回 101 Switching Protocols：
     *     <ul>
     *       <li>{@code HTTP/1.1 101 Switching Protocols}</li>
     *       <li>{@code Upgrade: websocket}</li>
     *       <li>{@code Connection: Upgrade}</li>
     *       <li>{@code Sec-WebSocket-Accept: Base64(SHA1(Key + GUID))}</li>
     *     </ul>
     *   </li>
     *   <li>握手完成后，TCP 连接升级为 WebSocket 全双工通道，后续使用帧格式通信</li>
     * </ol>
     *
     * <p>与 HTTP 的区别：</p>
     * <ul>
     *   <li>HTTP：请求-响应模式，半双工（同一时间只能一端发）</li>
     *   <li>WebSocket：全双工，服务端可主动推送，帧头仅 2-10 字节，开销极小</li>
     *   <li>HTTP 长轮询：客户端不断发请求，每次携带完整 HTTP 头（数百字节）</li>
     * </ul>
     */
    static void q1HandshakePrinciple() {
        System.out.println("=== Q1: WebSocket 握手原理 ===\n");
        System.out.println("客户端请求：");
        System.out.println("""
                GET /chat HTTP/1.1
                Host: server.example.com
                Upgrade: websocket
                Connection: Upgrade
                Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
                Sec-WebSocket-Version: 13
                """);
        System.out.println("服务端响应：");
        System.out.println("""
                HTTP/1.1 101 Switching Protocols
                Upgrade: websocket
                Connection: Upgrade
                Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
                """);
        System.out.println("计算过程：Base64(SHA1(clientKey + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'))");
        System.out.println("WebSocket 帧头仅 2-10 字节，HTTP 请求头通常 500-800 字节。\n");
    }

    // ======================== Q2: 心跳与重连方案 ========================

    /**
     * <h3>Q2：WebSocket 如何做心跳保活和断线重连？</h3>
     *
     * <p>心跳机制（两种方案）：</p>
     * <ul>
     *   <li><b>方案 A：应用层 Ping-Pong</b>
     *     <ul>
     *       <li>客户端定时（30s）发送 WebSocket Ping 帧（Opcode 0x9）</li>
     *       <li>服务端自动回复 Pong 帧（Opcode 0xA）</li>
     *       <li>连续 N 次未收到 Pong 判定断线</li>
     *       <li>优点：标准协议，浏览器原生支持</li>
     *     </ul>
     *   </li>
     *   <li><b>方案 B：服务端 IdleStateHandler（Netty）</b>
     *     <ul>
     *       <li>readerIdle：读空闲 N 秒 → 发 Ping 探测或直接关闭</li>
     *       <li>writerIdle：写空闲 N 秒 → 发心跳包保活</li>
     *       <li>allIdle：读写都空闲 N 秒 → 关闭连接</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>重连策略（指数退避）：</p>
     * <ul>
     *   <li>重连间隔：1s → 2s → 4s → 8s → 16s → 30s (max)</li>
     *   <li>加上随机因子（jitter）避免惊群效应</li>
     *   <li>重连成功后恢复 Session，补推积压的离线消息</li>
     * </ul>
     */
    static void q2HeartbeatReconnect() {
        System.out.println("=== Q2: 心跳保活与断线重连 ===\n");
        System.out.println("心跳：");
        System.out.println("  客户端 → Ping(Opcode 0x9) → 服务端 → Pong(Opcode 0xA) → 客户端");
        System.out.println("  连续 3 次未收到 Pong → 判定断线 → 触发重连");
        System.out.println();
        System.out.println("服务端 IdleStateHandler：");
        System.out.println("  readerIdle=15s → 15秒无读数据 → 发Ping探测");
        System.out.println("  writerIdle=30s → 30秒无写数据 → 发Pong保活");
        System.out.println("  allIdle=60s   → 60秒无任何数据 → 关闭连接");
        System.out.println();
        System.out.println("重连退避（指数退避 + Jitter）：");
        System.out.println("  1s → 2s → 4s → 8s → 16s → 30s(max)");
        System.out.println("  加 ±25% 随机 jitter 防止同时重连压垮服务端");
        System.out.println("  状态机: CONNECTED → DISCONNECTED → RECONNECTING → RECONNECTED/FAILED\n");
    }

    // ======================== Q3: 10w+ 连接优化 ========================

    /**
     * <h3>Q3：10 万级 WebSocket 连接如何优化内存和性能？</h3>
     *
     * <ul>
     *   <li><b>1. 减少 Session 对象内存占用：</b>
     *     <ul>
     *       <li>使用基本类型代替包装类（long 代替 Long）</li>
     *       <li>延迟初始化 attributes Map（大多数 Session 不需要额外属性）</li>
     *       <li>压缩 userId/sessionId（短 ID 代替 UUID）</li>
     *     </ul>
     *   </li>
     *   <li><b>2. Netty 内存优化：</b>
     *     <ul>
     *       <li>使用堆外内存（DirectByteBuf）+ 池化（PooledByteBufAllocator）</li>
     *       <li>调整 SO_RCVBUF/SO_SNDBUF 大小（默认 128KB 太大，可降至 8KB）</li>
     *       <li>开启 TCP_NODELAY 减少延迟</li>
     *     </ul>
     *   </li>
     *   <li><b>3. 操作系统层面：</b>
     *     <ul>
     *       <li>文件描述符上限：ulimit -n 655350</li>
     *       <li>TCP 参数调优：tcp_tw_reuse, tcp_fin_timeout, tcp_max_syn_backlog</li>
     *     </ul>
     *   </li>
     *   <li><b>4. 序列化/压缩：</b>
     *     <ul>
     *       <li>非活跃 Session 序列化到外部存储（Redis），用时再加载</li>
     *       <li>大消息压缩（Permessage-deflate 扩展）</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    static void q3TenThousandConnections() {
        System.out.println("=== Q3: 10w+ 连接内存优化 ===\n");
        System.out.println("单 Session 内存估算（优化前 vs 优化后）：");
        System.out.println("  优化前: sessionId(UUID 36B) + userId(20B) + attributes(Map 200B+) ≈ 300B");
        System.out.println("  优化后: shortId(8B) + primitive fields + lazy attributes ≈ 100B");
        System.out.println("  10w 连接：30MB → 10MB");
        System.out.println();
        System.out.println("关键优化点：");
        System.out.println("  1. Netty: PooledByteBufAllocator + DirectByteBuf");
        System.out.println("  2. OS: ulimit -n 655350 + TCP 参数调优");
        System.out.println("  3. 应用: 延迟初始化 + 基本类型 + 序列化非活跃Session");
        System.out.println("  4. 压缩: Permessage-deflate 扩展（RFC 7692）\n");
    }

    // ======================== Q4: 集群方案 ========================

    /**
     * <h3>Q4：WebSocket 集群方案有哪些？如何跨节点推送消息？</h3>
     *
     * <p>WebSocket 是有状态协议，集群需要解决「连接在节点 A，消息从节点 B 发出」的问题：</p>
     *
     * <p><b>方案一：Redis Pub/Sub 跨节点广播（推荐轻量场景）</b></p>
     * <ul>
     *   <li>所有节点订阅同一个 Redis Channel</li>
     *   <li>节点 A 需要向 uid=123 发消息时，Publish 到 Redis</li>
     *   <li>持有 uid=123 连接的节点收到消息后，找到对应 Session 推送</li>
     *   <li>优点：简单，延迟低；缺点：消息全广播（非目标节点的也收到）</li>
     * </ul>
     *
     * <p><b>方案二：Kafka / RocketMQ 消息中转（推荐高吞吐场景）</b></p>
     * <ul>
     *   <li>消息写入 MQ Topic，各节点作为 Consumer Group 消费</li>
     *   <li>可按 userId hash 分区，确保同一用户消息有序</li>
     *   <li>优点：可靠，可回溯；缺点：延迟略高</li>
     * </ul>
     *
     * <p><b>方案三：一致性哈希路由（推荐）</b></p>
     * <ul>
     *   <li>Nginx/API Gateway 按 userId 一致性哈希路由，同一用户始终落到同一节点</li>
     *   <li>节点间无需转发，架构最简单</li>
     *   <li>缺点：节点上下线需重分配</li>
     * </ul>
     */
    static void q4ClusterSolution() {
        System.out.println("=== Q4: WebSocket 集群方案 ===\n");
        System.out.println("方案 1: Redis Pub/Sub");
        System.out.println("  节点A → PUBLISH channel:user:123 \"hello\" → Redis");
        System.out.println("  节点B(持有uid=123) ← SUBSCRIBE ← Redis → 找到Session → 推送");
        System.out.println("  优点: 简单低延迟 | 缺点: 全广播，非目标节点也收到");
        System.out.println();
        System.out.println("方案 2: Kafka 消息中转");
        System.out.println("  消息 → Kafka Topic(按userId分区) → 各节点Consumer消费");
        System.out.println("  优点: 可靠可回溯 | 缺点: 延迟略高(~10ms)");
        System.out.println();
        System.out.println("方案 3: 一致性哈希路由（推荐）");
        System.out.println("  Nginx/网关 → userId哈希 → 固定节点 → 无需跨节点转发");
        System.out.println("  优点: 最简单 | 缺点: 节点变更需重分配");
        System.out.println();
        System.out.println("生产推荐: 一致性哈希 + Redis Pub/Sub 兜底（处理重分配场景）\n");
    }

    // ======================== Q5: WebSocket vs Socket ========================

    /**
     * <h3>Q5：WebSocket 和 Socket 有什么区别？各自适用场景？</h3>
     *
     * <ul>
     *   <li><b>Socket（TCP Socket）</b>：
     *     <ul>
     *       <li>传输层抽象，操作系统 API，对上层应用暴露字节流</li>
     *       <li>无协议规范，需自定义通信协议（定长/分隔符/长度前缀）</li>
     *       <li>适用：游戏长连接、IoT 设备通信、自定义协议场景</li>
     *     </ul>
     *   </li>
     *   <li><b>WebSocket</b>：
     *     <ul>
     *       <li>应用层协议（RFC 6455），运行在 TCP 之上</li>
     *       <li>有标准帧格式、握手流程、Opcode 类型</li>
     *       <li>浏览器原生支持（JS WebSocket API）</li>
     *       <li>天然穿透防火墙/代理（基于 HTTP 升级）</li>
     *       <li>适用：Web 实时推送、在线客服、协同编辑、行情推送</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>核心区别：WebSocket 是 Socket 的上层封装，本质还是 TCP。
     * 选型关键：需要浏览器兼容 → WebSocket；自定义协议 → Socket。</p>
     */
    static void q5WebSocketVsSocket() {
        System.out.println("=== Q5: WebSocket vs Socket ===\n");
        System.out.println("比较维度     | WebSocket                    | Socket(TCP)");
        System.out.println("-----------|------------------------------|--------------------------");
        System.out.println("OSI 层级     | 应用层(RFC 6455)              | 传输层(OS API)");
        System.out.println("协议        | 标准帧格式、握手流程            | 无规范，自定义");
        System.out.println("浏览器支持   | 原生 JS API                   | 不支持");
        System.out.println("防火墙穿透   | HTTP Upgrade，天然兼容          | 需额外配置");
        System.out.println("帧头开销     | 2-10 字节                      | 无(自定义)");
        System.out.println("典型场景     | Web推送、IM、协同编辑           | 游戏、IoT、自定义协议");
        System.out.println("安全         | wss:// (TLS)                  | SSL/TLS");
        System.out.println();
        System.out.println("本质: WebSocket = TCP + HTTP Upgrade + 标准帧协议");
    }

    // ======================== Nginx 代理配置 ========================

    static void nginxConfig() {
        System.out.println("=== Nginx 代理 WebSocket 配置要点 ===\n");
        System.out.println("""
                location /ws/ {
                    proxy_pass http://backend;
                    proxy_http_version 1.1;           # ★必须 HTTP/1.1
                    proxy_set_header Upgrade $http_upgrade;   # ★透传 Upgrade
                    proxy_set_header Connection "upgrade";    # ★透传 Connection
                    proxy_read_timeout 3600s;         # 长连接不超时
                    proxy_send_timeout 3600s;
                }
                """);
        System.out.println("关键：proxy_http_version 必须为 1.1，Upgrade 和 Connection 必须透传。\n");
    }

    // ======================== 主入口 ========================

    public static void main(String[] args) {
        System.out.println("########## WebSocket 面试高频问题 ##########\n");
        q1HandshakePrinciple();
        q2HeartbeatReconnect();
        q3TenThousandConnections();
        q4ClusterSolution();
        q5WebSocketVsSocket();
        nginxConfig();
        System.out.println("########## 面试问题演示完成 ##########");
    }
}