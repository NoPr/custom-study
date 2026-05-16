package base.netty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 粘包半包解决方案：LineBased / DelimiterBased / FixedLength / LengthFieldBased。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>粘包</b>：多条消息粘在一起到达，接收方一次 read 读到多条</li>
 *   <li><b>半包</b>：一条消息被拆分到多次 read 中，接收方一次读不完整</li>
 *   <li><b>原因</b>：Nagle 算法（小包合并）+ MSS 分段 + 滑动窗口 + 接收缓冲区大小</li>
 *   <li><b>LineBasedFrameDecoder</b>：按换行符 \n 或 \r\n 拆分帧</li>
 *   <li><b>LengthFieldBasedFrameDecoder</b>：自定义长度字段，最通用方案</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()，观察各种解码器对粘包半包数据的处理效果。
 *
 * @author study-tuling
 */
public class StickyPacketDemo {

    public static void main(String[] args) {
        System.out.println("=== Netty 粘包半包解决方案演示 ===\n");

        /* ==================== 粘包半包原因分析 ==================== */
        System.out.println("--- 0. TCP 粘包半包原因分析 ---");
        demonstrateStickyCause();

        /* ==================== 各种解码器演示 ==================== */
        demonstrateLineBasedDecoder();
        demonstrateFixedLengthDecoder();
        demonstrateDelimiterBasedDecoder();
        demonstrateLengthFieldBasedDecoder();
        demonstrateCustomProtocolCodec();

        System.out.println("=== 演示完成 ===");
    }

    /* ==================== 0. 粘包半包原因分析 ==================== */

    /**
     * TCP 粘包半包根本原因：TCP 是面向字节流的协议，消息无边界。
     *
     * <pre>
     *   发送方（应用层）        TCP 传输层（Nagle/MSS）        接收方（应用层）
     *   send("ABC")    ──┐
     *   send("DEF")    ──┤──>  [A,B,C,D,E,F]   ──>  recv() = "ABCDEF"  ← 粘包
     *   send("GHI")    ──┘
     *
     *   send("HELLO_WORLD")  ──>  MSS=8B  ──>  [HELLO_WO] [RLD] ← 半包
     * </pre>
     */
    private static void demonstrateStickyCause() {
        String[][] scenarios = {
                {"粘包", "发送 msg1='HELLO', msg2='WORLD'",
                        "TCP 合并为 'HELLOWORLD'",
                        "-> 接收方一次 read 读到 2 条消息"},
                {"半包", "发送 msg1='HELLOWORLDPYTHON'",
                        "TCP 分片为 'HELLOWO' + 'RLDPYTHON'",
                        "-> 接收方需要 2 次 read 才能拼完 1 条消息"},
        };

        for (String[] scenario : scenarios) {
            System.out.printf("  [%s] %s\n", scenario[0], scenario[1]);
            System.out.printf("       %s\n", scenario[2]);
            System.out.printf("       %s\n\n", scenario[3]);
        }

        System.out.println("  根本原因：TCP 是字节流协议，不保留应用层消息边界。");
        System.out.println("  触发条件：");
        System.out.println("    - Nagle 算法：合并小包减少网络开销（默认开启）");
        System.out.println("    - MSS 最大分段：超过 1460 字节（以太网）时 IP 层分片");
        System.out.println("    - 滑动窗口：接收缓冲区满时阻塞发送，解封后一起到达");
        System.out.println("    - 发送端批量 write，接收端一次 read 容量不足以读完\n");
    }

    /* ==================== 1. LineBasedFrameDecoder ==================== */

    /**
     * 按换行符拆分帧，适用于文本协议（如 Redis 协议）。
     *
     * <pre>
     *   ByteBuf: "hello\nworld\nfoo\n"
     *   解码结果: ["hello", "world", "foo"]
     * </pre>
     */
    private static void demonstrateLineBasedDecoder() {
        System.out.println("--- 1. LineBasedFrameDecoder（按换行符拆分）---");

        /* 模拟粘包数据：3 条消息粘在一起 */
        String stickyData = "hello\nworld\nfoo\n";
        System.out.printf("  输入粘包数据: \"%s\" (原始: %s)%n",
                stickyData.replace("\n", "\\n"),
                stickyData);

        LineBasedDecoder decoder = new LineBasedDecoder();
        List<String> frames = decoder.decode(stickyData.getBytes(StandardCharsets.UTF_8));

        System.out.println("  解码结果:");
        for (int i = 0; i < frames.size(); i++) {
            System.out.printf("    帧[%d]: \"%s\"%n", i, frames.get(i));
        }
        System.out.println();
    }

    /** 换行符解码器 */
    static class LineBasedDecoder {
        private final List<String> frames = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();

        List<String> decode(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                char ch = (char) buffer.get();
                if (ch == '\n') {
                    frames.add(pending.toString());
                    pending.setLength(0);
                } else if (ch == '\r') {
                    /* 忽略 \r */
                } else {
                    pending.append(ch);
                }
            }
            return frames;
        }
    }

    /* ==================== 2. FixedLengthFrameDecoder ==================== */

    /**
     * 按固定长度拆分帧，适用于定长协议。
     *
     * <pre>
     *   ByteBuf: "ABCDEFGHIJKL"  (frameLength=4)
     *   解码结果: ["ABCD", "EFGH", "IJKL"]
     * </pre>
     */
    private static void demonstrateFixedLengthDecoder() {
        System.out.println("--- 2. FixedLengthFrameDecoder（按固定长度拆分）---");

        String raw = "ABCDEFGHIJKLMNOPQR";
        int frameLength = 4;
        System.out.printf("  输入数据: \"%s\", 帧长度=%d%n", raw, frameLength);

        FixedLengthDecoder decoder = new FixedLengthDecoder(frameLength);
        List<String> frames = decoder.decode(raw.getBytes(StandardCharsets.UTF_8));

        System.out.println("  解码结果:");
        for (int i = 0; i < frames.size(); i++) {
            System.out.printf("    帧[%d]: \"%s\"%n", i, frames.get(i));
        }
        System.out.println("  适用场景：固定长度字段协议，如某些 IoT 传感器数据\n");
    }

    /** 固定长度解码器 */
    static class FixedLengthDecoder {
        private final int frameLength;

        FixedLengthDecoder(int frameLength) {
            this.frameLength = frameLength;
        }

        List<String> decode(byte[] data) {
            List<String> frames = new ArrayList<>();
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.remaining() >= frameLength) {
                byte[] frame = new byte[frameLength];
                buffer.get(frame);
                frames.add(new String(frame, StandardCharsets.UTF_8));
            }
            return frames;
        }
    }

    /* ==================== 3. DelimiterBasedFrameDecoder ==================== */

    /**
     * 按自定义分隔符拆分帧，支持任意字节序列作为分隔符。
     *
     * <pre>
     *   ByteBuf: "msg1###msg2###msg3###"
     *   分隔符: "###"
     *   解码结果: ["msg1", "msg2", "msg3"]
     * </pre>
     */
    private static void demonstrateDelimiterBasedDecoder() {
        System.out.println("--- 3. DelimiterBasedFrameDecoder（按自定义分隔符拆分）---");

        String delimiter = "###";
        String stickyData = "msg1###msg2###msg3###";
        System.out.printf("  输入数据: \"%s\", 分隔符=\"%s\"%n", stickyData, delimiter);

        DelimiterBasedDecoder decoder = new DelimiterBasedDecoder(delimiter.getBytes(StandardCharsets.UTF_8));
        List<String> frames = decoder.decode(stickyData.getBytes(StandardCharsets.UTF_8));

        System.out.println("  解码结果:");
        for (int i = 0; i < frames.size(); i++) {
            System.out.printf("    帧[%d]: \"%s\"%n", i, frames.get(i));
        }
        System.out.println("  适用场景：自定义文本协议\n");
    }

    /** 分隔符解码器 */
    static class DelimiterBasedDecoder {
        private final byte[] delimiter;

        DelimiterBasedDecoder(byte[] delimiter) {
            this.delimiter = delimiter;
        }

        List<String> decode(byte[] data) {
            List<String> frames = new ArrayList<>();
            ByteBuffer buffer = ByteBuffer.wrap(data);
            java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();

            while (buffer.hasRemaining()) {
                pending.write(buffer.get());
                byte[] current = pending.toByteArray();
                if (endsWith(current, delimiter)) {
                    byte[] frame = new byte[current.length - delimiter.length];
                    System.arraycopy(current, 0, frame, 0, frame.length);
                    frames.add(new String(frame, StandardCharsets.UTF_8));
                    pending.reset();
                }
            }
            return frames;
        }

        private boolean endsWith(byte[] array, byte[] suffix) {
            if (suffix.length > array.length) {
                return false;
            }
            for (int i = 1; i <= suffix.length; i++) {
                if (array[array.length - i] != suffix[suffix.length - i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /* ==================== 4. LengthFieldBasedFrameDecoder ==================== */

    /**
     * 基于长度字段的帧解码器（最通用方案）。
     *
     * <p>自定义协议格式：
     * <pre>
     *   +----------+------------+----------+
     *   | magic(2B)| length(4B) | body(NB) |
     *   +----------+------------+----------+
     *   magic = 0xCAFE（魔数校验）
     *   length = body 的字节数（大端序）
     *   body = 实际数据载荷
     * </pre>
     *
     * <p>参数说明：
     * <ul>
     *   <li><b>lengthFieldOffset</b>：长度字段在帧中的偏移量（magic 后 = 2）</li>
     *   <li><b>lengthFieldLength</b>：长度字段自身的字节数（4）</li>
     *   <li><b>lengthAdjustment</b>：长度值需要调整的量（0，表示长度值 = body 长度）</li>
     *   <li><b>initialBytesToStrip</b>：解码后要剥离的前导字节数（6 = magic + length）</li>
     * </ul>
     */
    private static void demonstrateLengthFieldBasedDecoder() {
        System.out.println("--- 4. LengthFieldBasedFrameDecoder（最通用方案）---");
        System.out.println("  协议格式: magic(2B=0xCAFE) + length(4B) + body(NB)\n");

        /* 构建两条消息 */
        byte[] frame1 = buildFrame("HelloWorld".getBytes(StandardCharsets.UTF_8));
        byte[] frame2 = buildFrame("Java".getBytes(StandardCharsets.UTF_8));

        /* 模拟粘包：两条消息合并在一起 */
        ByteBuffer stickyBuffer = ByteBuffer.allocate(frame1.length + frame2.length);
        stickyBuffer.put(frame1);
        stickyBuffer.put(frame2);
        stickyBuffer.flip();

        byte[] stickyData = new byte[stickyBuffer.remaining()];
        stickyBuffer.get(stickyData);
        System.out.printf("  粘包数据(%d bytes): ", stickyData.length);
        printHex(stickyData);
        System.out.println();

        /* 解码 */
        LengthFieldBasedDecoder decoder = new LengthFieldBasedDecoder(
                2,   // lengthFieldOffset：magic 占 2 字节
                4,   // lengthFieldLength：length 占 4 字节
                0,   // lengthAdjustment：length 值即 body 长度
                6    // initialBytesToStrip：剥离 magic(2) + length(4) = 6 字节
        );

        List<byte[]> decodedFrames = decoder.decode(stickyData);
        System.out.println("  解码结果:");
        for (int i = 0; i < decodedFrames.size(); i++) {
            System.out.printf("    帧[%d]: \"%s\" (length=%d)%n",
                    i, new String(decodedFrames.get(i), StandardCharsets.UTF_8),
                    decodedFrames.get(i).length);
        }
        System.out.println();
    }

    /** 构建自定义协议帧 */
    private static byte[] buildFrame(byte[] body) {
        int totalLength = 2 + 4 + body.length; // magic + length + body
        ByteBuffer frame = ByteBuffer.allocate(totalLength);

        /* magic: 0xCAFE */
        frame.putShort((short) 0xCAFE);
        /* length: body 长度（大端序） */
        frame.putInt(body.length);
        /* body */
        frame.put(body);

        return frame.array();
    }

    /** 打印十六进制 */
    private static void printHex(byte[] data) {
        for (byte b : data) {
            System.out.printf("%02X ", b);
        }
        System.out.println();
    }

    /** 长度字段帧解码器 */
    static class LengthFieldBasedDecoder {
        private final int lengthFieldOffset;
        private final int lengthFieldLength;
        private final int lengthAdjustment;
        private final int initialBytesToStrip;
        private final ByteBuffer pending = ByteBuffer.allocate(65536);

        LengthFieldBasedDecoder(int lengthFieldOffset, int lengthFieldLength,
                                int lengthAdjustment, int initialBytesToStrip) {
            this.lengthFieldOffset = lengthFieldOffset;
            this.lengthFieldLength = lengthFieldLength;
            this.lengthAdjustment = lengthAdjustment;
            this.initialBytesToStrip = initialBytesToStrip;
            pending.clear();
        }

        List<byte[]> decode(byte[] data) {
            List<byte[]> frames = new ArrayList<>();

            /* 追加到 pending buffer */
            if (pending.remaining() < data.length) {
                /* 扩容 */
                ByteBuffer expanded = ByteBuffer.allocate(pending.capacity() * 2);
                pending.flip();
                expanded.put(pending);
                pending.clear();
                expanded.flip(); // no-op conceptually, we just use the new buffer
                // 简化实现：直接用新的
                // 实际 Netty 使用可扩展的 ByteBuf
            }
            pending.put(data);
            pending.flip(); // 切换到读模式

            while (pending.remaining() >= lengthFieldOffset + lengthFieldLength) {
                pending.mark();

                /* 跳过 lengthFieldOffset 字节，读取长度字段 */
                pending.position(pending.position() + lengthFieldOffset);

                long frameLength = 0;
                for (int i = 0; i < lengthFieldLength; i++) {
                    frameLength = (frameLength << 8) | (pending.get() & 0xFF);
                }
                frameLength += lengthAdjustment;

                /* 计算完整帧大小 = 头部 + body */
                int fullFrameSize = lengthFieldOffset + lengthFieldLength + (int) frameLength;

                if (pending.remaining() < frameLength) {
                    /* 半包：数据不够，回退等待更多数据 */
                    pending.reset();
                    break;
                }

                /* 跳到帧起始位置 */
                pending.reset();
                /* 跳过 initialBytesToStrip 字节 */
                pending.position(pending.position() + initialBytesToStrip);
                /* 读取 body */
                byte[] body = new byte[(int) frameLength];
                pending.get(body);
                frames.add(body);

                /* 压缩 pending：移除已处理的帧 */
                ByteBuffer remaining = ByteBuffer.allocate(pending.remaining());
                remaining.put(pending);
                remaining.flip();
                pending.clear();
                pending.put(remaining);
                pending.flip();
            }

            /* 切换回写模式，保留未处理的数据 */
            pending.compact();
            return frames;
        }
    }

    /* ==================== 5. 自定义协议编解码器 ==================== */

    /**
     * 自定义协议：magic(2B) + length(4B) + body(NB) 的 Encoder/Decoder 对。
     *
     * <p>模拟 Netty MessageToByteEncoder 和 ByteToMessageDecoder。
     * Encoder：Java 对象 → 字节数组（write magic + length + body）
     * Decoder：字节数组 → Java 对象（read magic 校验 + length + body）
     */
    private static void demonstrateCustomProtocolCodec() {
        System.out.println("--- 5. 自定义协议 Encoder/Decoder（完整编解码流程）---");

        /* === Encoder 编码 === */
        System.out.println("  [Encoder] 编码对象 → 字节（MessageToByteEncoder 模拟）:");
        CustomProtocolEncoder encoder = new CustomProtocolEncoder();

        byte[] encoded1 = encoder.encode("HelloWorld"); // 10 字节
        System.out.print("    \"HelloWorld\" → ");
        printHex(encoded1);

        byte[] encoded2 = encoder.encode("Java"); // 4 字节
        System.out.print("    \"Java\"       → ");
        printHex(encoded2);

        /* 模拟粘包：两条编码后的消息合并发送 */
        ByteBuffer merged = ByteBuffer.allocate(encoded1.length + encoded2.length);
        merged.put(encoded1).put(encoded2);
        merged.flip();
        byte[] stickyData = new byte[merged.remaining()];
        merged.get(stickyData);

        /* === Decoder 解码 === */
        System.out.println("\n  [Decoder] 解码字节 → 对象（ByteToMessageDecoder 模拟）:");
        CustomProtocolDecoder decoder = new CustomProtocolDecoder();
        List<String> decodedMessages = decoder.decode(stickyData);

        for (int i = 0; i < decodedMessages.size(); i++) {
            System.out.printf("    消息[%d]: \"%s\"%n", i, decodedMessages.get(i));
        }

        System.out.println("\n  协议结构：");
        System.out.println("    +---------+---------+-----------+");
        System.out.println("    | MAGIC(2)| LENGTH(4)| BODY(N)  |");
        System.out.println("    +---------+---------+-----------+");
        System.out.println("    MAGIC  = 0xCAFE（魔数校验，过滤非法数据）");
        System.out.println("    LENGTH = body 字节数（4 字节大端 int）");
        System.out.println("    BODY   = 实际数据载荷");
    }

    /** 自定义协议编码器 */
    static class CustomProtocolEncoder {
        private static final short MAGIC = (short) 0xCAFE;

        byte[] encode(String message) {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(2 + 4 + body.length);
            frame.putShort(MAGIC);
            frame.putInt(body.length);
            frame.put(body);
            return frame.array();
        }
    }

    /** 自定义协议解码器 */
    static class CustomProtocolDecoder {
        private static final short EXPECTED_MAGIC = (short) 0xCAFE;
        private final ByteBuffer pending = ByteBuffer.allocate(65536);

        CustomProtocolDecoder() {
            pending.clear();
        }

        List<String> decode(byte[] data) {
            List<String> messages = new ArrayList<>();
            pending.put(data);
            pending.flip();

            while (pending.remaining() >= 6) { // 最小帧 = magic(2) + length(4)
                pending.mark();

                /* 校验魔数 */
                short magic = pending.getShort();
                if (magic != EXPECTED_MAGIC) {
                    System.err.printf("    [Decoder] 魔数校验失败: expected=0x%04X, actual=0x%04X%n",
                            EXPECTED_MAGIC, magic);
                    /* 跳过 1 字节继续尝试（实际应关闭连接） */
                    pending.reset();
                    pending.get();
                    continue;
                }

                /* 读取长度 */
                int bodyLength = pending.getInt();

                /* 检查是否收到完整帧 */
                if (pending.remaining() < bodyLength) {
                    /* 半包：数据不够 */
                    pending.reset();
                    break;
                }

                /* 读取 body */
                byte[] body = new byte[bodyLength];
                pending.get(body);
                messages.add(new String(body, StandardCharsets.UTF_8));

                /* 压缩 pending */
                ByteBuffer remaining = ByteBuffer.allocate(pending.remaining());
                remaining.put(pending);
                remaining.flip();
                pending.clear();
                pending.put(remaining);
                pending.flip();
            }
            pending.compact();
            return messages;
        }
    }
}