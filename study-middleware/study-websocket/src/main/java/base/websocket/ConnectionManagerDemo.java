package base.websocket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接管理器：Session 管理 + 路由表 + 广播 + 单播 + 分组。
 *
 * <p>本类演示 WebSocket 连接管理的核心数据结构与操作：</p>
 * <ul>
 *   <li>Session 数据结构：sessionId + userId + Channel + lastHeartbeat + attributes(Map)</li>
 *   <li>路由表：ConcurrentHashMap&lt;userId, Session&gt; + ConcurrentHashMap&lt;groupId, Set&lt;Session&gt;&gt;</li>
 *   <li>广播 sendToAll / 单播 sendToUser / 组播 sendToGroup</li>
 *   <li>连接生命周期：onConnect → onMessage → onClose → onError</li>
 * </ul>
 *
 * <p>本例使用纯 Java 模拟 Channel，实际项目中使用 Netty 的 ChannelHandlerContext 或
 * Spring WebSocket 的 WebSocketSession 替代。</p>
 */
public class ConnectionManagerDemo {

    // ======================== 数据模型 ========================

    /** WebSocket 会话，封装单个连接的所有状态。 */
    static class Session {
        /** 全局唯一会话 ID */
        final String sessionId;
        /** 绑定的用户 ID */
        String userId;
        /** 模拟的 Channel 引用（实际项目中为 Netty Channel 或 Spring WebSocketSession） */
        final SimChannel channel;
        /** 最近一次心跳时间戳 */
        volatile long lastHeartbeat;
        /** 连接建立时间 */
        final long connectTime;
        /** 自定义属性（如设备类型、登录 IP 等） */
        final Map<String, Object> attributes;

        Session(String sessionId, SimChannel channel) {
            this.sessionId = sessionId;
            this.channel = channel;
            this.lastHeartbeat = System.currentTimeMillis();
            this.connectTime = System.currentTimeMillis();
            this.attributes = new ConcurrentHashMap<>();
        }

        void updateHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
        }

        boolean isAlive(long idleTimeoutMs) {
            return (System.currentTimeMillis() - lastHeartbeat) < idleTimeoutMs;
        }

        @Override
        public String toString() {
            return String.format("Session{sid=%s, uid=%s, alive=%s, attrs=%s}",
                    sessionId, userId,
                    isAlive(30_000) ? "YES" : "NO_TIMEOUT",
                    attributes);
        }
    }

    /**
     * 模拟 Channel，真实的 WebSocket 连接封装。
     * 实际项目中对应 Netty 的 Channel 或 Spring 的 WebSocketSession。
     */
    static class SimChannel {
        final String channelId;
        volatile boolean open = true;

        SimChannel(String channelId) {
            this.channelId = channelId;
        }

        /** 向该 channel 发送文本消息 */
        void sendText(String message) {
            if (open) {
                System.out.printf("  [CH-%s] <<< %s%n", channelId, message);
            } else {
                System.out.printf("  [CH-%s] <<< (已关闭，消息丢弃)%n", channelId);
            }
        }

        void close() {
            open = false;
            System.out.printf("  [CH-%s] 连接关闭%n", channelId);
        }
    }

    // ======================== 连接管理器 ========================

    static class ConnectionManager {
        /** 所有在线会话：sessionId → Session */
        final ConcurrentHashMap<String, Session> sessionMap = new ConcurrentHashMap<>();

        /** 用户 → Session 路由表（一个用户可多端登录） */
        final ConcurrentHashMap<String, Set<Session>> userSessions = new ConcurrentHashMap<>();

        /** 分组 → 会话集合（用于组播） */
        final ConcurrentHashMap<String, Set<Session>> groupSessions = new ConcurrentHashMap<>();

        /** Session ID 生成器 */
        final AtomicLong sessionIdGen = new AtomicLong(0);

        /** 最大空闲时间（毫秒），超过视为断线 */
        static final long IDLE_TIMEOUT_MS = 30_000;

        // ---- 连接生命周期 ----

        /**
         * 新连接接入。
         *
         * @param channel 模拟 Channel
         * @return 新建的 Session
         */
        Session onConnect(SimChannel channel) {
            String sid = "SID-" + sessionIdGen.incrementAndGet();
            Session session = new Session(sid, channel);
            sessionMap.put(sid, session);
            System.out.printf("[生命周期] onConnect: %s%n", sid);
            return session;
        }

        /**
         * 用户绑定（登录后调用）。
         */
        void bindUser(String sessionId, String userId) {
            Session session = sessionMap.get(sessionId);
            if (session == null) return;
            session.userId = userId;
            userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
            System.out.printf("[生命周期] bindUser: %s -> uid=%s%n", sessionId, userId);
        }

        /**
         * 收到消息时更新心跳。
         */
        void onMessage(String sessionId) {
            Session session = sessionMap.get(sessionId);
            if (session != null) {
                session.updateHeartbeat();
            }
        }

        /**
         * 连接关闭。
         */
        void onClose(String sessionId) {
            Session session = sessionMap.remove(sessionId);
            if (session == null) return;
            System.out.printf("[生命周期] onClose: %s (uid=%s)%n", sessionId, session.userId);

            // 从用户路由表移除
            if (session.userId != null) {
                Set<Session> sessions = userSessions.get(session.userId);
                if (sessions != null) {
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        userSessions.remove(session.userId);
                    }
                }
            }

            // 从所有分组移除
            for (Set<Session> groupSet : groupSessions.values()) {
                groupSet.remove(session);
            }

            session.channel.close();
        }

        /**
         * 连接异常。
         */
        void onError(String sessionId, Throwable cause) {
            System.out.printf("[生命周期] onError: %s, cause=%s%n", sessionId,
                    cause != null ? cause.getMessage() : "unknown");
            onClose(sessionId);
        }

        // ---- 消息路由 ----

        /**
         * 广播：向所有在线连接发送消息。
         */
        void sendToAll(String message) {
            System.out.printf("[广播] 发送给 %d 个连接%n", sessionMap.size());
            for (Session session : sessionMap.values()) {
                session.channel.sendText(message);
            }
        }

        /**
         * 单播：向指定用户的所有连接发送消息。
         */
        void sendToUser(String userId, String message) {
            Set<Session> sessions = userSessions.get(userId);
            if (sessions == null || sessions.isEmpty()) {
                System.out.printf("[单播] uid=%s 不在线，消息丢弃%n", userId);
                return;
            }
            System.out.printf("[单播] 发送给 uid=%s 的 %d 个连接%n", userId, sessions.size());
            for (Session session : sessions) {
                session.channel.sendText(message);
            }
        }

        /**
         * 组播：向指定分组内所有连接发送消息。
         */
        void sendToGroup(String groupId, String message) {
            Set<Session> sessions = groupSessions.get(groupId);
            if (sessions == null || sessions.isEmpty()) {
                System.out.printf("[组播] group=%s 无成员%n", groupId);
                return;
            }
            System.out.printf("[组播] 发送给 group=%s 的 %d 个成员%n", groupId, sessions.size());
            for (Session session : sessions) {
                session.channel.sendText(message);
            }
        }

        // ---- 分组管理 ----

        /**
         * 将连接加入分组。
         */
        void joinGroup(String sessionId, String groupId) {
            Session session = sessionMap.get(sessionId);
            if (session == null) return;
            groupSessions.computeIfAbsent(groupId, k -> new CopyOnWriteArraySet<>()).add(session);
            System.out.printf("[分组] %s 加入 group=%s%n", sessionId, groupId);
        }

        /**
         * 将连接移出分组。
         */
        void leaveGroup(String sessionId, String groupId) {
            Set<Session> sessions = groupSessions.get(groupId);
            if (sessions == null) return;
            Session session = sessionMap.get(sessionId);
            if (session != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    groupSessions.remove(groupId);
                }
            }
            System.out.printf("[分组] %s 离开 group=%s%n", sessionId, groupId);
        }

        /**
         * 获取当前在线用户数。
         */
        int onlineCount() {
            return userSessions.size();
        }

        /**
         * 获取当前连接数。
         */
        int connectionCount() {
            return sessionMap.size();
        }

        /**
         * 打印当前路由表快照。
         */
        void printRoutingTable() {
            System.out.println("\n--- 路由表快照 ---");
            System.out.printf("连接数: %d, 在线用户: %d%n", connectionCount(), onlineCount());
            System.out.println("用户路由表:");
            for (Map.Entry<String, Set<Session>> entry : userSessions.entrySet()) {
                System.out.printf("  uid=%s → %d 个连接%n", entry.getKey(), entry.getValue().size());
            }
            System.out.println("分组路由表:");
            for (Map.Entry<String, Set<Session>> entry : groupSessions.entrySet()) {
                System.out.printf("  group=%s → %d 个成员%n", entry.getKey(), entry.getValue().size());
            }
            System.out.println("-----------------\n");
        }
    }

    // ======================== 演示入口 ========================

    public static void main(String[] args) {
        System.out.println("=== Netty 连接管理器演示 ===\n");

        ConnectionManager cm = new ConnectionManager();

        /*
         * 场景：模拟 3 个用户连接，2 个分组，演示广播/单播/组播。
         * 用户 Alice 双端登录（手机 + PC）。
         */

        System.out.println("--- 1. 连接建立 ---");
        Session s1 = cm.onConnect(new SimChannel("CH-1"));
        cm.bindUser(s1.sessionId, "Alice");
        cm.joinGroup(s1.sessionId, "chat-room");

        Session s2 = cm.onConnect(new SimChannel("CH-2"));
        cm.bindUser(s2.sessionId, "Alice"); // Alice 多端登录
        cm.joinGroup(s2.sessionId, "chat-room");

        Session s3 = cm.onConnect(new SimChannel("CH-3"));
        cm.bindUser(s3.sessionId, "Bob");
        cm.joinGroup(s3.sessionId, "chat-room");

        Session s4 = cm.onConnect(new SimChannel("CH-4"));
        cm.bindUser(s4.sessionId, "Charlie");
        cm.joinGroup(s4.sessionId, "game-room");

        cm.printRoutingTable();

        System.out.println("--- 2. 广播测试 ---");
        cm.sendToAll("系统公告：服务器将于 23:00 维护");

        System.out.println("\n--- 3. 单播测试 ---");
        cm.sendToUser("Alice", "你的专属消息：积分已到账"); // Alice 双端都会收到

        System.out.println("\n--- 4. 组播测试 ---");
        cm.sendToGroup("chat-room", "聊天室消息：大家好！");
        cm.sendToGroup("game-room", "游戏房间消息：准备开黑！");

        System.out.println("\n--- 5. 连接关闭 ---");
        cm.onClose(s1.sessionId); // Alice 的手机断开
        cm.printRoutingTable();

        System.out.println("--- 6. 单播给断开一端的用户 ---");
        cm.sendToUser("Alice", "消息仍会发给 Alice 的 PC 端");

        System.out.println("\n=== 演示完成 ===");
    }
}