package com.nopr.mq.rocketmq.simulate;

import java.util.*;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】消息过滤 —— Tag 过滤·SQL92 表达式·Broker 端过滤
 * 【描述】模拟 RocketMQ 消息过滤机制：Tag 过滤（单维度快速匹配）和
 *         SQL92 表达式过滤（多属性组合条件，如 a > 5 AND b = 'x'）。
 *         Broker 端完成过滤后再推送给 Consumer，减少网络传输。
 * 【关键概念】消息过滤、Tag、SQL92、ExpressionType、Broker 端过滤、
 *             FilterServer、TAG/SQL92、用户属性
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class MessageFilterDemo {

    record Message(String msgId, String topic, String tag, Map<String, String> properties, String body) {}

    enum FilterType { NONE, TAG, SQL92 }

    record Subscription(String topic, FilterType type, String expression) {}

    static class FilterBroker {
        private final Map<String, Queue<Message>> queues = new LinkedHashMap<>();
        private final List<Subscription> subscriptions = new ArrayList<>();

        void putMessage(Message msg) {
            queues.computeIfAbsent(msg.topic(), k -> new LinkedList<>()).offer(msg);
        }

        void subscribe(String topic, String expression) {
            FilterType type;
            if (expression == null || expression.equals("*")) {
                type = FilterType.NONE;
            } else if (expression.matches("[a-zA-Z0-9|*]+")) {
                type = FilterType.TAG;
            } else {
                type = FilterType.SQL92;
            }
            subscriptions.add(new Subscription(topic, type, expression));
        }

        List<Message> pullWithFilter(String topic, String expression) {
            Queue<Message> q = queues.get(topic);
            if (q == null || q.isEmpty()) return Collections.emptyList();

            FilterType type;
            if (expression == null || expression.equals("*")) {
                type = FilterType.NONE;
            } else if (expression.matches("[a-zA-Z0-9|*]+")) {
                type = FilterType.TAG;
            } else {
                type = FilterType.SQL92;
            }

            return q.stream().filter(msg -> match(msg, type, expression)).toList();
        }

        private boolean match(Message msg, FilterType type, String expression) {
            return switch (type) {
                case NONE -> true;
                case TAG -> {
                    String[] allowedTags = expression.split("\\|");
                    yield Arrays.asList(allowedTags).contains(msg.tag());
                }
                case SQL92 -> evaluateSQL92(msg, expression);
            };
        }

        private boolean evaluateSQL92(Message msg, String expression) {
            // 简化 SQL92 求值：支持 "a > N AND b = 'X'" 格式
            Map<String, String> props = msg.properties();
            if (props == null || props.isEmpty()) return false;

            try {
                String[] andParts = expression.toUpperCase().split("\\s+AND\\s+");
                for (String part : andParts) {
                    part = part.trim();
                    if (part.contains(">")) {
                        String[] kv = part.split(">");
                        String key = kv[0].trim();
                        int expected = Integer.parseInt(kv[1].trim());
                        int actual = Integer.parseInt(props.getOrDefault(key, "-1"));
                        if (actual <= expected) return false;
                    } else if (part.contains("=")) {
                        String[] kv = part.split("=");
                        String key = kv[0].trim();
                        String expected = kv[1].trim().replace("'", "");
                        String actual = props.getOrDefault(key, "");
                        if (!expected.equals(actual)) return false;
                    } else if (part.contains("<")) {
                        String[] kv = part.split("<");
                        String key = kv[0].trim();
                        int expected = Integer.parseInt(kv[1].trim());
                        int actual = Integer.parseInt(props.getOrDefault(key, "999999"));
                        if (actual >= expected) return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  RocketMQ 消息过滤 Demo");
        System.out.println("=".repeat(60));

        FilterBroker broker = new FilterBroker();

        broker.putMessage(new Message("M1", "OrderTopic", "TagA",
                Map.of("price", "100", "region", "east"), "手机订单"));
        broker.putMessage(new Message("M2", "OrderTopic", "TagB",
                Map.of("price", "50", "region", "west"), "图书订单"));
        broker.putMessage(new Message("M3", "OrderTopic", "TagA",
                Map.of("price", "200", "region", "east"), "电脑订单"));
        broker.putMessage(new Message("M4", "OrderTopic", "TagC",
                Map.of("price", "30", "region", "east"), "零食订单"));

        System.out.println("\n--- Tag 过滤：订阅 TagA（表达式: TagA）---");
        broker.subscribe("OrderTopic", "TagA");
        List<Message> tagFiltered = broker.pullWithFilter("OrderTopic", "TagA");
        tagFiltered.forEach(m ->
                System.out.printf("  msgId=%s tag=%s body=%s%n", m.msgId(), m.tag(), m.body()));

        System.out.println("\n--- Tag 过滤：订阅 TagA|TagB（表达式: TagA|TagB）---");
        List<Message> multiTagFiltered = broker.pullWithFilter("OrderTopic", "TagA|TagB");
        multiTagFiltered.forEach(m ->
                System.out.printf("  msgId=%s tag=%s body=%s%n", m.msgId(), m.tag(), m.body()));

        System.out.println("\n--- SQL92 过滤：price > 80 AND region = 'east' ---");
        List<Message> sqlFiltered = broker.pullWithFilter("OrderTopic", "price > 80 AND region = 'east'");
        sqlFiltered.forEach(m ->
                System.out.printf("  msgId=%s price=%s region=%s body=%s%n",
                        m.msgId(), m.properties().get("price"),
                        m.properties().get("region"), m.body()));

        System.out.println("\n--- 不过滤（表达式: *）---");
        List<Message> all = broker.pullWithFilter("OrderTopic", "*");
        all.forEach(m ->
                System.out.printf("  msgId=%s tag=%s body=%s%n", m.msgId(), m.tag(), m.body()));

        System.out.println("\n\uD83D\uDCA1 Tag 过滤适合简单场景，SQL92 适合多属性组合条件");
        System.out.println("   Kafka 无原生消息过滤，需 Consumer 端自行过滤或在 Connect 层处理");
    }
}
