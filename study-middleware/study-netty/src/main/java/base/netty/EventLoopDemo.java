package base.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EventLoop 机制：单线程循环 + Selector + Channel 注册。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>EventLoop</b>：单线程事件循环，while(true) 轮询 Selector，处理就绪的 IO 事件</li>
 *   <li><b>Boss EventLoopGroup</b>：负责监听 ACCEPT 事件，接收新连接并注册到 Worker</li>
 *   <li><b>Worker EventLoopGroup</b>：负责处理已建立连接的 READ/WRITE 事件</li>
 *   <li>每个 Channel 从注册到销毁，始终绑定在同一个 EventLoop 上，避免线程切换</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()，然后用 telnet localhost 8080 连接测试。
 *
 * @author study-tuling
 */
public class EventLoopDemo {

    /** 工作线程数 */
    private static final int WORKER_COUNT = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws IOException {
        System.out.println("=== Netty EventLoop 机制演示（基于 JDK NIO 模拟） ===\n");

        /*
         * ==================== 1. Boss EventLoopGroup（接收连接） ====================
         * Netty 中 Boss Group 通常只有 1 个线程，专门处理 OP_ACCEPT。
         * 因为服务端一般只有一个 ServerSocketChannel，一个线程足够。
         */
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(new InetSocketAddress(8080));
        System.out.println("[Boss] ServerSocketChannel 绑定端口 8080");

        Selector bossSelector = Selector.open();
        serverSocketChannel.register(bossSelector, SelectionKey.OP_ACCEPT);
        System.out.println("[Boss] 注册 OP_ACCEPT 到 Boss Selector");

        /*
         * ==================== 2. Worker EventLoopGroup（处理 IO） ====================
         * Netty 中 Worker Group 通常有 CPU 核数 * 2 个线程，处理 OP_READ / OP_WRITE。
         * 每个 Worker 有自己的 Selector 实现无锁串行化。
         */
        WorkerEventLoop[] workers = new WorkerEventLoop[WORKER_COUNT];
        Selector[] workerSelectors = new Selector[WORKER_COUNT];
        for (int i = 0; i < WORKER_COUNT; i++) {
            workerSelectors[i] = Selector.open();
            workers[i] = new WorkerEventLoop("worker-" + i, workerSelectors[i]);
            workers[i].start();
        }
        System.out.printf("[Worker] 创建 %d 个 Worker EventLoop（各带独立 Selector）%n%n", WORKER_COUNT);

        AtomicInteger roundRobinIndex = new AtomicInteger(0);

        /*
         * ==================== 3. Boss 事件循环 ====================
         * while(true) 死循环 select() 阻塞等待 IO 事件。
         * 这是 EventLoop 的核心——一个线程 + 一个 Selector + 一个死循环。
         */
        System.out.println(">>> Boss EventLoop 启动，等待连接...");
        System.out.println(">>> 使用 telnet localhost 8080 测试（或 curl http://localhost:8080）\n");

        while (true) {
            bossSelector.select();
            Set<SelectionKey> selectedKeys = bossSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();

                if (key.isAcceptable()) {
                    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                    SocketChannel clientChannel = serverChannel.accept();
                    clientChannel.configureBlocking(false);

                    /* 轮询分配 Worker */
                    int index = roundRobinIndex.getAndIncrement() % WORKER_COUNT;
                    workers[index].register(clientChannel);
                    System.out.printf("[Boss] 新连接 %s → 分配到 %s%n",
                            clientChannel.getRemoteAddress(), workers[index].name);
                }
            }
        }
        // bossSelector.close(); // unreachable in demo
    }

    /**
     * 模拟 Netty Worker EventLoop：单线程 + 独立 Selector + 任务队列。
     *
     * <p>每个 Worker 绑定一个 Selector，Channel 从注册到销毁始终在一个 Worker 上，
     * 实现无锁串行化——这是 Netty 高性能的关键设计之一。
     */
    static class WorkerEventLoop extends Thread {
        final String name;
        final Selector selector;
        /** 任务队列：用于异步注册 Channel（Netty 中用 MPSC 队列） */
        final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

        WorkerEventLoop(String name, Selector selector) {
            super(name);
            this.name = name;
            this.selector = selector;
            setDaemon(true);
        }

        /** 注册 Channel 到本 Worker（通过任务队列，避免直接操作 Selector） */
        void register(SocketChannel channel) {
            taskQueue.offer(() -> {
                try {
                    channel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(256));
                    selector.wakeup(); // 唤醒阻塞的 select()
                } catch (ClosedChannelException e) {
                    System.err.printf("[%s] 注册失败: %s%n", name, e.getMessage());
                }
            });
            selector.wakeup();
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    /* === 执行任务队列中的异步任务 === */
                    Runnable task;
                    while ((task = taskQueue.poll()) != null) {
                        task.run();
                    }

                    /* === select() 阻塞等待 IO 事件 === */
                    int readyCount = selector.select(1000);
                    if (readyCount == 0) {
                        continue;
                    }

                    /* === 处理就绪的 IO 事件 === */
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (key.isReadable()) {
                            handleRead(key);
                        }
                        if (key.isWritable()) {
                            handleWrite(key);
                        }
                    }
                } catch (IOException e) {
                    System.err.printf("[%s] EventLoop 异常: %s%n", name, e.getMessage());
                }
            }
        }

        /** 处理 OP_READ：读取数据，设置 OP_WRITE 回写响应 */
        private void handleRead(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            ByteBuffer buffer = (ByteBuffer) key.attachment();
            buffer.clear();

            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                System.out.printf("[%s] 客户端断开: %s%n", name, channel.getRemoteAddress());
                channel.close();
                return;
            }

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String message = new String(data).trim();
            System.out.printf("[%s] 收到(%dB): %s%n", name, bytesRead, message);

            /* 回写响应 —— 模拟 ChannelHandler 链处理后的响应 */
            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n"
                    + "[Worker-" + name + "] Echo: " + message + "\n";
            ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
            key.attach(responseBuffer);
            key.interestOps(SelectionKey.OP_WRITE);
        }

        /** 处理 OP_WRITE：写入响应后恢复监听 OP_READ */
        private void handleWrite(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            ByteBuffer buffer = (ByteBuffer) key.attachment();
            channel.write(buffer);

            if (!buffer.hasRemaining()) {
                /* 写完成，恢复监听 READ */
                key.interestOps(SelectionKey.OP_READ);
                key.attach(ByteBuffer.allocate(256));
            }
        }
    }
}