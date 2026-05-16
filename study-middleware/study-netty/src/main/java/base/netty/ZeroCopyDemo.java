package base.netty;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Netty 零拷贝：CompositeByteBuf + Slice + FileRegion + Direct Memory。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>传统 IO 拷贝（4 次）</b>：磁盘→内核缓冲区→用户缓冲区→Socket 缓冲区→网卡</li>
 *   <li><b>sendfile 零拷贝（2 次）</b>：磁盘→内核缓冲区→（DMA gather）→网卡，不经过用户态</li>
 *   <li><b>mmap + write</b>：磁盘→内核缓冲区（用户态映射），减少 1 次拷贝，Kafka 使用此方案</li>
 *   <li><b>DirectBuffer</b>：堆外内存，避免 GC，适合长期存活的大缓冲区</li>
 *   <li><b>CompositeByteBuf</b>：合并多个 ByteBuf 不产生额外拷贝</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()，观察各方案对比输出。
 *
 * @author study-tuling
 */
public class ZeroCopyDemo {

    private static final Path TEST_FILE = Path.of(System.getProperty("java.io.tmpdir"), "zerocopy-demo.dat");
    private static final int FILE_SIZE = 10 * 1024 * 1024; // 10MB

    public static void main(String[] args) throws Exception {
        System.out.println("=== Netty 零拷贝机制演示 ===\n");

        /* 准备测试文件 */
        prepareTestFile();

        demonstrateTraditionalCopy();
        demonstrateSendfileTransferTo();
        demonstrateMmapKafkaStyle();
        demonstrateDirectBufferVsHeapBuffer();
        demonstrateCompositeBufferConcept();

        /* 清理 */
        Files.deleteIfExists(TEST_FILE);
        System.out.println("\n=== 演示完成 ===");
    }

    /* ==================== 1. 传统 IO：4 次拷贝 + 2 次上下文切换 ==================== */

    /**
     * 传统 IO 数据路径：
     * <pre>
     *   磁盘 ──[DMA copy]──> 内核缓冲区 ──[CPU copy]──> 用户缓冲区(JVM Heap)
     *   用户缓冲区 ──[CPU copy]──> Socket 缓冲区 ──[DMA copy]──> 网卡
     *
     *   共 4 次拷贝（2 次 DMA + 2 次 CPU），2 次用户态↔内核态切换
     * </pre>
     */
    private static void demonstrateTraditionalCopy() throws IOException {
        System.out.println("--- 1. 传统 IO（HeapBuffer 中转）---");

        long start = System.nanoTime();
        try (FileInputStream fis = new FileInputStream(TEST_FILE.toFile());
             FileChannel fileChannel = fis.getChannel()) {

            ByteBuffer heapBuffer = ByteBuffer.allocate(8192);
            int totalRead = 0;
            while (fileChannel.read(heapBuffer) != -1) {
                totalRead += heapBuffer.position();
                /* 模拟 Socket 写出（此处省略 SocketChannel，仅测读取） */
                heapBuffer.clear();
            }
            System.out.printf("  读取 %d 字节，耗时 %.2f ms (HeapBuffer)%n",
                    totalRead, (System.nanoTime() - start) / 1_000_000.0);
        }
        System.out.println("  拷贝次数：4 次（磁盘→内核→用户→Socket→网卡）");
        System.out.println("  上下文切换：2 次（用户态↔内核态）×2\n");
    }

    /* ==================== 2. sendfile 零拷贝：transferTo ==================== */

    /**
     * sendfile (transferTo) 数据路径：
     * <pre>
     *   磁盘 ──[DMA copy]──> 内核缓冲区 ──[DMA gather copy]──> 网卡
     *
     *   仅 2 次 DMA 拷贝，0 次 CPU 拷贝，0 次上下文切换（sendfile 系统调用内完成）
     *
     *   Java NIO: FileChannel.transferTo(position, count, targetChannel)
     * </pre>
     */
    private static void demonstrateSendfileTransferTo() throws IOException, InterruptedException {
        System.out.println("--- 2. sendfile 零拷贝（FileChannel.transferTo）---");

        /* 启动一个简单的接收端，用于演示 transferTo */
        Thread server = new Thread(() -> {
            try {
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress(9090));
                SocketChannel clientChannel = serverChannel.accept();

                /* 接收端只读取并丢弃，测试 transferTo 性能 */
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                long total = 0;
                while (clientChannel.read(buffer) != -1) {
                    total += buffer.position();
                    buffer.clear();
                }
                System.out.printf("  [接收端] 共接收 %d 字节%n", total);
                clientChannel.close();
                serverChannel.close();
            } catch (IOException e) {
                System.err.println("  [接收端] " + e.getMessage());
            }
        });
        server.setDaemon(true);
        server.start();

        Thread.sleep(300); // 等待服务器启动

        long start = System.nanoTime();
        try (SocketChannel socketChannel = SocketChannel.open(new InetSocketAddress("localhost", 9090));
             FileChannel fileChannel = FileChannel.open(TEST_FILE)) {

            long position = 0;
            long count = fileChannel.size();
            long totalTransferred = 0;

            /* transferTo 多次调用直到传输完成（大文件可能需多次） */
            while (position < count) {
                long transferred = fileChannel.transferTo(position, count - position, socketChannel);
                if (transferred <= 0) {
                    break;
                }
                position += transferred;
                totalTransferred += transferred;
            }
            socketChannel.shutdownOutput();

            System.out.printf("  传输 %d 字节，耗时 %.2f ms (transferTo)%n",
                    totalTransferred, (System.nanoTime() - start) / 1_000_000.0);
        }
        System.out.println("  拷贝次数：2 次（磁盘→内核→网卡），0 次 CPU 拷贝");
        System.out.println("  上下文切换：0 次（sendfile 系统调用一次性完成）\n");

        server.join(2000);
    }

    /* ==================== 3. mmap + write（Kafka 方案） ==================== */

    /**
     * mmap 数据路径（Kafka 使用的方案）：
     * <pre>
     *   磁盘 ──[DMA copy]──> 内核缓冲区（用户态可直接访问 PageCache）
     *   内核缓冲区 ──[CPU copy]──> Socket 缓冲区 ──[DMA copy]──> 网卡
     *
     *   3 次拷贝（1 次 CPU），1 次上下文切换。
     *   相比传统 IO 减少 1 次 CPU 拷贝。
     *
     *   优势：用户态可直接操作 PageCache，适合多次读取同一文件的场景。
     *   限制：文件大小受限于虚拟内存地址空间。
     * </pre>
     */
    private static void demonstrateMmapKafkaStyle() throws IOException {
        System.out.println("--- 3. mmap 内存映射（Kafka 写磁盘方案）---");

        long start = System.nanoTime();
        try (RandomAccessFile raf = new RandomAccessFile(TEST_FILE.toFile(), "r");
             FileChannel channel = raf.getChannel()) {

            /* MappedByteBuffer 直接映射内核 PageCache */
            java.nio.MappedByteBuffer mappedBuffer = channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, channel.size());

            /* 模拟 Kafka 顺序读取 PageCache 并写入 Socket */
            byte[] batch = new byte[8192];
            long totalRead = 0;
            while (mappedBuffer.hasRemaining()) {
                int len = Math.min(batch.length, mappedBuffer.remaining());
                mappedBuffer.get(batch, 0, len);
                totalRead += len;
                /* 实际 Kafka: socketChannel.write(...) 这里省略 */
            }
            System.out.printf("  读取 %d 字节，耗时 %.2f ms (mmap)%n",
                    totalRead, (System.nanoTime() - start) / 1_000_000.0);
        }
        System.out.println("  拷贝次数：3 次（磁盘→内核→Socket→网卡），1 次 CPU 拷贝");
        System.out.println("  适用场景：Kafka 顺序读写 PageCache，消费者直接读取内核缓冲区\n");
    }

    /* ==================== 4. DirectBuffer vs HeapBuffer ==================== */

    /**
     * DirectBuffer 与 HeapBuffer 对比：
     * <ul>
     *   <li><b>HeapBuffer</b>：JVM 堆内分配，受 GC 管理，IO 时需先拷贝到堆外临时缓冲区</li>
     *   <li><b>DirectBuffer</b>：堆外直接内存分配，不受 GC 管理，IO 时可直接传给内核</li>
     *   <li>Netty 默认使用 DirectBuffer 作为数据容器（PooledByteBufAllocator）</li>
     *   <li>DirectBuffer 创建/销毁开销大，需要池化复用</li>
     * </ul>
     */
    private static void demonstrateDirectBufferVsHeapBuffer() {
        System.out.println("--- 4. DirectBuffer vs HeapBuffer 对比 ---");

        int iterations = 100_000;
        int bufferSize = 4096;

        /* HeapBuffer 读写测试 */
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer heapBuffer = ByteBuffer.allocate(bufferSize);
            for (int j = 0; j < bufferSize; j++) {
                heapBuffer.put((byte) (j % 127));
            }
            heapBuffer.flip();
            while (heapBuffer.hasRemaining()) {
                heapBuffer.get();
            }
        }
        double heapTime = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("  HeapBuffer: %d 次读写 → %.2f ms%n", iterations, heapTime);

        /* DirectBuffer 读写测试 */
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(bufferSize);
            for (int j = 0; j < bufferSize; j++) {
                directBuffer.put((byte) (j % 127));
            }
            directBuffer.flip();
            while (directBuffer.hasRemaining()) {
                directBuffer.get();
            }
        }
        double directTime = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("  DirectBuffer: %d 次读写 → %.2f ms%n", iterations, directTime);
        System.out.printf("  性能差异: %.1f%%%n", (directTime - heapTime) / heapTime * 100);
        System.out.println("  DirectBuffer 优势：IO 时避免堆内→堆外拷贝");
        System.out.println("  DirectBuffer 劣势：分配/回收开销大，需池化（Netty PooledByteBufAllocator）\n");
    }

    /* ==================== 5. CompositeByteBuf 概念 ==================== */

    /**
     * CompositeByteBuf 零拷贝合并：
     * <pre>
     *   传统方式：ByteBuf buf1 + ByteBuf buf2 → new ByteBuf(buf1.len + buf2.len) + 两次 copy
     *   Composite 方式：CompositeByteBuf = [buf1 → buf2]（只记录引用，不拷贝数据）
     *
     *   Netty 中常用于 HTTP 响应（header + body 合并）+ SSL/TLS 加密
     * </pre>
     */
    private static void demonstrateCompositeBufferConcept() {
        System.out.println("--- 5. CompositeByteBuf 零拷贝合并（概念演示）---");

        ByteBuffer header = ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n".getBytes());
        ByteBuffer body = ByteBuffer.wrap("Hello World".getBytes());

        /*
         * 传统方式：需要拷贝合并
         * ByteBuffer merged = ByteBuffer.allocate(header.remaining() + body.remaining());
         * merged.put(header).put(body); // 两次内存拷贝
         */

        /* Netty CompositeByteBuf 方式：零拷贝组合（此处用数组模拟） */
        ByteBuffer[] composite = {header, body};

        System.out.println("  CompositeByteBuf 组合结构（零拷贝）：");
        int totalBytes = 0;
        for (int i = 0; i < composite.length; i++) {
            ByteBuffer buf = composite[i];
            System.out.printf("    [%d] position=%d, limit=%d, capacity=%d → %d 字节%n",
                    i, buf.position(), buf.limit(), buf.capacity(), buf.remaining());
            totalBytes += buf.remaining();
        }
        System.out.printf("  总计 %d 字节，0 次内存拷贝%n", totalBytes);
        System.out.println("  应用场景：HTTP Response = Header + Body 零拷贝组合\n");
    }

    /** 准备 10MB 测试文件 */
    private static void prepareTestFile() throws IOException {
        byte[] data = new byte[FILE_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 127 + 1);
        }
        Files.write(TEST_FILE, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.printf("[准备] 测试文件 %s，大小 %d MB%n%n", TEST_FILE, FILE_SIZE / (1024 * 1024));
    }
}