package com.nopr.mq.rocketmq.client;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.List;

/**
 * 【模块】rocketmq
 * 【分类】client
 * 【主题】RocketMQ Push Consumer —— 订阅消费·Tag 过滤·重试
 * 【描述】使用 RocketMQ 官方 Push Consumer 订阅消息，演示 Tag 过滤订阅、
 *         并发消费处理、手动确认/重试。先运行 RocketMQProducerDemo 发送消息。
 * 【运行要求】NameServer 已启动（默认 192.168.4.52:9876）
 * 【关键概念】DefaultMQPushConsumer、subscribe、MessageListenerConcurrently、
 *             ConsumeConcurrentlyStatus、RECONSUME_LATER、CONSUME_SUCCESS
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class RocketMQConsumerDemo {

    private static final String NAMESRV_ADDR = "192.168.4.52:9876";
    private static final String CONSUMER_GROUP = "study-consumer-group";
    private static final String TOPIC = "study-topic";

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(CONSUMER_GROUP);
        consumer.setNamesrvAddr(NAMESRV_ADDR);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(4);
        consumer.setConsumeMessageBatchMaxSize(1);

        consumer.subscribe(TOPIC, "TagA || TagB || TagC");

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(
                    List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
                for (MessageExt msg : msgs) {
                    System.out.printf("  [消费] msgId=%s queueId=%d tags=%s body=%s%n",
                            msg.getMsgId(), msg.getQueueId(), msg.getTags(),
                            new String(msg.getBody()));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        consumer.start();
        System.out.println("Consumer 启动成功, NameServer: " + NAMESRV_ADDR);
        System.out.println("订阅: " + TOPIC + " (TagA || TagB || TagC)");
        System.out.println("等待消息... (Ctrl+C 退出)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.shutdown();
            System.out.println("Consumer 已关闭");
        }));

        Thread.currentThread().join();
    }
}
