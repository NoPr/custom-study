package com.nopr.mq.rocketmq.simulate;

import java.util.*;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】死信队列 —— 消费重试上限·DLQ·人工干预
 * 【描述】模拟 RocketMQ 死信队列机制：消费者重试 N 次（默认 16 次）后仍失败的消息
 *         自动转入死信队列 (%DLQ%ConsumeGroup)，避免阻塞正常消息消费。
 *         DLQ 中的消息需要人工介入处理后重新投递。
 * 【关键概念】死信队列、DLQ、消费重试上限、%DLQ%、消息回溯、
 *             ConsumeConcurrentlyStatus.RECONSUME_LATER
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.ConsumeRetryDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class DeadLetterQueueDemo {

    static class Message {
        final String msgId;
        final String topic;
        final String body;
        final long bornTime;
        int retryTimes;

        Message(String msgId, String topic, String body) {
            this.msgId = msgId;
            this.topic = topic;
            this.body = body;
            this.bornTime = System.currentTimeMillis();
            this.retryTimes = 0;
        }
    }

    static class DeadLetterBroker {
        private final int maxRetryTimes;
        private final Map<String, Queue<Message>> queues = new LinkedHashMap<>();
        private final Map<String, List<Message>> dlq = new LinkedHashMap<>();
        private final List<String> eventLog = new ArrayList<>();

        DeadLetterBroker(int maxRetryTimes) {
            this.maxRetryTimes = maxRetryTimes;
        }

        void putMessage(String topic, Message msg) {
            queues.computeIfAbsent(topic, k -> new LinkedList<>()).offer(msg);
            eventLog.add(String.format("[生产] topic=%s msgId=%s body=%s", topic, msg.msgId, msg.body));
        }

        Message pullMessage(String topic) {
            Queue<Message> q = queues.get(topic);
            if (q == null || q.isEmpty()) return null;
            return q.poll();
        }

        boolean consumeAndRetry(String topic, String consumerGroup, boolean success) {
            Message msg = pullMessage(topic);
            if (msg == null) return false;

            if (success) {
                eventLog.add(String.format("[消费成功] group=%s msgId=%s body=%s",
                        consumerGroup, msg.msgId, msg.body));
                return true;
            }

            msg.retryTimes++;
            if (msg.retryTimes > maxRetryTimes) {
                String dlqTopic = "%DLQ%" + consumerGroup;
                dlq.computeIfAbsent(dlqTopic, k -> new ArrayList<>()).add(msg);
                eventLog.add(String.format("[转入DLQ] group=%s msgId=%s body=%s retryTimes=%d/%d",
                        consumerGroup, msg.msgId, msg.body, msg.retryTimes, maxRetryTimes));
            } else {
                queues.computeIfAbsent(topic, k -> new LinkedList<>()).offer(msg);
                eventLog.add(String.format("[消费失败-重试] group=%s msgId=%s body=%s retryTimes=%d/%d",
                        consumerGroup, msg.msgId, msg.body, msg.retryTimes, maxRetryTimes));
            }
            return false;
        }

        List<Message> getDLQ(String consumerGroup) {
            return dlq.getOrDefault("%DLQ%" + consumerGroup, Collections.emptyList());
        }

        void printEventLog() {
            eventLog.forEach(System.out::println);
        }

        void printDLQState(String consumerGroup) {
            List<Message> deadMsgs = getDLQ(consumerGroup);
            System.out.printf("%n  DLQ (%s) 共 %d 条死信消息:%n",
                    "%DLQ%" + consumerGroup, deadMsgs.size());
            deadMsgs.forEach(m ->
                System.out.printf("    msgId=%s body=%s retryTimes=%d%n",
                        m.msgId, m.body, m.retryTimes));
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  RocketMQ 死信队列 (DLQ) Demo");
        System.out.println("=".repeat(60));

        DeadLetterBroker broker = new DeadLetterBroker(3);
        String topic = "order-topic";
        String group = "order-consumer-group";

        broker.putMessage(topic, new Message("MSG-001", topic, "创建订单"));
        broker.putMessage(topic, new Message("MSG-002", topic, "支付订单"));
        broker.putMessage(topic, new Message("MSG-003", topic, "取消订单"));

        System.out.println("\n--- MSG-001: 消费成功 ---");
        broker.consumeAndRetry(topic, group, true);

        System.out.println("\n--- MSG-002: 连续消费失败（超过重试上限→DLQ）---");
        for (int i = 0; i < 5; i++) {
            broker.consumeAndRetry(topic, group, false);
        }

        System.out.println("\n--- MSG-003: 消费失败 1 次后成功 ---");
        broker.consumeAndRetry(topic, group, false);
        broker.consumeAndRetry(topic, group, true);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  事件日志");
        System.out.println("=".repeat(60));
        broker.printEventLog();
        broker.printDLQState(group);

        System.out.println("\n\uD83D\uDCA1 死信队列防止坏消息阻塞消费，需人工介入处理");
        System.out.println("   可通过 RocketMQ Console 查看 DLQ 消息并重新投递");
    }
}
