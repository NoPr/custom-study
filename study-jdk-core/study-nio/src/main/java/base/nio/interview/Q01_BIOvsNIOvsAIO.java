package base.nio.interview;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 面试题：BIO vs NIO vs AIO 区别
 *
 * 高频考点：
 * 1. 阻塞 vs 非阻塞 vs 异步
 * 2. 线程模型
 * 3. 适用场景
 * 4. 底层实现（select/poll/epoll/iocp）
 * 5. 为什么 Netty 选择 NIO 而不是 AIO
 */
public class Q01_BIOvsNIOvsAIO {

    public static void main(String[] args) throws Exception {
        conceptualComparison();
        bioExample();
        nioExample();
        aioExample();
        underlyingImplementation();
        threadModelComparison();
        nettyChoiceExplain();
    }

    static void conceptualComparison() {
        System.out.println("=== BIO vs NIO vs AIO 概念对比 ===");
        System.out.println();
        System.out.println("┌──────────┬───────────────┬───────────────┬─────────────────────────┐");
        System.out.println("│ 维度      │ BIO            │ NIO            │ AIO                      │");
        System.out.println("├──────────┼───────────────┼───────────────┼─────────────────────────┤");
        System.out.println("│ 全称      │ Blocking IO    │ Non-blocking IO│ Asynchronous IO          │");
        System.out.println("│ 连接模型  │ 1 连接 = 1 线程 │ 1 线程管理 N 连接│ 回调/事件驱动            │");
        System.out.println("│ 阻塞点    │ accept/read/wr.│ select() 阻塞   │ 无阻塞（完全异步）        │");
        System.out.println("│ 线程开销  │ 极高（C10K 失效）│ 极低            │ 低                       │");
        System.out.println("│ 编程难度  │ 简单            │ 中等            │ 复杂                     │");
        System.out.println("│ 底层      │ recv/send       │ select/poll/epol│ epoll + 线程池/IOCP      │");
        System.out.println("│ 数据流    │ 面向流（Stream） │ 面向缓冲（Buffer）│ 面向缓冲（Buffer）       │");
        System.out.println("└──────────┴───────────────┴───────────────┴─────────────────────────┘");
        System.out.println();
    }

    static void bioExample() {
        System.out.println("=== BIO 示例（阻塞 IO）===");
        System.out.println();

        byte[] buffer = new byte[1024];
        System.out.println("// BIO 伪代码");
        System.out.println("ServerSocket server = new ServerSocket(8080);");
        System.out.println("while (true) {");
        System.out.println("    Socket client = server.accept();        // 阻塞 1");
        System.out.println("    new Thread(() -> {");
        System.out.println("        InputStream in = client.getInputStream();");
        System.out.println("        in.read(buffer);                    // 阻塞 2");
        System.out.println("    }).start();");
        System.out.println("}");
        System.out.println();
        System.out.println("问题：10,000 连接 → 10,000 线程 → 10GB+ 栈内存 → 大量上下文切换");
        System.out.println();
    }

    /**
     * NIO 示例 — 实际可运行的 Selector 多路复用
     */
    static void nioExample() throws Exception {
        System.out.println("=== NIO 示例（非阻塞 + 多路复用）===");
        System.out.println();

        Selector selector = Selector.open();
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(9090));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("// NIO Selector 主循环");
        System.out.println("while (true) {");
        System.out.println("    selector.select();   // 阻塞直到有就绪事件");
        System.out.println("    for (SelectionKey key : selector.selectedKeys()) {");
        System.out.println("        if (key.isAcceptable()) {");
        System.out.println("            // 接受新连接，注册 OP_READ");
        System.out.println("        } else if (key.isReadable()) {");
        System.out.println("            // 读取数据 → ByteBuffer");
        System.out.println("        }");
        System.out.println("    }");
        System.out.println("}");
        System.out.println();

        new Thread(() -> {
            try {
                SocketChannel client = SocketChannel.open(
                        new InetSocketAddress("localhost", 9090));
                client.write(ByteBuffer.wrap(
                        "NIO DEMO".getBytes(StandardCharsets.UTF_8)));
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        selector.select(2000);
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            if (key.isAcceptable()) {
                SocketChannel client = serverChannel.accept();
                System.out.println("[NIO] 接受连接: " + client.getRemoteAddress());
                client.close();
            }
        }

        serverChannel.close();
        selector.close();
        System.out.println("优点：单线程管理 N 个连接，线程开销 O(1)");
        System.out.println();
    }

    /**
     * AIO 示例 — 异步回调模式
     */
    static void aioExample() throws Exception {
        System.out.println("=== AIO 示例（异步 IO）===");
        System.out.println();

        AsynchronousServerSocketChannel server = null;
        try {
            server = AsynchronousServerSocketChannel.open().bind(
                    new InetSocketAddress(9091));

            CompletionHandler<AsynchronousSocketChannel, Object> handler =
                    new CompletionHandler<>() {
                        @Override
                        public void completed(AsynchronousSocketChannel result,
                                              Object attachment) {
                            System.out.println("[AIO] 异步接受连接: " + result);
                            ByteBuffer buffer = ByteBuffer.allocate(256);
                            result.read(buffer, null,
                                    new CompletionHandler<Integer, Object>() {
                                        @Override
                                        public void completed(Integer bytesRead,
                                                              Object attachment) {
                                            buffer.flip();
                                            byte[] data = new byte[buffer.remaining()];
                                            buffer.get(data);
                                            System.out.println("[AIO] 异步读取: "
                                                    + new String(data,
                                                    StandardCharsets.UTF_8));
                                            try {
                                                result.close();
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }

                                        @Override
                                        public void failed(Throwable exc,
                                                           Object attachment) {
                                            exc.printStackTrace();
                                        }
                                    });
                        }

                        @Override
                        public void failed(Throwable exc, Object attachment) {
                            exc.printStackTrace();
                        }
                    };

            server.accept(null, handler);

            new Thread(() -> {
                try {
                    AsynchronousSocketChannel client =
                            AsynchronousSocketChannel.open();
                    Future<Void> connectFuture = client.connect(
                            new InetSocketAddress("localhost", 9091));
                    connectFuture.get(2, TimeUnit.SECONDS);

                    ByteBuffer writeBuffer = ByteBuffer.wrap(
                            "AIO DEMO".getBytes(StandardCharsets.UTF_8));
                    Future<Integer> writeFuture = client.write(writeBuffer);
                    writeFuture.get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            TimeUnit.SECONDS.sleep(1);
        } finally {
            if (server != null) {
                server.close();
            }
        }

        System.out.println();
        System.out.println("// AIO 伪代码");
        System.out.println("AsynchronousServerSocketChannel server = ...");
        System.out.println("server.accept(null, new CompletionHandler<>() {");
        System.out.println("    void completed(AsynchronousSocketChannel result, Object attach) {");
        System.out.println("        ByteBuffer buf = ...;");
        System.out.println("        result.read(buf, null, new CompletionHandler<>() { ... });");
        System.out.println("    }");
        System.out.println("});");
        System.out.println();
        System.out.println("特点：提交任务 → OS 异步执行 → 回调通知 → 无需 select() 循环");
        System.out.println();
    }

    static void underlyingImplementation() {
        System.out.println("=== 底层实现对比 ===");
        System.out.println();
        System.out.println("┌──────────┬──────────────────────────────────────────────┐");
        System.out.println("│ 系统调用   │ 说明                                          │");
        System.out.println("├──────────┼──────────────────────────────────────────────┤");
        System.out.println("│ select   │ O(n) 遍历所有 fd，fd 数上限 1024，TCP 轮询      │");
        System.out.println("│ poll     │ 链表存储 fd（无上限），但仍需 O(n) 遍历           │");
        System.out.println("│ epoll    │ 红黑树 + 就绪链表，O(1) 获取就绪事件（Linux 2.6+）│");
        System.out.println("│ kqueue   │ FreeBSD/MacOS 的 epoll 等价实现                │");
        System.out.println("│ IOCP     │ Windows 原生 AIO，真正的异步 I/O               │");
        System.out.println("│ io_uring │ Linux 5.1+，共享环形缓冲区，零系统调用            │");
        System.out.println("└──────────┴──────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("Java NIO 在不同平台的底层实现：");
        System.out.println("  Linux:   EPollSelectorProvider (epoll)");
        System.out.println("  MacOS:   KQueueSelectorProvider (kqueue)");
        System.out.println("  Windows: WindowsSelectorProvider (select，非 IOCP！)");
        System.out.println();
        System.out.println("注意：Java AIO 在 Linux 上基于 epoll + 线程池模拟，并非真正的 AIO。");
        System.out.println("Windows 上才是真正的 IOCP 异步。");
        System.out.println();
    }

    static void threadModelComparison() {
        System.out.println("=== 线程模型对比 ===");
        System.out.println();
        System.out.println("BIO 线程模型：");
        System.out.println("  [Acceptor 线程] → accept 连接 → new Thread(Handler)");
        System.out.println("  Handler 线程阻塞在 read()，连接数和线程数 1:1");
        System.out.println();
        System.out.println("NIO Reactor 模型：");
        System.out.println("  [Reactor 线程] → select() → dispatch → Handler（业务线程池）");
        System.out.println("  单 Reactor：1 个线程 accept + read + write");
        System.out.println("  主从 Reactor：MainReactor accept，SubReactor read/write");
        System.out.println();
        System.out.println("AIO Proactor 模型：");
        System.out.println("  [Proactor] → 提交异步请求 → OS 完成 → 回调 Handler");
        System.out.println("  OS 内核完成数据读写后才通知应用层（真正的异步）");
        System.out.println();
    }

    static void nettyChoiceExplain() {
        System.out.println("=== 为什么 Netty 选择 NIO 而不是 AIO？===");
        System.out.println();
        System.out.println("1. 跨平台：Java AIO 在不同平台表现不一致");
        System.out.println("   Linux 上 AIO 用 epoll + 线程池模拟，性能不如原生 epoll");
        System.out.println("   Windows 上才是真正的 IOCP");
        System.out.println();
        System.out.println("2. 生态成熟：NIO epoll 经过大规模生产验证");
        System.out.println("   epoll 是 Linux 内核级支持，十几年的优化积累");
        System.out.println();
        System.out.println("3. Netty 在 NIO 上已经构建了完整的 Reactor 模型");
        System.out.println("   EventLoopGroup（Boss/Worker）、Pipeline、ChannelHandler");
        System.out.println("   切换到 AIO 收益不明显，反而增加维护复杂度");
        System.out.println();
        System.out.println("4. 未完成操作的清理问题");
        System.out.println("   AIO 异步写未完成时关闭 Channel → 数据可能丢失或抛异常");
        System.out.println("   NIO 写完后立即关闭更可控");
        System.out.println();
        System.out.println("结论：除非 Windows 独占部署 + 需要高并发连接，否则优先选 NIO。");
        System.out.println();
    }
}