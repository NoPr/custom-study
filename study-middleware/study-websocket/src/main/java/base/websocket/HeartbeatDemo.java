package base.websocket;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 心跳机制：Ping-Pong + 服务端空闲检测 + 断线重连。
 *
 * <p>本类演示 WebSocket 心跳保活机制：</p>
 * <ul>
 *   <li>Ping-Pong 心跳：客户端 30s 发 Ping，服务端回复 Pong</li>
 *   <li>服务端空闲检测：IdleStateHandler（readerIdle/writerIdle/allIdle）</li>
 *   <li>三次 Pong 未到视为断线（Ping→wait→Ping→wait→Ping→超时）</li>
 *   <li>断线 Cleanup：从分组移除 + 通知其他用户离线</li>
 * </ul>
 *
 * <p>实际项目中，Netty 的 IdleStateHandler 处理空闲检测，此处用纯 Java 模拟。</p>
 */
public class HeartbeatDemo {

    // ======================== 心跳配置 ========================

    /** 心跳间隔：客户端每 5 秒发送一次 Ping（演示用缩短间隔） */
    static final long PING_INTERVAL_SEC = 5;

    /** Pong 响应超时：等待 Pong 的最大时间 */
    static final long PONG_TIMEOUT_SEC = 3;

    /** 连续丢失 Pong 的最大次数，超过视为断线 */
    static final int MAX_MISSED_PONGS = 3;

    /** 服务端空闲检测间隔 */
    static final long SERVER_IDLE_CHECK_MS = 6_000;

    /** readerIdle：读空闲超时（秒） */
    static final long READER_IDLE_SEC = 15;

    /** writerIdle：写空闲超时（秒） */
    static final long WRITER_IDLE_SEC = 30;

    /** allIdle：读写都空闲超时（秒） */
    static final long ALL_IDLE_SEC = 60;

    // ======================== 空闲检测状态 ========================

    /**
     * 空闲检测状态枚举。
     * 对应 Netty IdleStateHandler 的三种事件。
     */
    enum IdleState {
        /** 读空闲（指定时间内未收到任何数据） */
        READER_IDLE,
        /** 写空闲（指定时间内未发送任何数据） */
        WRITER_IDLE,
        /** 全部空闲（读写都没有数据） */
        ALL_IDLE
    }

    // ======================== 模拟客户端 ========================

    /**
     * 模拟客户端，拥有独立的 Ping-Pong 逻辑。
     */
    static class SimClient {
        final String clientId;
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger missedPongs = new AtomicInteger(0);
        volatile long lastPongTime = System.currentTimeMillis();
        volatile boolean connected = true;

        /** 用于模拟服务端对客户端的回调 */
        SimServer server;

        SimClient(String clientId) {
            this.clientId = clientId;
        }

        /**
         * 启动客户端心跳：定时发送 Ping。
         */
        void startHeartbeat() {
            scheduler.scheduleAtFixedRate(() -> {
                if (!connected) return;
                System.out.printf("[%s] → 发送 Ping%n", clientId);
                if (server != null) {
                    boolean pongReceived = server.onPing(this);
                    if (pongReceived) {
                        missedPongs.set(0);
                        lastPongTime = System.currentTimeMillis();
                        System.out.printf("[%s] ← 收到 Pong (连续丢失=%d)%n", clientId, missedPongs.get());
                    } else {
                        int missed = missedPongs.incrementAndGet();
                        System.out.printf("[%s] ← Pong 超时! 连续丢失=%d/%d%n",
                                clientId, missed, MAX_MISSED_PONGS);
                        if (missed >= MAX_MISSED_PONGS) {
                            System.out.printf("[%s] ★ 三次 Pong 未到，判定断线！%n", clientId);
                            disconnect();
                        }
                    }
                }
            }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
        }

        void disconnect() {
            connected = false;
            scheduler.shutdown();
            if (server != null) {
                server.onClientDisconnect(this);
            }
            System.out.printf("[%s] *** 已断线，触发 Cleanup ***%n", clientId);
        }

        void shutdown() {
            connected = false;
            scheduler.shutdown();
        }
    }

    // ======================== 模拟服务端 ========================

    /**
     * 模拟 WebSocket 服务端，包含空闲检测和心跳处理。
     */
    static class SimServer {
        final Map<String, SimClient> clients = new ConcurrentHashMap<>();
        final Map<String, Long> lastReadTime = new ConcurrentHashMap<>();
        final Map<String, Long> lastWriteTime = new ConcurrentHashMap<>();
        final ScheduledExecutorService idleChecker = Executors.newSingleThreadScheduledExecutor();

        /** 在线用户变更回调 */
        interface OnlineChangeListener {
            void onUserOffline(String clientId);
        }

        OnlineChangeListener listener;

        SimServer(OnlineChangeListener listener) {
            this.listener = listener;
        }

        /**
         * 处理客户端 Ping，回复 Pong。
         *
         * @return true 表示成功回复 Pong
         */
        boolean onPing(SimClient client) {
            lastReadTime.put(client.clientId, System.currentTimeMillis());
            // 模拟 80% 概率回复 Pong
            boolean shouldReply = Math.random() > 0.2;
            if (shouldReply) {
                lastWriteTime.put(client.clientId, System.currentTimeMillis());
            }
            return shouldReply;
        }

        /**
         * 注册客户端。
         */
        void register(SimClient client) {
            clients.put(client.clientId, client);
            lastReadTime.put(client.clientId, System.currentTimeMillis());
            lastWriteTime.put(client.clientId, System.currentTimeMillis());
            client.server = this;
            System.out.printf("[SERVER] 客户端 %s 注册成功%n", client.clientId);
        }

        /**
         * 客户端断线回调。
         */
        void onClientDisconnect(SimClient client) {
            String cid = client.clientId;
            clients.remove(cid);
            lastReadTime.remove(cid);
            lastWriteTime.remove(cid);
            System.out.printf("[SERVER] 从分组/路由表移除 %s%n", cid);
            if (listener != null) {
                listener.onUserOffline(cid);
            }
        }

        /**
         * 启动服务端空闲检测。
         * 对应 Netty 的 IdleStateHandler 机制。
         */
        void startIdleCheck() {
            idleChecker.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, SimClient> entry : clients.entrySet()) {
                    String cid = entry.getKey();
                    Long lastRead = lastReadTime.getOrDefault(cid, now);
                    Long lastWrite = lastWriteTime.getOrDefault(cid, now);

                    long readIdle = (now - lastRead) / 1000;
                    long writeIdle = (now - lastWrite) / 1000;

                    if (readIdle >= READER_IDLE_SEC && writeIdle >= WRITER_IDLE_SEC) {
                        System.out.printf("[IdleCheck] %s ALL_IDLE (read=%ds, write=%ds) → 超时关闭%n",
                                cid, readIdle, writeIdle);
                        entry.getValue().disconnect();
                    } else if (readIdle >= READER_IDLE_SEC) {
                        System.out.printf("[IdleCheck] %s READER_IDLE %ds → 发送 Ping 探测%n", cid, readIdle);
                    } else if (writeIdle >= WRITER_IDLE_SEC) {
                        System.out.printf("[IdleCheck] %s WRITER_IDLE %ds → 发送 Pong 保活%n", cid, writeIdle);
                    }
                }
            }, SERVER_IDLE_CHECK_MS, SERVER_IDLE_CHECK_MS, TimeUnit.MILLISECONDS);
        }

        void shutdown() {
            idleChecker.shutdown();
        }
    }

    // ======================== 演示入口 ========================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 心跳机制与空闲检测演示 ===\n");

        SimServer server = new SimServer(clientId ->
                System.out.printf("  [通知] 用户 %s 已离线，通知其好友...%n", clientId));

        server.startIdleCheck();

        // 创建两个客户端
        SimClient clientA = new SimClient("Client-A");
        SimClient clientB = new SimClient("Client-B");

        server.register(clientA);
        server.register(clientB);

        clientA.startHeartbeat();
        clientB.startHeartbeat();

        System.out.println("观察心跳 Ping-Pong 过程（约 25s）...\n");

        // 运行约 25 秒让心跳演示，然后关闭
        Thread.sleep(25_000);

        System.out.println("\n--- 模拟客户端正常断开 ---");
        clientA.shutdown();
        server.onClientDisconnect(clientA);

        System.out.println("\n--- 模拟客户端 B 异常断开（停心跳但不通知服务端） ---");
        clientB.shutdown();
        // B 不掉 onClientDisconnect，让服务端空闲检测发现
        Thread.sleep(8_000);
        // 服务端 idleCheck 会检测到 Client-B 长时间无数据

        server.shutdown();
        System.out.println("\n=== 心跳演示完成 ===");
    }
}