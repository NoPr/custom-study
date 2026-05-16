package base.nio.interview;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 面试题：零拷贝原理与实现
 *
 * 高频考点：
 * 1. 传统 IO 的 4 次拷贝 + 2 次上下文切换
 * 2. mmap + write 减少 1 次 CPU 拷贝
 * 3. sendfile（Linux 2.4+）实现 2 次 DMA 拷贝 + 0 次 CPU 拷贝
 * 4. Java 中的 FileChannel.transferTo() / map()
 * 5. Kafka 和 Netty 如何使用零拷贝
 */
public class Q02_ZeroCopy {

    public static void main(String[] args) throws Exception {
        traditionalIOPath();
        zeroCopyDefinition();
        mmapVsSendfile();
        javaApiUsage();
        kafkaNettyApplication();
        zeroCopyLimitations();
    }

    static void traditionalIOPath() {
        System.out.println("=== 传统 IO 数据传输路径（4 次拷贝 + 2 次切换）===");
        System.out.println();
        System.out.println("步骤                          | 模式     | 操作");
        System.out.println("─────────────────────────────────────────────────");
        System.out.println("1. read() 系统调用            | 用户→内核 | 上下文切换 1");
        System.out.println("2. DMA 拷贝（磁盘→内核缓冲区）  | DMA      | 拷贝 1");
        System.out.println("3. CPU 拷贝（内核→用户空间）    | CPU      | 拷贝 2");
        System.out.println("4. read() 返回                | 内核→用户 | 上下文切换 2");
        System.out.println("5. write() 系统调用           | 用户→内核 | 上下文切换 3");
        System.out.println("6. CPU 拷贝（用户→socket 缓冲区）| CPU      | 拷贝 3");
        System.out.println("7. DMA 拷贝（socket→网卡/磁盘） | DMA      | 拷贝 4");
        System.out.println("8. write() 返回               | 内核→用户 | 上下文切换 4");
        System.out.println();
        System.out.println("总计：4 次拷贝（2 CPU + 2 DMA）+ 4 次上下文切换（实际优化为 2 次）");
        System.out.println("（Linux 2.3+ 已验证 read/write 组合可减少到 2 次上下文切换）");
        System.out.println();
    }

    static void zeroCopyDefinition() {
        System.out.println("=== 零拷贝定义 ===");
        System.out.println();
        System.out.println("零拷贝 ≠ 零次拷贝");
        System.out.println("零拷贝 = 减少不必要的 CPU 拷贝，让 CPU 从数据搬运中解放出来");
        System.out.println();
        System.out.println("目标：数据从磁盘到网卡的过程中，不经过用户态 CPU 拷贝");
        System.out.println();
    }

    static void mmapVsSendfile() {
        System.out.println("=== mmap vs sendfile 对比 ===");
        System.out.println();
        System.out.println("┌──────────────────┬─────────────────┬─────────────────┐");
        System.out.println("│ 维度              │ mmap + write     │ sendfile          │");
        System.out.println("├──────────────────┼─────────────────┼─────────────────┤");
        System.out.println("│ CPU 拷贝次数     │ 1               │ 0                │");
        System.out.println("│ DMA 拷贝次数     │ 2               │ 2                │");
        System.out.println("│ 上下文切换次数   │ 4               │ 2                │");
        System.out.println("│ 用户空间可见     │ 是（内存映射）   │ 否                │");
        System.out.println("│ 适合操作         │ 随机读写         │ 顺序传输          │");
        System.out.println("│ 内存占用         │ 映射整个文件     │ 无需额外内存      │");
        System.out.println("│ 系统调用         │ mmap + write     │ sendfile 一次     │");
        System.out.println("└──────────────────┴─────────────────┴─────────────────┘");
        System.out.println();
        System.out.println("mmap 原理：");
        System.out.println("  通过内存映射，将内核缓冲区直接映射到用户空间虚拟地址");
        System.out.println("  用户空间操作 mmap 区域 = 直接操作内核缓冲区（page cache）");
        System.out.println("  省去一次 read() 的 CPU 拷贝（内核→用户）");
        System.out.println();
        System.out.println("sendfile（Linux 2.4+ scatter-gather）原理：");
        System.out.println("  sendfile(out_fd, in_fd, offset, count)");
        System.out.println("  DMA 将磁盘数据读到内核缓冲区");
        System.out.println("  仅将缓冲区描述符（fd + offset + length）传给 socket 缓冲区");
        System.out.println("  DMA gather 直接从内核缓冲区读取数据发送到网卡");
        System.out.println("  全程数据不经过用户空间、不经过 CPU！");
        System.out.println();
    }

    /**
     * Java 零拷贝 API 使用
     */
    static void javaApiUsage() throws Exception {
        System.out.println("=== Java 零拷贝 API ===");
        System.out.println();

        Path sourcePath = Files.createTempFile("zc_src_", ".tmp");
        Path targetPath1 = Files.createTempFile("zc_mmap_", ".tmp");
        Path targetPath2 = Files.createTempFile("zc_transfer_", ".tmp");

        byte[] data = "Zero Copy Test Data".getBytes();
        Files.write(sourcePath, data);

        System.out.println("1. FileChannel.map() — mmap 方式：");
        try (RandomAccessFile raf = new RandomAccessFile(sourcePath.toFile(), "r");
             RandomAccessFile target = new RandomAccessFile(targetPath1.toFile(), "rw");
             FileChannel sourceChannel = raf.getChannel();
             FileChannel targetChannel = target.getChannel()) {

            MappedByteBuffer mappedBuffer = sourceChannel.map(
                    FileChannel.MapMode.READ_ONLY, 0, sourceChannel.size());
            targetChannel.write(mappedBuffer);
            System.out.println("   数据从 mmap 区域直接写入目标 Channel（跳过堆内存）");
            System.out.println("   MappedByteBuffer 是堆外内存（Direct Memory）");
        }

        System.out.println();
        System.out.println("2. FileChannel.transferTo() — sendfile 方式：");
        try (FileChannel sourceChannel = FileChannel.open(sourcePath, StandardOpenOption.READ);
             FileChannel targetChannel = FileChannel.open(targetPath2,
                     StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

            long fileSize = sourceChannel.size();
            long transferred = sourceChannel.transferTo(0, fileSize, targetChannel);
            System.out.println("   transferTo 传输了 " + transferred + " bytes");
            System.out.println("   底层调用 sendfile64（Linux），数据不经过用户空间");
        }

        System.out.println();
        System.out.println("3. 对比传统 IO read/write：");
        try (FileInputStream fis = new FileInputStream(sourcePath.toFile());
             FileOutputStream fos = new FileOutputStream(sourcePath.toFile() + ".copy")) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            System.out.println("   传统方式：数据 磁盘 → 内核 → JVM 堆 → 内核 → 磁盘（多 2 次 CPU 拷贝）");
        }

        deleteTempFileSafely(sourcePath);
        deleteTempFileSafely(targetPath1);
        deleteTempFileSafely(targetPath2);
        System.out.println();
    }

    static void kafkaNettyApplication() {
        System.out.println("=== Kafka 和 Netty 中的零拷贝 ===");
        System.out.println();
        System.out.println("Kafka：");
        System.out.println("  - 日志段文件 → mmap 映射（顺序写）");
        System.out.println("  - 消费消息 → FileChannel.transferTo() → SocketChannel（零拷贝发送）");
        System.out.println("  - 配合 Linux 的 sendfile，CPU 几乎不参与数据传输");
        System.out.println("  - 结果：Kafka 能达到百万 QPS，CPU 占用极低");
        System.out.println();
        System.out.println("Netty：");
        System.out.println("  - DefaultFileRegion.transferTo() → 封装 FileChannel.transferTo()");
        System.out.println("  - CompositeByteBuf → 零拷贝合并多个 ByteBuf（只传引用不复制数据）");
        System.out.println("  - Unpooled.wrappedBuffer() → 零拷贝包装字节数组");
        System.out.println("  - ByteBuf.slice() → 零拷贝切分（共享同一块内存）");
        System.out.println();
        System.out.println("Netty 中「零拷贝」的广义含义：");
        System.out.println("  ≠ OS 级 sendfile");
        System.out.println("  = JVM 堆内不复制数据，用指针/引用传递数据");
        System.out.println();
    }

    static void zeroCopyLimitations() {
        System.out.println("=== 零拷贝的局限性 ===");
        System.out.println();
        System.out.println("1. transferTo 限制：");
        System.out.println("   Linux 2.6.33 之前只能 FileChannel → SocketChannel");
        System.out.println("   单次最多传输 Integer.MAX_VALUE（2GB），大文件需循环调用");
        System.out.println("   Windows 单次最大 8MB");
        System.out.println();
        System.out.println("2. mmap 限制：");
        System.out.println("   映射大文件需要大块虚拟地址空间（32 位 JVM 受限）");
        System.out.println("   MappedByteBuffer 不会自动释放（需显式 Cleaner.clean()）");
        System.out.println("   频繁 mmap/munmap 开销大");
        System.out.println();
        System.out.println("3. 适用场景限制：");
        System.out.println("   数据需经过用户空间修改 → 零拷贝不适用");
        System.out.println("   如加密、压缩、转码等场景仍需 CPU 参与");
        System.out.println();
    }

    private static void deleteTempFileSafely(Path path) {
        try {
            System.gc();
            Thread.sleep(50);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            path.toFile().deleteOnExit();
        }
    }
}