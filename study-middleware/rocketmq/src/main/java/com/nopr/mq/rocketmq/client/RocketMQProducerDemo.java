package com.nopr.mq.rocketmq.client;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.io.UnsupportedEncodingException;

/**
 * 【模块】rocketmq
 * 【分类】client
 * 【主题】RocketMQ Producer —— 同步·异步·单向发送
 * 【描述】使用 RocketMQ 官方客户端连接 NameServer，演示三种发送模式：
 *         同步发送（等待结果）、异步发送（回调通知）、单向发送（不关心结果）。
 *         先运行此 Demo 发送消息，再运行 RocketMQConsumerDemo 消费。
 * 【运行要求】NameServer 已启动（默认 192.168.4.52:9876）
 * 【关键概念】DefaultMQProducer、Sync/Async/Oneway、NameServerAddr、
 *             SendResult、SendCallback、SendStatus
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class RocketMQProducerDemo {

    private static final String NAMESRV_ADDR = "192.168.4.52:9876";
    private static final String PRODUCER_GROUP = "study-producer-group";
    private static final String TOPIC = "study-topic";

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(PRODUCER_GROUP);
        producer.setNamesrvAddr(NAMESRV_ADDR);
        producer.setSendMsgTimeout(5000);
        producer.start();
        System.out.println("Producer 启动成功, NameServer: " + NAMESRV_ADDR);

        syncSend(producer);
        asyncSend(producer);
        onewaySend(producer);

        Thread.sleep(2000);
        producer.shutdown();
        System.out.println("Producer 已关闭");
    }

    static void syncSend(DefaultMQProducer producer)
            throws UnsupportedEncodingException, MQBrokerException,
                   RemotingException, InterruptedException, MQClientException {
        System.out.println("\n--- 同步发送 ---");
        for (int i = 1; i <= 3; i++) {
            Message msg = new Message(TOPIC, "TagA",
                    ("同步消息-" + i).getBytes(RemotingHelper.DEFAULT_CHARSET));
            SendResult result = producer.send(msg);
            System.out.printf("  msgId=%s queueId=%d offset=%d%n",
                    result.getMsgId(), result.getMessageQueue().getQueueId(),
                    result.getQueueOffset());
        }
    }

    static void asyncSend(DefaultMQProducer producer)
            throws UnsupportedEncodingException, RemotingException,
                   InterruptedException, MQClientException {
        System.out.println("\n--- 异步发送 ---");
        for (int i = 1; i <= 3; i++) {
            final int idx = i;
            Message msg = new Message(TOPIC, "TagB",
                    ("异步消息-" + i).getBytes(RemotingHelper.DEFAULT_CHARSET));
            producer.send(msg, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    System.out.printf("  [成功] msgId=%s queueId=%d offset=%d%n",
                            result.getMsgId(), result.getMessageQueue().getQueueId(),
                            result.getQueueOffset());
                }

                @Override
                public void onException(Throwable e) {
                    System.err.printf("  [失败] index=%d error=%s%n", idx, e.getMessage());
                }
            });
        }
    }

    static void onewaySend(DefaultMQProducer producer)
            throws UnsupportedEncodingException, RemotingException,
                   InterruptedException, MQClientException {
        System.out.println("\n--- 单向发送（不关心结果）---");
        for (int i = 1; i <= 2; i++) {
            Message msg = new Message(TOPIC, "TagC",
                    ("单向消息-" + i).getBytes(RemotingHelper.DEFAULT_CHARSET));
            producer.sendOneway(msg);
            System.out.printf("  [发送] 单向消息-%d (无返回结果)%n", i);
        }
    }
}
