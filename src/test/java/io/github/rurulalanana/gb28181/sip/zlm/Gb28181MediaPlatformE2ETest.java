package io.github.rurulalanana.gb28181.sip.zlm;

import com.sun.jna.Native;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端测试：真实 GB28181 设备（裸 SIP 报文模拟）→ 平台（gb28181-sip 信令 +
 * ZLMediaKit 收流）全流程。
 *
 * <p>流程：REGISTER → INVITE(Play) → 断言 200 OK 且 SDP 端口与 ZLM RTP 会话端口一致
 * → ACK → BYE → 断言会话释放。另验证回放 INVITE 被 488 拒绝。
 */
public class Gb28181MediaPlatformE2ETest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String CAMERA_ID = "34020000001320000001";

    private static final int SIP_PORT = 15060;
    private static final int HTTP_PORT = 18083;

    @BeforeClass
    public static void 检查原生库可用() {
        try {
            Native.load("mk_api", com.aizuda.zlm4j.core.ZLMApi.class);
        } catch (Throwable t) {
            Assume.assumeTrue("当前平台无可用 ZLM 原生库，跳过: " + t, false);
        }
    }

    @Test
    public void 设备主动点播全流程() throws Exception {
        Gb28181MediaPlatform platform = new Gb28181MediaPlatform(
                MediaServerProperties.builder()
                        .platformId(PLATFORM_ID)
                        .sipHost("127.0.0.1")
                        .sipPort(SIP_PORT)
                        .mediaIp("127.0.0.1")
                        .httpPort(HTTP_PORT)
                        .rtmpPort(11937)
                        .rtspPort(15556)
                        .rtcPort(18002)
                        .app("rtp")
                        .build());
        platform.start();

        Device device = new Device(CAMERA_ID, PLATFORM_ID, SIP_PORT);
        try {
            // ---- 注册 ----
            String registerResp = device.register();
            assertTrue("REGISTER 应 200: " + firstLine(registerResp),
                    registerResp.startsWith("SIP/2.0 200"));

            // ---- INVITE（实时点播，设备侧声明 SSRC 1234567890）----
            String sdpOffer = "v=0\r\n"
                    + "o=" + CAMERA_ID + " 0 0 IN IP4 127.0.0.1\r\n"
                    + "s=Play\r\n"
                    + "c=IN IP4 127.0.0.1\r\n"
                    + "t=0 0\r\n"
                    + "m=video 40000 RTP/AVP 96\r\n"
                    + "a=rtpmap:96 PS/90000\r\n"
                    + "y=1234567890\r\n";
            String inviteResp = device.invite(sdpOffer);
            assertTrue("INVITE 应 200: " + firstLine(inviteResp),
                    inviteResp.startsWith("SIP/2.0 200"));

            // ---- 校验应答 SDP ----
            String answerSdp = body(inviteResp);
            int answerPort = parseVideoPort(answerSdp);
            assertTrue("应答 SDP 应携带媒体端口: " + answerSdp, answerPort > 0);

            RtpSession session = platform.sessionManager().getByChannel(CAMERA_ID);
            assertNotNull("会话应按通道登记", session);
            assertEquals("SDP 端口应与 ZLM RTP 服务器端口一致",
                    session.getPort(), answerPort);
            assertTrue("应答 SDP 应为 PS/90000: " + answerSdp, answerSdp.contains("a=rtpmap:96 PS/90000"));
            assertTrue("应答 SDP 应含平台 SSRC(y=10位): " + answerSdp,
                    Pattern.compile("y=\\d{10}").matcher(answerSdp).find());
            assertTrue("应答 SDP 应为媒体 IP: " + answerSdp, answerSdp.contains("c=IN IP4 127.0.0.1"));

            // ---- ACK ----
            device.ack(inviteResp);

            // ---- BYE → 会话释放 ----
            device.bye(inviteResp);
            boolean released = false;
            for (int i = 0; i < 50; i++) {
                if (platform.sessionManager().getByChannel(CAMERA_ID) == null) {
                    released = true;
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            assertTrue("BYE 后会话应释放", released);
        } finally {
            device.close();
            platform.stop();
        }
    }

    @Test
    public void 回放INVITE应被488拒绝() throws Exception {
        Gb28181MediaPlatform platform = new Gb28181MediaPlatform(
                MediaServerProperties.builder()
                        .platformId(PLATFORM_ID)
                        .sipHost("127.0.0.1")
                        .sipPort(SIP_PORT)
                        .mediaIp("127.0.0.1")
                        .httpPort(HTTP_PORT)
                        .rtmpPort(11937)
                        .rtspPort(15556)
                        .rtcPort(18002)
                        .build());
        platform.start();

        Device device = new Device(CAMERA_ID, PLATFORM_ID, SIP_PORT);
        try {
            String sdpOffer = "v=0\r\n"
                    + "o=" + CAMERA_ID + " 0 0 IN IP4 127.0.0.1\r\n"
                    + "s=Playback\r\n"
                    + "c=IN IP4 127.0.0.1\r\n"
                    + "t=0 0\r\n"
                    + "m=video 40000 RTP/AVP 96\r\n"
                    + "a=rtpmap:96 PS/90000\r\n";
            String resp = device.invite(sdpOffer);
            assertTrue("回放应被 488 拒绝: " + firstLine(resp), resp.startsWith("SIP/2.0 488"));
            assertTrue("不应创建会话", platform.sessionManager().sessionCount() == 0);
        } finally {
            device.close();
            platform.stop();
        }
    }

    // ==================== 设备模拟器 ====================

    /** 裸 SIP 报文设备模拟器（沿用 gb28181-sip 模块测试的模式）。 */
    static class Device implements Closeable {

        private final DatagramSocket socket;
        private final String deviceId;
        private final String platformId;
        private final int platformPort;
        private final String fromTag = "tag" + System.nanoTime();
        private int cseq = 0;
        private String lastCallId;

        Device(String deviceId, String platformId, int platformPort) throws IOException {
            this.socket = new DatagramSocket();
            this.socket.setSoTimeout(5000);
            this.deviceId = deviceId;
            this.platformId = platformId;
            this.platformPort = platformPort;
        }

        String register() throws IOException {
            String callId = newCallId();
            String msg = "REGISTER sip:" + platformId + "@127.0.0.1:" + platformPort + " SIP/2.0\r\n"
                    + via()
                    + "From: <sip:" + deviceId + "@3402000000>;tag=" + fromTag + "\r\n"
                    + "To: <sip:" + deviceId + "@3402000000>\r\n"
                    + "Call-ID: " + callId + "\r\n"
                    + "CSeq: " + (++cseq) + " REGISTER\r\n"
                    + "Contact: <sip:" + deviceId + "@127.0.0.1:" + socket.getLocalPort() + ">\r\n"
                    + "Expires: 3600\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Length: 0\r\n\r\n";
            return exchange(msg);
        }

        String invite(String sdpBody) throws IOException {
            lastCallId = newCallId();
            String subject = deviceId + ":" + platformId + ",0000000001:0000000001";
            String msg = "INVITE sip:" + platformId + "@127.0.0.1:" + platformPort + " SIP/2.0\r\n"
                    + via()
                    + "From: <sip:" + deviceId + "@3402000000>;tag=" + fromTag + "\r\n"
                    + "To: <sip:" + platformId + "@3402000000>\r\n"
                    + "Call-ID: " + lastCallId + "\r\n"
                    + "CSeq: " + (++cseq) + " INVITE\r\n"
                    + "Contact: <sip:" + deviceId + "@127.0.0.1:" + socket.getLocalPort() + ">\r\n"
                    + "Subject: " + subject + "\r\n"
                    + "Content-Type: application/sdp\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Length: " + sdpBody.getBytes(UTF8).length + "\r\n\r\n"
                    + sdpBody;
            return exchange(msg);
        }

        void ack(String okResponse) throws IOException {
            String toTag = headerParam(okResponse, "To", "tag");
            String msg = "ACK sip:" + platformId + "@127.0.0.1:" + platformPort + " SIP/2.0\r\n"
                    + via()
                    + "From: <sip:" + deviceId + "@3402000000>;tag=" + fromTag + "\r\n"
                    + "To: <sip:" + platformId + "@3402000000>" + (toTag == null ? "" : ";tag=" + toTag) + "\r\n"
                    + "Call-ID: " + lastCallId + "\r\n"
                    + "CSeq: " + cseq + " ACK\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Length: 0\r\n\r\n";
            send(msg);
        }

        void bye(String okResponse) throws IOException {
            String toTag = headerParam(okResponse, "To", "tag");
            String msg = "BYE sip:" + platformId + "@127.0.0.1:" + platformPort + " SIP/2.0\r\n"
                    + via()
                    + "From: <sip:" + deviceId + "@3402000000>;tag=" + fromTag + "\r\n"
                    + "To: <sip:" + platformId + "@3402000000>" + (toTag == null ? "" : ";tag=" + toTag) + "\r\n"
                    + "Call-ID: " + lastCallId + "\r\n"
                    + "CSeq: " + (++cseq) + " BYE\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Length: 0\r\n\r\n";
            // BYE 的 200 OK 应答
            exchange(msg);
        }

        // ---- 内部 ----

        private String via() {
            return "Via: SIP/2.0/UDP 127.0.0.1:" + socket.getLocalPort()
                    + ";branch=z9hG4bK-" + System.nanoTime() + "\r\n";
        }

        private String newCallId() {
            return System.nanoTime() + "@127.0.0.1";
        }

        private void send(String msg) throws IOException {
            byte[] data = msg.getBytes(UTF8);
            socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("127.0.0.1"), platformPort));
        }

        /** 发送并等待最终响应（跳过 100 Trying 等临时响应）。 */
        private String exchange(String msg) throws IOException {
            send(msg);
            byte[] buf = new byte[8192];
            for (int i = 0; i < 6; i++) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                socket.receive(resp);
                String text = new String(resp.getData(), 0, resp.getLength(), UTF8);
                if (!text.startsWith("SIP/2.0 100") && !text.startsWith("SIP/2.0 18")) {
                    return text;
                }
            }
            throw new IOException("未收到最终响应");
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    // ==================== 解析辅助 ====================

    private static String firstLine(String msg) {
        int idx = msg.indexOf("\r\n");
        return idx > 0 ? msg.substring(0, idx) : msg;
    }

    private static String body(String msg) {
        int idx = msg.indexOf("\r\n\r\n");
        return idx >= 0 ? msg.substring(idx + 4) : "";
    }

    private static final Pattern VIDEO_PORT = Pattern.compile("m=video\\s+(\\d+)");

    private static int parseVideoPort(String sdp) {
        Matcher m = VIDEO_PORT.matcher(sdp);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 取某个头域的指定参数，如 To 头的 tag。 */
    private static String headerParam(String msg, String headerName, String param) {
        for (String line : msg.split("\r\n")) {
            if (line.startsWith(headerName + ":")) {
                Matcher m = Pattern.compile(";" + param + "=([^;\\s]+)").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }
}
