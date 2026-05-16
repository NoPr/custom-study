package base.nio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * TCP 粘包/半包问题演示 + 三种解决方案
 *
 * 核心知识点：
 *
 * 【粘包/半包原因】
 * TCP 是流式协议（Stream），消息之间没有边界。
 * 应用层 write("AB") + write("CD") 可能被 TCP 合并为 ABCDEF 一次发送（粘包），
 * 也可能被拆分为 AB 和 CD 两次接收，或者 A 和 BCDEF（半包）。
 *
 * 根本原因：
 * 1. Nagle 算法：将小数据包合并发送以降低网络开销
 * 2. MSS 分段：超过 MSS（最大报文段长度）的数据会被分包
 * 3. 接收缓冲区：应用层读取速度跟不上网络接收速度
 *
 * 【三种解决方案】
 * 1. 定长解码（FixedLengthFrameDecoder）：每个消息固定长度，不足补 0
 * 2. 分隔符解码（DelimiterBasedFrameDecoder）：每个消息以特定字符结尾（如 \n、\r\n）
 * 3. 长度域解码（LengthFieldBasedFrameDecoder）：消息头 + 消息体，头记录体长度
 */
public class StickyPacketDemo {

    private static final int PORT = 9001;

    public static void main(String[] args) throws Exception {
        stickyPacketReasonExplain();
        stickyPacketSimulation();
        fixedLengthSolution();
        delimiterSolution();
        lengthFieldSolution();
    }

    static void stickyPacketReasonExplain() {
        System.out.println("=== 粘包/半包产生原因 ===");
        System.out.println();
        System.out.println("TCP 是「流式协议」—— 字节流，消息之间没有边界标识。");
        System.out.println();
        System.out.println("发送端 write(\"ABC\") + write(\"DEF\")：");
        System.out.println("  TCP 可能合并发送: [ABCDEF]        ← 粘包");
        System.out.println("  TCP 可能分包发送: [AB] [CDEF]     ← 半包");
        System.out.println();
        System.out.println("三大根本原因：");
        System.out.println("  1. Nagle 算法：将小包合并为大包，减少网络中的小包数量");
        System.out.println("  2. MSS 限制：   数据超过 MSS（1460 字节）会被 TCP 层分片");
        System.out.println("  3. 应用层速度： 应用层读取速度慢，TCP 缓冲区积压多个消息");
        System.out.println();
    }

    /**
     * 模拟粘包场景 — BIO 服务器快速连续发送，客户端一次接收多条消息
     */
    static void stickyPacketSimulation() throws Exception {
        System.out.println("=== 粘包场景模拟 ===");

        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.println("[BIO 服务器] 启动，端口 " + PORT);

                try (Socket clientSocket = serverSocket.accept();
                     OutputStream out = clientSocket.getOutputStream()) {

                    String[] messages = {
                            "Hello", "World", "TCP", "Sticky", "Packet"
                    };

                    for (String message : messages) {
                        byte[] data = message.getBytes(StandardCharsets.UTF_8);
                        out.write(data);
                        System.out.println("[BIO 服务器] 写入: " + message
                                + " (" + data.length + " bytes)");
                    }
                    out.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "sticky-server");
        serverThread.start();

        TimeUnit.MILLISECONDS.sleep(300);

        try (Socket socket = new Socket("localhost", PORT);
             InputStream in = socket.getInputStream()) {

            byte[] receiveBuffer = new byte[1024];
            System.out.println("[BIO 客户端] 开始读取...");
            TimeUnit.MILLISECONDS.sleep(500);

            int bytesRead = in.read(receiveBuffer);
            byte[] actualData = Arrays.copyOf(receiveBuffer, bytesRead);
            String received = new String(actualData, StandardCharsets.UTF_8);

            System.out.println("[BIO 客户端] 一次性收到 " + bytesRead
                    + " bytes: \"" + received + "\"");
            System.out.println("  问题：5 条消息粘在一起，无法区分边界！");
            System.out.println("  5 条独立消息变成了 1 个字符串 \"" + received + "\"");
        }

        serverThread.join();
        System.out.println();
    }

    /**
     * 方案 1：定长解码 — 每个消息固定 20 字节，不足补空格
     */
    static void fixedLengthSolution() {
        System.out.println("=== 方案 1：定长解码（FixedLengthFrameDecoder）===");
        System.out.println();

        int fixedLength = 20;
        String[] rawMessages = {"Hello", "World", "TCP", "Sticky", "Packet"};

        ByteBuffer encodeBuffer = ByteBuffer.allocate(1024);
        for (String message : rawMessages) {
            byte[] padded = new byte[fixedLength];
            byte[] raw = message.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(raw, 0, padded, 0,
                    Math.min(raw.length, fixedLength));
            encodeBuffer.put(padded);
            System.out.println("  编码: \"" + message + "\" → "
                    + fixedLength + " bytes 定长帧");
        }
        encodeBuffer.flip();

        System.out.println("  编码后总数据: " + encodeBuffer.remaining() + " bytes");
        System.out.println();

        System.out.println("  解码：按 " + fixedLength + " bytes 切分帧");
        int frameIndex = 0;
        while (encodeBuffer.remaining() >= fixedLength) {
            byte[] frame = new byte[fixedLength];
            encodeBuffer.get(frame);
            String message = new String(frame, StandardCharsets.UTF_8).trim();
            System.out.println("  帧 " + (++frameIndex) + ": \"" + message + "\"");
        }

        System.out.println();
        System.out.println("优点：实现简单，解码效率高");
        System.out.println("缺点：浪费带宽（短消息填充大量无用字节）");
        System.out.println("适用场景：固定长度指令协议（如 Modbus）");
        System.out.println();
    }

    /**
     * 方案 2：分隔符解码 — 每个消息以 \n 结尾
     */
    static void delimiterSolution() {
        System.out.println("=== 方案 2：分隔符解码（DelimiterBasedFrameDecoder）===");
        System.out.println();

        String[] rawMessages = {"Hello", "World", "TCP", "Sticky", "Packet"};
        String delimiter = "\n";

        StringBuilder encoded = new StringBuilder();
        for (String message : rawMessages) {
            encoded.append(message).append(delimiter);
        }
        byte[] encodedBytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
        String encodedStr = new String(encodedBytes, StandardCharsets.UTF_8);

        System.out.println("  编码数据（转义显示）: \""
                + encodedStr.replace("\n", "\\n") + "\"");
        System.out.println();

        System.out.println("  解码：按 '\\n' 分割");
        String[] frames = encodedStr.split("\n", -1);
        for (int i = 0; i < frames.length - 1; i++) {
            System.out.println("  帧 " + (i + 1) + ": \"" + frames[i] + "\"");
        }

        System.out.println();
        System.out.println("优点：可读性好，适合文本协议");
        System.out.println("缺点：消息体不能包含分隔符（需转义处理）");
        System.out.println("适用场景：Redis 协议、HTTP 头、Telnet");
        System.out.println();
    }

    /**
     * 方案 3：LengthField 解码 — 4 字节消息头 + 消息体
     * 头部的 int 值表示消息体长度
     */
    static void lengthFieldSolution() {
        System.out.println("=== 方案 3：LengthField 解码（LengthFieldBasedFrameDecoder）===");
        System.out.println();

        String[] rawMessages = {"Hello", "World", "TCP Sticky Packet Demo", "Short", "LongerMessage"};

        ByteBuffer encodeBuffer = ByteBuffer.allocate(1024);
        for (String message : rawMessages) {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            encodeBuffer.putInt(body.length);
            encodeBuffer.put(body);
            System.out.println("  编码: [长度=" + body.length + "] [数据=\"" + message + "\"]");
        }
        encodeBuffer.flip();

        System.out.println("  编码后总数据: " + encodeBuffer.remaining() + " bytes");
        System.out.println();

        System.out.println("  解码：先读 4 字节长度 → 再读对应字节的数据");
        int frameIndex = 0;
        while (encodeBuffer.remaining() >= 4) {
            int bodyLength = encodeBuffer.getInt();
            if (encodeBuffer.remaining() < bodyLength) {
                System.out.println("  半包！需要 " + bodyLength + " bytes，只剩 "
                        + encodeBuffer.remaining() + " bytes");
                break;
            }
            byte[] body = new byte[bodyLength];
            encodeBuffer.get(body);
            String message = new String(body, StandardCharsets.UTF_8);
            System.out.println("  帧 " + (++frameIndex) + ": [长度=" + bodyLength
                    + "] \"" + message + "\"");
        }

        System.out.println();
        System.out.println("LengthField 参数说明（Netty 对应）：");
        System.out.println("  lengthFieldOffset   = 0   ← 长度域起始偏移");
        System.out.println("  lengthFieldLength   = 4   ← 长度域占 4 字节（int）");
        System.out.println("  lengthAdjustment    = 0   ← 长度域值 + 调整值 = body 长度");
        System.out.println("  initialBytesToStrip = 4   ← 解码后跳过 4 字节头");
        System.out.println();
        System.out.println("优点：精确、高效、无歧义");
        System.out.println("缺点：需要预知消息结构");
        System.out.println("适用场景：绝大多数二进制协议（Dubbo、gRPC、MQTT）");
        System.out.println();
    }
}