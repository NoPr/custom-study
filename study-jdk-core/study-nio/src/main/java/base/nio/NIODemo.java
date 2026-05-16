package base.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * NIO 服务器 + 客户端 — BIO vs NIO vs AIO 对比
 *
 * 核心知识点：
 * 1. BIO（Blocking IO）：一个连接一个线程，accept/read/write 全部阻塞
 * 2. NIO（Non-blocking IO）：Selector 多路复用，一个线程管理多个 Channel
 * 3. AIO（Asynchronous IO）：异步 IO，操作系统回调通知，JDK 7+
 *
 * NIO 三大核心组件：
 * - Channel：  双向读写（FileChannel/DatagramChannel/SocketChannel/ServerSocketChannel）
 * - Buffer：   数据容器（ByteBuffer/CharBuffer/IntBuffer...）
 * - Selector： 多路复用器（单线程监听多个 Channel 事件）
 *
 * Selector 执行流程：
 * ① 创建 Selector：Selector.open()
 * ② Channel 注册：channel.register(selector, SelectionKey.OP_XXX)
 * ③ select() 阻塞直到有就绪事件
 * ④ 遍历 selectedKeys，判断事件类型：OP_ACCEPT → OP_READ → OP_WRITE
 * ⑤ 处理完清除 key，继续 select() 循环
 *
 * ByteBuffer 核心方法：
 * - flip()：    limit=position, position=0（写模式 → 读模式）
 * - clear()：   position=0, limit=capacity（读模式 → 写模式，不清理数据）
 * - compact()： 未读数据移到头部，position 紧随（适合半包粘包场景）
 * - rewind()：  position=0（重新读）
 * - mark()/reset()：标记/回退
 *
 * 对比 BIO：
 * - BIO：ServerSocket.accept() 阻塞 → 每个连接 new Thread → InputStream.read() 阻塞
 * - NIO：ServerSocketChannel 注册 OP_ACCEPT → 单线程 select() → 多路复用处理
 * - AIO：AsynchronousServerSocketChannel → accept 回调 → read 回调
 */
public class NIODemo {

    private static final int PORT = 9000;
    private static final int BUFFER_SIZE = 256;

    public static void main(String[] args) throws Exception {
        bioModelExplanation();
        nioModelExplanation();
        aioModelExplanation();
        nioServerClientDemo();
    }

    static void bioModelExplanation() {
        System.out.println("=== BIO 模型 ===");
        System.out.println("模型：一个连接 = 一个线程");
        System.out.println();
        System.out.println("ServerSocket.accept() → 阻塞等待连接");
        System.out.println("  ↓ 新连接到达");
        System.out.println("new Thread(connectionHandler) → 一个连接一个线程");
        System.out.println("  ↓");
        System.out.println("InputStream.read() → 阻塞等待数据");
        System.out.println();
        System.out.println("缺点：");
        System.out.println("  1. 线程数 = 连接数，C10K 问题（10,000 连接 → 10,000 线程）");
        System.out.println("  2. 大量线程上下文切换开销");
        System.out.println("  3. 大部分线程在阻塞等待 I/O，浪费资源");
        System.out.println("  4. 线程栈内存（-Xss 默认 1MB）→ 10,000 线程 = 10GB 内存消耗");
        System.out.println();
    }

    static void nioModelExplanation() {
        System.out.println("=== NIO 模型（多路复用）===");
        System.out.println("模型：一个 Selector 线程管理多个 Channel");
        System.out.println();
        System.out.println("┌────────────────────────────────────────┐");
        System.out.println("│          Selector（多路复用器）          │");
        System.out.println("│  ┌──────────┬──────────┬──────────┐    │");
        System.out.println("│  │ OP_ACCEPT│ OP_READ  │ OP_WRITE │    │");
        System.out.println("│  └──────────┴──────────┴──────────┘    │");
        System.out.println("│       ↑           ↑          ↑         │");
        System.out.println("│  ServerSocketCh  SocketCh1  SocketCh2  │");
        System.out.println("└────────────────────────────────────────┘");
        System.out.println();
        System.out.println("流程：");
        System.out.println("  1. ServerSocketChannel 注册 OP_ACCEPT 到 Selector");
        System.out.println("  2. selector.select() 阻塞等待事件");
        System.out.println("  3. 遍历 selectedKeys：");
        System.out.println("     OP_ACCEPT → 接受连接，注册 OP_READ");
        System.out.println("     OP_READ   → 读取数据到 ByteBuffer");
        System.out.println("     OP_WRITE  → 写入响应");
        System.out.println("  4. 清除已处理的 key，回到步骤 2");
        System.out.println();
        System.out.println("优点：");
        System.out.println("  1. 单线程管理成千上万个连接（C10K 可解）");
        System.out.println("  2. 非阻塞模式，CPU 只处理就绪的 Channel");
        System.out.println("  3. 线程和内存开销极小");
        System.out.println();
    }

    static void aioModelExplanation() {
        System.out.println("=== AIO 模型（异步 IO）===");
        System.out.println("模型：操作系统异步回调");
        System.out.println();
        System.out.println("AsynchronousServerSocketChannel.accept(null, handler)");
        System.out.println("  → 系统调用下发到 OS");
        System.out.println("  → 数据到达 OS 内核缓冲区");
        System.out.println("  → OS 完成读取后回调 handler.completed()");
        System.out.println("  → handler 中处理业务逻辑");
        System.out.println();
        System.out.println("AIO 在 Linux 上基于 epoll + 线程池模拟（JDK 实现）");
        System.out.println("真正原生 AIO 需要 IOCP（Windows）或 io_uring（Linux 5.1+）");
        System.out.println("Netty 不推荐 JDK AIO，因为 Linux epoll 更成熟高效");
        System.out.println();
    }

    /**
     * NIO 服务器 + 客户端完整演示
     * 服务器：单线程 Selector 多路复用
     * 客户端：SocketChannel 非阻塞连接
     */
    static void nioServerClientDemo() throws Exception {
        System.out.println("=== NIO 服务器 + 客户端完整演示 ===");
        System.out.println();

        NioServer server = new NioServer(PORT);
        Thread serverThread = new Thread(server, "nio-server");
        serverThread.setDaemon(true);
        serverThread.start();

        TimeUnit.MILLISECONDS.sleep(300);

        NioClient client = new NioClient(PORT);
        client.sendAndReceive();

        TimeUnit.MILLISECONDS.sleep(500);
        server.stop();
        System.out.println();
    }

    static class NioServer implements Runnable {

        private final int port;
        private volatile boolean running = true;
        private Selector selector;

        NioServer(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            try {
                selector = Selector.open();

                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress(port));
                serverChannel.configureBlocking(false);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);

                System.out.println("[NIO 服务器] 启动，监听端口 " + port);

                while (running) {
                    int readyChannels = selector.select(1000);
                    if (readyChannels == 0) {
                        continue;
                    }

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            } finally {
                closeSelector();
            }
        }

        private void handleAccept(SelectionKey key) throws IOException {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ,
                    ByteBuffer.allocate(BUFFER_SIZE));
            System.out.println("[NIO 服务器] 接受新连接: "
                    + clientChannel.getRemoteAddress());
        }

        private void handleRead(SelectionKey key) throws IOException {
            SocketChannel clientChannel = (SocketChannel) key.channel();
            ByteBuffer buffer = (ByteBuffer) key.attachment();

            buffer.clear();
            int bytesRead = clientChannel.read(buffer);

            if (bytesRead == -1) {
                System.out.println("[NIO 服务器] 客户端断开: "
                        + clientChannel.getRemoteAddress());
                clientChannel.close();
                return;
            }

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String received = new String(data, StandardCharsets.UTF_8);
            System.out.println("[NIO 服务器] 收到: " + received);

            String response = "ECHO: " + received;
            ByteBuffer writeBuffer = ByteBuffer.wrap(
                    response.getBytes(StandardCharsets.UTF_8));
            while (writeBuffer.hasRemaining()) {
                clientChannel.write(writeBuffer);
            }

            key.interestOps(SelectionKey.OP_READ);
        }

        private void closeSelector() {
            try {
                if (selector != null) {
                    for (SelectionKey key : selector.keys()) {
                        key.channel().close();
                    }
                    selector.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        void stop() {
            running = false;
            if (selector != null) {
                selector.wakeup();
            }
        }
    }

    static class NioClient {

        private final int port;

        NioClient(int port) {
            this.port = port;
        }

        void sendAndReceive() throws Exception {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(true);
            socketChannel.connect(new InetSocketAddress("localhost", port));

            String[] messages = {"Hello NIO", "多路复用测试", "第三条消息"};
            ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);

            for (String message : messages) {
                ByteBuffer writeBuffer = ByteBuffer.wrap(
                        message.getBytes(StandardCharsets.UTF_8));
                while (writeBuffer.hasRemaining()) {
                    socketChannel.write(writeBuffer);
                }
                System.out.println("[NIO 客户端] 发送: " + message);

                readBuffer.clear();
                int bytesRead = socketChannel.read(readBuffer);
                if (bytesRead > 0) {
                    readBuffer.flip();
                    byte[] data = new byte[readBuffer.remaining()];
                    readBuffer.get(data);
                    System.out.println("[NIO 客户端] 收到响应: "
                            + new String(data, StandardCharsets.UTF_8));
                }

                TimeUnit.MILLISECONDS.sleep(100);
            }

            socketChannel.close();
            System.out.println("[NIO 客户端] 连接关闭");
        }
    }
}