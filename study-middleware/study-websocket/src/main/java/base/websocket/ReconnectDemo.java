package base.websocket;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 断线重连：指数退避 + 重连状态机 + Session 恢复。
 *
 * <p>本类演示 WebSocket 断线重连的完整方案：</p>
 * <ul>
 *   <li>重连退避策略：1s → 2s → 4s → 8s → 16s → 30s(max)</li>
 *   <li>重连状态机：CONNECTED → DISCONNECTED → RECONNECTING → RECONNECTED / FAILED</li>
 *   <li>Session 恢复：重连后恢复之前的 Session（sessionId 不变，userId 重新绑定）</li>
 *   <li>消息积压处理：重连成功后补推离线消息</li>
 * </ul>
 */
public class ReconnectDemo {

    // ======================== 重连配置 ========================

    /** 退避间隔序列（秒）：1, 2, 4, 8, 16, 30 */
    static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};

    /** 最大重试次数 */
    static final int MAX_RETRY_COUNT = BACKOFF_SECONDS.length;

    /** 重连最终失败（用于标记已达最大重试） */
    static final int BACKOFF_FAILED = -1;

    // ======================== 重连状态机 ========================

    /**
     * 重连状态枚举。
     */
    enum ReconnectState {
        /** 已连接 */
        CONNECTED,
        /** 已断开 */
        DISCONNECTED,
        /** 正在重连 */
        RECONNECTING,
        /** 重连成功 */
        RECONNECTED,
        /** 重连失败（超过最大重试次数） */
        FAILED
    }

    // ======================== Session 恢复 ========================

    /**
     * 模拟的 WebSocket Session，重连时可恢复。
     */
    static class RecoverableSession {
        final String sessionId;
        final String userId;
        final Map<String, Object> attributes;
        volatile ReconnectState state;

        RecoverableSession(String sessionId, String userId) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.attributes = new ConcurrentHashMap<>();
            this.state = ReconnectState.CONNECTED;
        }

        @Override
        public String toString() {
            return String.format("Session{sid=%s, uid=%s, state=%s, attrs=%s}",
                    sessionId, userId, state, attributes);
        }
    }

    // ======================== 离线消息积压队列 ========================

    /**
     * 离线消息积压管理。
     * 重连成功后批量补推。
     */
    static class OfflineMessageQueue {
        /** userId → 离线消息队列 */
        final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> queues = new ConcurrentHashMap<>();

        /** 单用户最大积压消息数 */
        static final int MAX_BACKLOG = 100;

        /**
         * 缓存离线消息（用户掉线期间收到的消息）。
         */
        void cacheMessage(String userId, String message) {
            ConcurrentLinkedQueue<String> queue = queues.computeIfAbsent(userId,
                    k -> new ConcurrentLinkedQueue<>());
            if (queue.size() < MAX_BACKLOG) {
                queue.offer(message);
            } else {
                System.out.printf("[积压] uid=%s 消息队列已满(%d)，丢弃旧消息%n", userId, MAX_BACKLOG);
            }
        }

        /**
         * 拉取并清空积压消息（重连成功后调用）。
         */
        List<String> drainMessages(String userId) {
            ConcurrentLinkedQueue<String> queue = queues.remove(userId);
            if (queue == null) return Collections.emptyList();
            List<String> messages = new ArrayList<>();
            String msg;
            while ((msg = queue.poll()) != null) {
                messages.add(msg);
            }
            return messages;
        }
    }

    // ======================== 重连管理器 ========================

    /**
     * 重连管理器，按指数退避策略自动重连。
     */
    static class ReconnectManager {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final Map<String, RecoverableSession> sessions = new ConcurrentHashMap<>();
        final OfflineMessageQueue offlineQueue = new OfflineMessageQueue();
        final AtomicInteger retryCount = new AtomicInteger(0);

        /**
         * 获取下一次退避等待时间（秒）。
         * 序列：1 → 2 → 4 → 8 → 16 → 30。
         *
         * @param attempt 当前重试次数（从 0 开始）
         * @return 退避秒数，返回 -1 表示已达最大重试
         */
        int getBackoffSeconds(int attempt) {
            if (attempt >= BACKOFF_SECONDS.length) {
                return BACKOFF_FAILED;
            }
            return BACKOFF_SECONDS[attempt];
        }

        /**
         * 模拟连接断开。
         */
        void onDisconnect(String sessionId) {
            RecoverableSession session = sessions.get(sessionId);
            if (session == null) return;

            session.state = ReconnectState.DISCONNECTED;
            System.out.printf("[状态] %s → %s%n", sessionId, ReconnectState.DISCONNECTED);

            // 启动重连
            retryCount.set(0);
            scheduleReconnect(session);
        }

        /**
         * 调度下一次重连。
         */
        void scheduleReconnect(RecoverableSession session) {
            int attempt = retryCount.get();
            int backoff = getBackoffSeconds(attempt);

            if (backoff == BACKOFF_FAILED) {
                session.state = ReconnectState.FAILED;
                System.out.printf("[状态] %s → %s (已达最大重试 %d 次)%n",
                        session.sessionId, ReconnectState.FAILED, MAX_RETRY_COUNT);
                return;
            }

            session.state = ReconnectState.RECONNECTING;
            System.out.printf("[重连] %s 第 %d/%d 次尝试，等待 %ds...%n",
                    session.sessionId, attempt + 1, MAX_RETRY_COUNT, backoff);

            scheduler.schedule(() -> attemptReconnect(session), backoff, TimeUnit.SECONDS);
        }

        /**
         * 尝试重连。
         */
        void attemptReconnect(RecoverableSession session) {
            // 模拟重连：70% 概率成功
            boolean success = Math.random() > 0.3;

            if (success) {
                onReconnectSuccess(session);
            } else {
                onReconnectFail(session);
            }
        }

        /**
         * 重连成功：恢复 Session + 补推离线消息。
         */
        void onReconnectSuccess(RecoverableSession session) {
            session.state = ReconnectState.RECONNECTED;
            System.out.printf("[状态] %s → %s (userId=%s 重新绑定)%n",
                    session.sessionId, ReconnectState.RECONNECTED, session.userId);

            // 补推离线消息
            List<String> backlog = offlineQueue.drainMessages(session.userId);
            if (!backlog.isEmpty()) {
                System.out.printf("[补推] uid=%s 离线期间积压 %d 条消息:%n", session.userId, backlog.size());
                for (String msg : backlog) {
                    System.out.printf("  → %s%n", msg);
                }
            } else {
                System.out.printf("[补推] uid=%s 无积压消息%n", session.userId);
            }
        }

        /**
         * 重连失败：退避后再次尝试。
         */
        void onReconnectFail(RecoverableSession session) {
            System.out.printf("[重连] %s 第 %d 次失败%n", session.sessionId, retryCount.get() + 1);
            retryCount.incrementAndGet();
            scheduleReconnect(session);
        }

        /**
         * 注册 Session。
         */
        void register(RecoverableSession session) {
            sessions.put(session.sessionId, session);
            session.state = ReconnectState.CONNECTED;
        }

        /**
         * 模拟向用户发送消息，该用户离线时进入积压队列。
         */
        void sendMessage(String userId, String message) {
            boolean online = sessions.values().stream()
                    .anyMatch(s -> s.userId.equals(userId)
                            && (s.state == ReconnectState.CONNECTED || s.state == ReconnectState.RECONNECTED));

            if (online) {
                System.out.printf("[消息] 投递 uid=%s: %s%n", userId, message);
            } else {
                System.out.printf("[消息] uid=%s 离线，进入积压队列: %s%n", userId, message);
                offlineQueue.cacheMessage(userId, message);
            }
        }

        void shutdown() {
            scheduler.shutdown();
        }
    }

    // ======================== 演示入口 ========================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 断线重连：指数退避 + 状态机 + Session 恢复 ===\n");

        ReconnectManager rm = new ReconnectManager();

        RecoverableSession session = new RecoverableSession("SID-1001", "user-zhangsan");
        session.attributes.put("device", "iOS");
        session.attributes.put("version", "3.2.1");
        rm.register(session);
        System.out.println("初始: " + session);

        // 模拟：发送消息给在线用户
        rm.sendMessage("user-zhangsan", "你好，有一条新通知");

        // 模拟：断开连接
        System.out.println("\n--- 连接意外断开 ---");
        rm.onDisconnect(session.sessionId);

        // 模拟：离线期间收到消息（进入积压队列）
        rm.sendMessage("user-zhangsan", "离线消息 1：系统升级通知");
        rm.sendMessage("user-zhangsan", "离线消息 2：积分变动");
        rm.sendMessage("user-zhangsan", "离线消息 3：好友申请");

        // 等待重连过程完成
        Thread.sleep(20_000);

        // 重连成功后再次发送消息
        if (session.state == ReconnectState.RECONNECTED) {
            System.out.println("\n--- 重连成功，后续消息正常投递 ---");
            rm.sendMessage("user-zhangsan", "实时消息：你的订单已发货");
        }

        System.out.println("\n最终状态: " + session);
        rm.shutdown();
        System.out.println("\n=== 重连演示完成 ===");
    }
}