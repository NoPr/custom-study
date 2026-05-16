package base.websocket;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Scanner;

/**
 * WebSocket 协议原理：HTTP Upgrade + 帧格式(Fin/Opcode/Mask/Payload)。
 *
 * <p>本类演示 WebSocket 协议的核心机制：</p>
 * <ul>
 *   <li>握手过程：客户端 SEC-WebSocket-Key + GUID → SHA1 + Base64 → 服务端 SEC-WebSocket-Accept</li>
 *   <li>帧格式解析：FIN / RSV1-3 / Opcode / MASK / Payload Length（7位/7+16位/7+64位 三种长度）</li>
 *   <li>Opcode 类型：Text(0x1) / Binary(0x2) / Close(0x8) / Ping(0x9) / Pong(0xA)</li>
 *   <li>掩码处理：客户端→服务器 masked，服务器→客户端 unmasked</li>
 * </ul>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455">RFC 6455 - The WebSocket Protocol</a>
 */
public class WebSocketCoreDemo {

    /** WebSocket 协议版本，RFC 6455 规定为 13 */
    static final String WS_VERSION = "13";

    /** WebSocket 握手 Magic GUID，用于计算 Accept Key */
    static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** 服务端口 */
    static final int SERVER_PORT = 18080;

    // ======================== Opcode 定义 ========================

    /** 延续帧 */
    static final int OPCODE_CONTINUATION = 0x0;
    /** 文本帧 */
    static final int OPCODE_TEXT = 0x1;
    /** 二进制帧 */
    static final int OPCODE_BINARY = 0x2;
    /** 连接关闭帧 */
    static final int OPCODE_CLOSE = 0x8;
    /** Ping 帧（心跳请求） */
    static final int OPCODE_PING = 0x9;
    /** Pong 帧（心跳响应） */
    static final int OPCODE_PONG = 0xA;

    // ======================== 握手模块 ========================

    /**
     * 计算 SEC-WebSocket-Accept 响应头。
     *
     * <p>算法：Base64( SHA1( clientKey + MAGIC_GUID ) )</p>
     *
     * @param clientKey 客户端请求头 SEC-WebSocket-Key 的值
     * @return 计算后的 Accept 值
     */
    static String computeAcceptKey(String clientKey) throws NoSuchAlgorithmException {
        String concatenated = clientKey + WS_MAGIC_GUID;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(concatenated.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 构造 WebSocket 握手 HTTP 响应报文。
     *
     * <p>状态码必须为 101 Switching Protocols，且必须包含 Upgrade 和 Connection 头。</p>
     */
    static String buildHandshakeResponse(String clientKey) throws NoSuchAlgorithmException {
        String acceptKey = computeAcceptKey(clientKey);
        return "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                + "\r\n";
    }

    /**
     * 从 HTTP 请求头中提取 SEC-WebSocket-Key。
     */
    static String extractWebSocketKey(String httpRequest) {
        for (String line : httpRequest.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return null;
    }

    // ======================== 帧格式模块 ========================

    /**
     * 帧格式二进制结构（参考 RFC 6455 Section 5.2）：
     *
     * <pre>
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-------+-+-------------+-------------------------------+
     * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
     * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
     * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
     * | |1|2|3|       |K|             |                               |
     * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
     * |     Extended payload length continued, if payload len == 127  |
     * + - - - - - - - - - - - - - - - +-------------------------------+
     * |                               |Masking-key, if MASK set to 1  |
     * +-------------------------------+-------------------------------+
     * | Masking-key (continued)       |          Payload Data         |
     * +-------------------------------- - - - - - - - - - - - - - - - +
     * :                     Payload Data continued ...                :
     * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
     * |                     Payload Data continued ...                |
     * +---------------------------------------------------------------+
     * </pre>
     */

    /**
     * 构造一个简单的 WebSocket 文本帧（服务端→客户端，不加掩码）。
     *
     * @param message 要发送的文本内容
     * @return 完整的帧字节数组
     */
    static byte[] buildTextFrame(String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        return buildFrame(true, OPCODE_TEXT, false, payload);
    }

    /**
     * 构造 Ping 帧。
     */
    static byte[] buildPingFrame() {
        return buildFrame(true, OPCODE_PING, false, new byte[0]);
    }

    /**
     * 构造 Pong 帧。
     */
    static byte[] buildPongFrame() {
        return buildFrame(true, OPCODE_PONG, false, new byte[0]);
    }

    /**
     * 构造 Close 帧。
     */
    static byte[] buildCloseFrame() {
        return buildFrame(true, OPCODE_CLOSE, false, new byte[0]);
    }

    /**
     * 通用帧构造方法。
     *
     * @param fin      是否为最后一帧（FIN bit）
     * @param opcode   操作码
     * @param mask     是否添加掩码（客户端→服务端为 true）
     * @param payload  载荷数据
     * @return 完整帧字节数组
     */
    static byte[] buildFrame(boolean fin, int opcode, boolean mask, byte[] payload) {
        int payloadLen = payload.length;
        int frameSize;

        // 根据 Payload Length 计算帧总大小
        if (payloadLen < 126) {
            frameSize = 2 + payloadLen + (mask ? 4 : 0);
        } else if (payloadLen <= 0xFFFF) {
            frameSize = 4 + payloadLen + (mask ? 4 : 0); // 2 + 2 ext
        } else {
            frameSize = 10 + payloadLen + (mask ? 4 : 0); // 2 + 8 ext
        }

        byte[] frame = new byte[frameSize];
        int pos = 0;

        // Byte 0: FIN(1) + RSV(3) + Opcode(4)
        frame[pos++] = (byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F));

        // Byte 1: MASK(1) + Payload Length(7)
        frame[pos++] = (byte) ((mask ? 0x80 : 0x00) | (payloadLen < 126 ? payloadLen : (payloadLen <= 0xFFFF ? 126 : 127)));

        // Extended Payload Length
        if (payloadLen >= 126 && payloadLen <= 0xFFFF) {
            frame[pos++] = (byte) ((payloadLen >> 8) & 0xFF);
            frame[pos++] = (byte) (payloadLen & 0xFF);
        } else if (payloadLen > 0xFFFF) {
            for (int i = 7; i >= 0; i--) {
                frame[pos++] = (byte) ((payloadLen >> (i * 8)) & 0xFF);
            }
        }

        // Masking Key（4 字节随机值）
        byte[] maskingKey = null;
        if (mask) {
            maskingKey = new byte[]{(byte) (Math.random() * 256), (byte) (Math.random() * 256),
                    (byte) (Math.random() * 256), (byte) (Math.random() * 256)};
            System.arraycopy(maskingKey, 0, frame, pos, 4);
            pos += 4;
        }

        // Payload Data（客户端需 XOR 掩码）
        if (mask) {
            for (int i = 0; i < payloadLen; i++) {
                frame[pos + i] = (byte) (payload[i] ^ maskingKey[i % 4]);
            }
        } else {
            System.arraycopy(payload, 0, frame, pos, payloadLen);
        }

        return frame;
    }

    // ======================== 帧解析模块 ========================

    /**
     * 帧解析结果，包含帧头各字段和载荷数据。
     */
    static class ParsedFrame {
        boolean fin;            // 是否为最后一帧
        int opcode;             // 操作码
        boolean masked;         // 是否有掩码
        int payloadLen;         // 载荷长度
        byte[] maskingKey;      // 掩码密钥（4 字节或 null）
        byte[] payload;         // 解码后的载荷数据

        String opcodeName() {
            return switch (opcode) {
                case OPCODE_TEXT -> "TEXT";
                case OPCODE_BINARY -> "BINARY";
                case OPCODE_CLOSE -> "CLOSE";
                case OPCODE_PING -> "PING";
                case OPCODE_PONG -> "PONG";
                case OPCODE_CONTINUATION -> "CONTINUATION";
                default -> "UNKNOWN(" + opcode + ")";
            };
        }

        @Override
        public String toString() {
            String payloadPreview = payloadLen > 0 ? new String(payload, StandardCharsets.UTF_8) : "(empty)";
            return String.format("Frame{FIN=%s, Opcode=%s, Masked=%s, PayloadLen=%d, Payload=%s}",
                    fin, opcodeName(), masked, payloadLen, payloadPreview);
        }
    }

    /**
     * 从字节流解析 WebSocket 帧。
     *
     * @param frame 完整的帧字节数组
     * @return 解析后的帧对象
     */
    static ParsedFrame parseFrame(byte[] frame) {
        ParsedFrame result = new ParsedFrame();
        int pos = 0;

        // Byte 0: FIN + RSV + Opcode
        result.fin = (frame[pos] & 0x80) != 0;
        result.opcode = frame[pos] & 0x0F;
        pos++;

        // Byte 1: MASK + Payload Length
        result.masked = (frame[pos] & 0x80) != 0;
        result.payloadLen = frame[pos] & 0x7F;
        pos++;

        // Extended Payload Length
        if (result.payloadLen == 126) {
            result.payloadLen = ((frame[pos] & 0xFF) << 8) | (frame[pos + 1] & 0xFF);
            pos += 2;
        } else if (result.payloadLen == 127) {
            result.payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                result.payloadLen = (result.payloadLen << 8) | (frame[pos + i] & 0xFF);
            }
            pos += 8;
        }

        // Masking Key
        if (result.masked) {
            result.maskingKey = new byte[]{frame[pos], frame[pos + 1], frame[pos + 2], frame[pos + 3]};
            pos += 4;
        }

        // Payload Data（客户端帧需 XOR 解码）
        result.payload = new byte[result.payloadLen];
        if (result.masked) {
            for (int i = 0; i < result.payloadLen; i++) {
                result.payload[i] = (byte) (frame[pos + i] ^ result.maskingKey[i % 4]);
            }
        } else {
            System.arraycopy(frame, pos, result.payload, 0, result.payloadLen);
        }

        return result;
    }

    // ======================== 演示入口 ========================

    static void demoHandshake() throws NoSuchAlgorithmException {
        System.out.println("=== 1. WebSocket 握手原理演示 ===");

        String clientKey = "dGhlIHNhbXBsZSBub25jZQ=="; // RFC 6455 示例
        System.out.println("客户端发送 SEC-WebSocket-Key: " + clientKey);

        String acceptKey = computeAcceptKey(clientKey);
        System.out.println("服务端计算 SEC-WebSocket-Accept: " + acceptKey);
        // RFC 6455 标准答案：s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
        System.out.println("RFC 6455 预期值:    s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
        System.out.println("匹配: " + acceptKey.equals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));

        System.out.println("\n完整握手响应报文:");
        System.out.println(buildHandshakeResponse(clientKey));
    }

    static void demoFrameFormat() {
        System.out.println("\n=== 2. WebSocket 帧格式演示 ===");

        // 服务端 → 客户端帧（不加掩码）
        String textMessage = "Hello WebSocket!";
        byte[] serverFrame = buildTextFrame(textMessage);
        System.out.println("服务端帧（unmasked）: " + bytesToHex(serverFrame));
        ParsedFrame parsedServer = parseFrame(serverFrame);
        System.out.println(" > 解析: " + parsedServer);

        // 模拟客户端 → 服务端帧（加掩码）
        byte[] maskedFrame = buildFrame(true, OPCODE_TEXT, true, textMessage.getBytes(StandardCharsets.UTF_8));
        System.out.println("\n客户端帧（masked）: " + bytesToHex(maskedFrame));
        ParsedFrame parsedClient = parseFrame(maskedFrame);
        System.out.println(" > 解析: " + parsedClient);
    }

    static void demoOpcodeTypes() {
        System.out.println("\n=== 3. Opcode 类型演示 ===");

        byte[] textFrame = buildFrame(true, OPCODE_TEXT, true, "你好WebSocket".getBytes(StandardCharsets.UTF_8));
        ParsedFrame pfText = parseFrame(textFrame);
        System.out.println("Text 帧:    " + pfText.opcodeName() + " | Payload=" + new String(pfText.payload, StandardCharsets.UTF_8));

        byte[] pingFrame = buildPingFrame();
        ParsedFrame pfPing = parseFrame(pingFrame);
        System.out.println("Ping 帧:    " + pfPing.opcodeName());

        byte[] pongFrame = buildPongFrame();
        ParsedFrame pfPong = parseFrame(pongFrame);
        System.out.println("Pong 帧:    " + pfPong.opcodeName());

        byte[] closeFrame = buildCloseFrame();
        ParsedFrame pfClose = parseFrame(closeFrame);
        System.out.println("Close 帧:   " + pfClose.opcodeName());

        byte[] binaryFrame = buildFrame(true, OPCODE_BINARY, false, new byte[]{0x00, 0x01, 0x02, 0x03});
        ParsedFrame pfBinary = parseFrame(binaryFrame);
        System.out.println("Binary 帧:  " + pfBinary.opcodeName() + " | PayloadLen=" + pfBinary.payloadLen);
    }

    static void demoPayloadLengthVariants() {
        System.out.println("\n=== 4. Payload Length 三种长度演示 ===");

        // 7位长度：payload < 126
        byte[] shortPayload = new byte[50];
        byte[] shortFrame = buildFrame(true, OPCODE_BINARY, false, shortPayload);
        System.out.println("7位(50字节):   帧头=2B, 总大小=" + shortFrame.length + "B");

        // 7+16位：126 <= payload <= 65535
        byte[] mediumPayload = new byte[200];
        byte[] mediumFrame = buildFrame(true, OPCODE_BINARY, false, mediumPayload);
        System.out.println("7+16位(200字节): 帧头=4B, 总大小=" + mediumFrame.length + "B");

        // 7+64位：payload > 65535（用 70000 模拟）
        byte[] longPayload = new byte[70000];
        byte[] longFrame = buildFrame(true, OPCODE_BINARY, false, longPayload);
        System.out.println("7+64位(70000字节): 帧头=10B, 总大小=" + longFrame.length + "B");
    }

    /**
     * 启动简易 WebSocket 服务端（单次握手 + 回显）。
     * 在另一个终端使用浏览器或 wscat 连接 ws://localhost:18080 测试。
     */
    static void startSimServer() {
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(SERVER_PORT)) {
                System.out.println("\n=== 5. 简易 WebSocket 服务端启动 ws://localhost:" + SERVER_PORT + " ===");
                System.out.println("（用浏览器打开 ws 客户端或 wscat -c ws://localhost:" + SERVER_PORT + " 测试）");
                while (true) {
                    try (Socket client = server.accept()) {
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();

                        // 读取 HTTP 握手请求
                        Scanner scanner = new Scanner(in, StandardCharsets.UTF_8).useDelimiter("\r\n\r\n");
                        String httpRequest = scanner.hasNext() ? scanner.next() : "";
                        System.out.println("[握手] 收到请求:\n" + httpRequest.split("\r\n")[0]);

                        String wsKey = extractWebSocketKey(httpRequest);
                        if (wsKey == null) {
                            System.out.println("[握手] 未找到 SEC-WebSocket-Key，不是 WebSocket 请求");
                            continue;
                        }

                        // 发送握手响应
                        String response = buildHandshakeResponse(wsKey);
                        out.write(response.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        System.out.println("[握手] 已发送 101 响应");

                        // 简单的回显循环
                        byte[] buffer = new byte[65536];
                        while (true) {
                            int read = in.read(buffer, 0, 2); // 最少读前 2 字节
                            if (read <= 0) break;

                            int firstByte = buffer[0] & 0xFF;
                            int secondByte = buffer[1] & 0xFF;
                            boolean isClose = (firstByte & 0x0F) == OPCODE_CLOSE;
                            if (isClose) {
                                System.out.println("[帧] 收到 Close 帧，关闭连接");
                                // 回应 Close 帧
                                out.write(buildCloseFrame());
                                out.flush();
                                break;
                            }

                            boolean isPing = (firstByte & 0x0F) == OPCODE_PING;
                            if (isPing) {
                                System.out.println("[帧] 收到 Ping，回复 Pong");
                                // 需要完整读取帧后回复 Pong
                                int payloadLen = secondByte & 0x7F;
                                int headerSize = 2 + (payloadLen == 126 ? 2 : payloadLen == 127 ? 8 : 0) + 4; // +4 for mask
                                in.readNBytes(buffer, 2, headerSize - 2 + payloadLen);
                                out.write(buildPongFrame());
                                out.flush();
                                continue;
                            }

                            // 读取剩余帧数据
                            int payloadLen = secondByte & 0x7F;
                            int extraHeader = (payloadLen == 126 ? 2 : payloadLen == 127 ? 8 : 0);
                            int totalHeader = 2 + extraHeader + 4; // frame header + mask
                            int bytesToRead = totalHeader - 2 + payloadLen;
                            if (bytesToRead > 0) {
                                in.readNBytes(buffer, 2, bytesToRead);
                            }

                            // 构造完整帧用于解析
                            byte[] fullFrame = new byte[totalHeader + payloadLen];
                            fullFrame[0] = buffer[0];
                            fullFrame[1] = buffer[1];
                            if (bytesToRead > 0) {
                                System.arraycopy(buffer, 2, fullFrame, 2, bytesToRead);
                            }

                            ParsedFrame parsed = parseFrame(fullFrame);
                            if (parsed.opcode == OPCODE_TEXT) {
                                String msg = new String(parsed.payload, StandardCharsets.UTF_8);
                                System.out.println("[帧] 收到 Text: " + msg);
                                // 回显
                                String echoMsg = "Echo: " + msg;
                                out.write(buildTextFrame(echoMsg));
                                out.flush();
                            }
                        }
                    } catch (IOException e) {
                        System.out.println("[连接] 异常断开: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("[服务端] 启动失败: " + e.getMessage());
            }
        }, "ws-server").start();
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 64); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        if (bytes.length > 64) sb.append("...");
        return sb.toString().trim();
    }

    /** 主入口：运行所有演示。 */
    public static void main(String[] args) throws Exception {
        demoHandshake();
        demoFrameFormat();
        demoOpcodeTypes();
        demoPayloadLengthVariants();
        startSimServer();
        System.out.println("\n=== WebSocketCoreDemo 演示完成 ===");
        System.out.println("简易服务器继续在后台运行，按 Ctrl+C 停止。");
    }
}