package com.nopr.mq.rocketmq.simulate;

import java.util.*;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】消息轨迹 —— 全链路追踪·Hook·生产/存储/消费时间戳
 * 【描述】模拟 RocketMQ 消息轨迹（Message Trace）机制：通过 Hook 在
 *         Producer 发送前、Broker 存储后、Consumer 消费前后采集时间戳，
 *         构建完整的消息生命周期追踪链路。
 *         演示消息从生产到消费各阶段的耗时分析。
 * 【关键概念】消息轨迹、MsgTraceDispatcher、SendMessageHook、
 *             ConsumeMessageHook、TraceConstants、链路追踪、耗时分析
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.RocketMQCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class MessageTraceDemo {

    enum TraceType { PRODUCE, STORE, CONSUME_START, CONSUME_END }

    record TraceRecord(String msgId, String group, TraceType type, long timestamp, String detail) {}

    static class MessageTrace {
        final String msgId;
        String topic;
        String body;
        long bornTime;
        long storeTime;
        long consumeStartTime;
        long consumeEndTime;
        boolean consumeSuccess;

        MessageTrace(String msgId) { this.msgId = msgId; }
    }

    static class TraceBroker {
        private final Map<String, Queue<Message>> queues = new LinkedHashMap<>();
        private final List<TraceRecord> traces = new ArrayList<>();
        private final Map<String, MessageTrace> traceMap = new LinkedHashMap<>();

        void record(TraceRecord record) {
            traces.add(record);
            MessageTrace trace = traceMap.computeIfAbsent(record.msgId(), MessageTrace::new);
            switch (record.type()) {
                case PRODUCE -> {
                    trace.topic = record.detail();
                    trace.bornTime = record.timestamp();
                }
                case STORE -> trace.storeTime = record.timestamp();
                case CONSUME_START -> trace.consumeStartTime = record.timestamp();
                case CONSUME_END -> trace.consumeEndTime = record.timestamp();
            }
        }

        void putMessage(String topic, String group, Message msg) {
            long now = System.currentTimeMillis();
            record(new TraceRecord(msg.msgId, group, TraceType.PRODUCE, now, topic));
            record(new TraceRecord(msg.msgId, group, TraceType.STORE, now + 10, "CommitLog"));
            queues.computeIfAbsent(topic, k -> new LinkedList<>()).offer(msg);
        }

        Message pullMessage(String topic) {
            Queue<Message> q = queues.get(topic);
            return q != null ? q.poll() : null;
        }

        void simulateConsume(String group, Message msg, boolean success) {
            long start = System.currentTimeMillis();
            record(new TraceRecord(msg.msgId, group, TraceType.CONSUME_START, start, msg.body));
            try { Thread.sleep(10 + (long) (Math.random() * 30)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            long end = System.currentTimeMillis();
            record(new TraceRecord(msg.msgId, group, TraceType.CONSUME_END, end,
                    success ? "SUCCESS" : "FAIL"));
            traceMap.get(msg.msgId).consumeSuccess = success;
        }

        void printTrace(String msgId) {
            MessageTrace t = traceMap.get(msgId);
            if (t == null) { System.out.println("  无轨迹数据"); return; }
            System.out.printf("  msgId: %s%n", t.msgId);
            System.out.printf("  生产时间: %s%n", new Date(t.bornTime));
            System.out.printf("  存储时间: %s (写入耗时 %dms)%n",
                    new Date(t.storeTime), t.storeTime - t.bornTime);
            System.out.printf("  消费开始: %s (排队耗时 %dms)%n",
                    new Date(t.consumeStartTime),
                    t.consumeStartTime - t.storeTime);
            System.out.printf("  消费结束: %s (处理耗时 %dms)%n",
                    new Date(t.consumeEndTime),
                    t.consumeEndTime - t.consumeStartTime);
            System.out.printf("  端到端延迟: %dms%n", t.consumeEndTime - t.bornTime);
            System.out.printf("  消费结果: %s%n", t.consumeSuccess ? "SUCCESS" : "FAIL");
        }
    }

    record Message(String msgId, String body) {}

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  RocketMQ 消息轨迹 (Message Trace) Demo");
        System.out.println("=".repeat(60));

        TraceBroker broker = new TraceBroker();
        String group = "trace-consumer-group";

        System.out.println("\n--- 生产消息 + 消费者处理 ---");
        broker.putMessage("OrderTopic", group, new Message("TRACE-001", "订单创建"));
        broker.putMessage("OrderTopic", group, new Message("TRACE-002", "订单支付"));
        broker.putMessage("OrderTopic", group, new Message("TRACE-003", "订单发货"));

        Message m1 = broker.pullMessage("OrderTopic");
        broker.simulateConsume(group, m1, true);

        Message m2 = broker.pullMessage("OrderTopic");
        broker.simulateConsume(group, m2, true);

        Message m3 = broker.pullMessage("OrderTopic");
        broker.simulateConsume(group, m3, false);

        System.out.println("\n--- 消息全链路追踪 ---");
        broker.printTrace("TRACE-001");
        System.out.println();
        broker.printTrace("TRACE-002");
        System.out.println();
        broker.printTrace("TRACE-003");

        System.out.println("\n\uD83D\uDCA1 消息轨迹帮助定位瓶颈：排队时间过长→Consumer扩容，处理耗时高→优化业务逻辑");
    }
}
