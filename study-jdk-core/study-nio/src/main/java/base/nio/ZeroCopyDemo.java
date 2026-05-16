package base.nio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 零拷贝详解 — 传统 IO vs mmap vs sendfile
 *
 * 核心知识点：
 *
 * 【传统 IO 传输路径】（4 次拷贝 + 2 次上下文切换）
 * 1. DMA Copy：磁盘 → OS 内核缓冲区（read buffer）
 * 2. CPU Copy：OS 内核缓冲区 → 用户空间（堆内存）
 * 3. CPU Copy：用户空间 → socket 内核缓冲区
 * 4. DMA Copy：socket 内核缓冲区 → 网卡
 *
 * 【mmap + write】（3 次拷贝 + 2 次上下文切换）
 * 1. DMA Copy：磁盘 → OS 内核缓冲区
 * 2. CPU Copy：内核缓冲区 → socket 缓冲区（跳过用户空间！）
 * 3. DMA Copy：socket 缓冲区 → 网卡
 *
 * 【sendfile（Linux 2.4+）】（2 次拷贝 + 2 次上下文切换）
 * 1. DMA Copy：磁盘 → OS 内核缓冲区（read buffer）
 * 2. DMA Copy：内核缓冲区 → 网卡（scatter-gather，零 CPU 拷贝！）
 *
 * Java 实现：
 * - mmap：FileChannel.map(MapMode.READ_ONLY, 0, length) → MappedByteBuffer
 * - sendfile：FileChannel.transferTo(position, count, targetChannel)
 * - NIO 中 transferTo 在 Linux 上底层调用 sendfile64
 */
public class ZeroCopyDemo {

    private static final int FILE_SIZE_MB = 10;
    private static final int FILE_SIZE = FILE_SIZE_MB * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        zeroCopyTheory();
        traditionalCopyDemo();
        mmapCopyDemo();
        transferToCopyDemo();
        performanceComparison();
    }

    static void zeroCopyTheory() {
        System.out.println("=== 零拷贝理论 ===");
        System.out.println();
        System.out.println("零拷贝 ≠ 零次拷贝，而是「减少不必要的 CPU 拷贝」");
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────────────┐");
        System.out.println("│ 方案              │ CPU 拷贝 │ DMA 拷贝 │ 上下文切换 │ 总拷贝 │");
        System.out.println("├──────────────────────────────────────────────────────────┤");
        System.out.println("│ 传统 IO            │ 2        │ 2       │ 2         │ 4      │");
        System.out.println("│ mmap + write      │ 1        │ 2       │ 2         │ 3      │");
        System.out.println("│ sendfile(Linux2.4+)│ 0        │ 2       │ 2         │ 2      │");
        System.out.println("└──────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("传统 IO 数据流向：");
        System.out.println("  磁盘 →【DMA】→ 内核缓冲区 →【CPU】→ 用户缓冲区");
        System.out.println("  →【CPU】→ socket 缓冲区 →【DMA】→ 网卡/目标");
        System.out.println();
        System.out.println("sendfile 数据流向（Linux 2.4+ scatter-gather）：");
        System.out.println("  磁盘 →【DMA】→ 内核缓冲区 →【仅传递 fd/offset】→ socket 缓冲区 →【DMA】→ 网卡");
        System.out.println();
        System.out.println("Java 零拷贝 API：");
        System.out.println("  mmap：   FileChannel.map() → MappedByteBuffer");
        System.out.println("  sendfile：FileChannel.transferTo()");
        System.out.println("  JDK 9+：FileChannel.transferTo() 内部调用 sendfile64");
        System.out.println();
    }

    /**
     * 传统 IO 拷贝 — FileInputStream → FileOutputStream
     * 发生 4 次拷贝：用户态和内核态之间来回搬运数据
     */
    static void traditionalCopyDemo() throws IOException {
        System.out.println("=== 传统 IO 拷贝演示 ===");

        Path sourcePath = Files.createTempFile("zero_copy_src_", ".tmp");
        Path targetPath = Files.createTempFile("zero_copy_trad_", ".tmp");
        createTestFile(sourcePath);

        long startTime = System.nanoTime();

        try (FileInputStream fis = new FileInputStream(sourcePath.toFile());
             FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            System.out.println("  传统 IO 拷贝完成: " + totalBytes + " bytes, 耗时 " + elapsed + " ms");
            System.out.println("  数据路径: 磁盘 → 内核缓冲区 → JVM 堆内存 → 内核缓冲区 → 磁盘");
            System.out.println("  拷贝次数: 4（2 CPU + 2 DMA）");
        } finally {
            deleteTempFileSafely(sourcePath);
            deleteTempFileSafely(targetPath);
        }
        System.out.println();
    }

    /**
     * mmap 拷贝 — FileChannel.map() 减少一次 CPU 拷贝
     * 内核缓冲区直接映射到用户空间，无需 read() 系统调用
     */
    static void mmapCopyDemo() throws IOException {
        System.out.println("=== mmap 拷贝演示 ===");

        Path sourcePath = Files.createTempFile("zero_copy_mmap_src_", ".tmp");
        Path targetPath = Files.createTempFile("zero_copy_mmap_tgt_", ".tmp");
        createTestFile(sourcePath);

        long startTime = System.nanoTime();

        try (RandomAccessFile sourceFile = new RandomAccessFile(sourcePath.toFile(), "r");
             RandomAccessFile targetFile = new RandomAccessFile(targetPath.toFile(), "rw");
             FileChannel sourceChannel = sourceFile.getChannel();
             FileChannel targetChannel = targetFile.getChannel()) {

            long fileSize = sourceChannel.size();
            long position = 0;
            long chunkSize = 128 * 1024 * 1024;

            while (position < fileSize) {
                long remaining = fileSize - position;
                long mapSize = Math.min(chunkSize, remaining);

                MappedByteBuffer mappedBuffer = sourceChannel.map(
                        FileChannel.MapMode.READ_ONLY, position, mapSize);
                targetChannel.write(mappedBuffer);
                position += mapSize;
            }

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            System.out.println("  mmap 拷贝完成: " + fileSize + " bytes, 耗时 " + elapsed + " ms");
            System.out.println("  数据路径: 磁盘 → 内核缓冲区（用户空间直接映射）→ socket 缓冲区 → 磁盘");
            System.out.println("  拷贝次数: 3（1 CPU + 2 DMA）");
            System.out.println("  注意：mmap 适合小文件随机访问，大文件建议用 transferTo");
        } finally {
            deleteTempFileSafely(sourcePath);
            deleteTempFileSafely(targetPath);
        }
        System.out.println();
    }

    /**
     * transferTo 拷贝 — FileChannel.transferTo() 底层 sendfile 零 CPU 拷贝
     * 数据从文件通道直接传输到目标通道，不经过用户空间
     */
    static void transferToCopyDemo() throws IOException {
        System.out.println("=== transferTo（sendfile）零拷贝演示 ===");

        Path sourcePath = Files.createTempFile("zero_copy_transfer_src_", ".tmp");
        Path targetPath = Files.createTempFile("zero_copy_transfer_tgt_", ".tmp");
        createTestFile(sourcePath);

        long startTime = System.nanoTime();

        try (FileChannel sourceChannel = FileChannel.open(sourcePath, StandardOpenOption.READ);
             FileChannel targetChannel = FileChannel.open(targetPath,
                     StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

            long fileSize = sourceChannel.size();
            long position = 0;
            long transferred;

            while (position < fileSize) {
                transferred = sourceChannel.transferTo(
                        position, fileSize - position, targetChannel);
                if (transferred == 0) {
                    break;
                }
                position += transferred;
            }

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            System.out.println("  transferTo 拷贝完成: " + position + " bytes, 耗时 " + elapsed + " ms");
            System.out.println("  数据路径: 磁盘 → 内核缓冲区 → 网卡（scatter-gather，不经过 CPU/用户空间）");
            System.out.println("  拷贝次数: 2（0 CPU + 2 DMA）");
            System.out.println("  底层调用: sendfile64（Linux 2.6.33+ 支持文件到文件）");
        } finally {
            deleteTempFileSafely(sourcePath);
            deleteTempFileSafely(targetPath);
        }
        System.out.println();
    }

    /**
     * 性能对比总结
     */
    static void performanceComparison() {
        System.out.println("=== 性能对比与使用场景 ===");
        System.out.println();
        System.out.println("┌──────────────────┬──────────┬──────────┬──────────────────────┐");
        System.out.println("│ 方案              │ 拷贝次数   │ 上下文切换 │ 适用场景              │");
        System.out.println("├──────────────────┼──────────┼──────────┼──────────────────────┤");
        System.out.println("│ 传统 read/write  │ 4        │ 2        │ 兼容性最好            │");
        System.out.println("│ mmap             │ 3        │ 2        │ 小文件随机读写        │");
        System.out.println("│ transferTo       │ 2        │ 2        │ 文件传输、大文件拷贝  │");
        System.out.println("└──────────────────┴──────────┴──────────┴──────────────────────┘");
        System.out.println();
        System.out.println("transferTo 典型应用场景：");
        System.out.println("  - 文件服务器：磁盘文件 → SocketChannel（Nginx sendfile on;）");
        System.out.println("  - Kafka：日志段文件 → 网卡（mmap + sendfile）");
        System.out.println("  - Netty：DefaultFileRegion.transferTo()");
        System.out.println();
        System.out.println("transferTo 限制（Linux 2.6.33 之前）：");
        System.out.println("  - 只能 FileChannel → SocketChannel，不能 FileChannel → FileChannel");
        System.out.println("  - 一次最多传输 Integer.MAX_VALUE 字节（2GB），大文件需分次调用");
        System.out.println("  - Windows 上 transferTo 最大 8MB");
        System.out.println();
    }

    private static void createTestFile(Path path) throws IOException {
        byte[] data = new byte[FILE_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        Files.write(path, data);
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