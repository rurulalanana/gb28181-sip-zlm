package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.SipPeer;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 纯逻辑单测：Subject 解析、SDP 应答构造、SSRC 归一化、播放地址。
 * 不加载原生库，任何平台可跑。
 */
public class MediaServiceUnitTest {

    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String CAMERA_ID = "34020000001320000001";

    private final Gb28181MediaService service = newService();

    private Gb28181MediaService newService() {
        MediaServerProperties props = MediaServerProperties.builder()
                .platformId(PLATFORM_ID)
                .mediaIp("192.168.1.100")
                .httpPort(8080)
                .rtmpPort(1935)
                .rtspPort(8554)
                .rtcPort(8000)
                .app("rtp")
                .build();
        ZlmMediaServer mediaServer = new ZlmMediaServer(props);
        RtpSessionManager manager = new RtpSessionManager(mediaServer, props);
        return new Gb28181MediaService(mediaServer, manager, props);
    }

    // ==================== Subject 解析 ====================

    @Test
    public void subject标准格式应取发送者为通道() {
        InviteEvent event = invite("34020000001320000001:34020000002000000001,0000000001:0000000001");
        assertEquals(CAMERA_ID, service.resolveChannelId(event));
    }

    @Test
    public void subject无序号段应只取ID对() {
        InviteEvent event = invite(CAMERA_ID + ":" + PLATFORM_ID);
        assertEquals(CAMERA_ID, service.resolveChannelId(event));
    }

    @Test
    public void subject首个编码是平台时应取接收者() {
        // 级联/异常场景：平台编码出现在发送者位置
        InviteEvent event = invite(PLATFORM_ID + ":" + CAMERA_ID + ",0000000001:0000000001");
        assertEquals(CAMERA_ID, service.resolveChannelId(event));
    }

    @Test
    public void subject缺失应退回来路设备编码() {
        InviteEvent event = invite(null);
        assertEquals(CAMERA_ID, service.resolveChannelId(event));
    }

    // ==================== SDP 应答 ====================

    @Test
    public void 点播下行SDP应符合国标约定() {
        RtpSession session = fakeSession(6000, "0100000001");
        SdpMessage sdp = service.buildAnswerSdp(session, false);
        String text = sdp.toText();

        assertTrue("s=Play 缺失: " + text, text.contains("s=Play"));
        assertTrue("o= 应含平台编码与媒体IP: " + text, text.contains("o=" + PLATFORM_ID + " 0 0 IN IP4 192.168.1.100"));
        assertTrue("c= 应为媒体IP: " + text, text.contains("c=IN IP4 192.168.1.100"));
        assertTrue("t=0 0 缺失: " + text, text.contains("t=0 0"));
        assertTrue("m= 行应为 UDP/多路pt/协商端口: " + text, text.contains("m=video 6000 RTP/AVP 96 97 98 99"));
        // 国标约定：pt=96 固定为 PS 封装，并宣告 97/98/99 供设备选择编码
        // 对照 WVP-GB28181-pro SIPCommander#buildInviteSdp
        assertTrue("a=rtpmap:96 PS/90000 缺失: " + text, text.contains("a=rtpmap:96 PS/90000"));
        assertTrue("a=rtpmap:98 H264/90000 缺失: " + text, text.contains("a=rtpmap:98 H264/90000"));
        assertTrue("a=rtpmap:99 H265/90000 缺失: " + text, text.contains("a=rtpmap:99 H265/90000"));
        assertTrue("m= 应宣告多路 pt: " + text, text.contains("RTP/AVP 96 97 98 99"));
        // 平台下行时须带 a=recvonly（平台只收不发）
        assertTrue("a=recvonly 缺失: " + text, text.contains("a=recvonly"));
        assertTrue("y= 应为平台SSRC: " + text, text.contains("y=0100000001"));
        assertFalse("UDP 不应带 setup: " + text, text.contains("setup"));
    }

    @Test
    public void tcp应答SDP应用TCP协议并宣告多路pt() {
        RtpSession session = fakeSession(6001, "0100000002");
        SdpMessage sdp = service.buildAnswerSdp(session, true);
        String text = sdp.toText();

        assertTrue("TCP 应为 TCP/RTP/AVP: " + text, text.contains("m=video 6001 TCP/RTP/AVP"));
        // 国标约定：pt=96 固定 PS 封装，同时宣告 97/98/99 供设备选择内部编码。
        // 只宣告单一编码会让设备按 PS 推流却收到 H264 声明，进而回 500。
        assertTrue("应宣告 96 为 PS 封装: " + text, text.contains("a=rtpmap:96 PS/90000"));
        assertTrue("应宣告多路 pt: " + text, text.contains("96 97 98 99"));
        assertTrue("应带 recvonly: " + text, text.contains("a=recvonly"));
        // 下行不下发 a=setup:passive —— 与 WVP-GB28181-pro 实际报文一致，
        // 其源码该行已被注释（未发现支持此特性的设备）。
        assertFalse("下行不应下发 a=setup:passive: " + text, text.contains("a=setup:passive"));
    }

    /** UDP 应答同样宣告 PS + 多路 pt，且协议为 UDP。 */
    @Test
    public void udp应答SDP应宣告PS与多路pt() {
        RtpSession session = fakeSession(6001, "0100000002");
        String text = service.buildAnswerSdp(session, false).toText();
        assertTrue("UDP 应为 RTP/AVP: " + text, text.contains("m=video 6001 RTP/AVP 96 97 98 99"));
        assertTrue("应带 ssrc: " + text, text.contains("y=0100000002"));
    }

    // ==================== SSRC 归一化 ====================

    @Test
    public void ssrc归一化() {
        assertEquals("0", RtpSessionManager.normalizeSsrc(null));
        assertEquals("0", RtpSessionManager.normalizeSsrc(""));
        assertEquals("0", RtpSessionManager.normalizeSsrc("0"));
        assertEquals("0", RtpSessionManager.normalizeSsrc("abc"));
        assertEquals("1234567890", RtpSessionManager.normalizeSsrc("1234567890"));
        assertEquals("987", RtpSessionManager.normalizeSsrc(" 987 "));
    }

    // ==================== 播放地址 ====================

    @Test
    public void 播放地址格式() {
        // 单端口收流（e1b0dac）后启用「会话双坐标」：app = 设备编码，
        // stream = <设备编码>_<通道编码>，app 重新作为 URL 路径段参与拼接。
        PlayUrls urls = PlayUrls.of("192.168.1.100", 8080, 1935, 8554, 8000,
                "34020000001320000001", "34020000001320000001_34020000001310000001");
        assertEquals("http://192.168.1.100:8080/34020000001320000001"
                + "/34020000001320000001_34020000001310000001.live.flv", urls.getHttpFlv());
        assertEquals("ws://192.168.1.100:8080/34020000001320000001"
                + "/34020000001320000001_34020000001310000001.live.flv", urls.getWsFlv());
        assertEquals("http://192.168.1.100:8080/34020000001320000001"
                + "/34020000001320000001_34020000001310000001/hls.m3u8", urls.getHls());
        assertEquals("webrtc://192.168.1.100:8000/34020000001320000001"
                + "/34020000001320000001_34020000001310000001", urls.getWebrtc());
        assertEquals("rtsp://192.168.1.100:8554/34020000001320000001"
                + "/34020000001320000001_34020000001310000001", urls.getRtsp());
        assertEquals("rtmp://192.168.1.100:1935/34020000001320000001"
                + "/34020000001320000001_34020000001310000001", urls.getRtmp());
    }

    /** app 为空时退化为仅 stream，不产生前导斜杠。 */
    @Test
    public void app为空时不拼前缀() {
        PlayUrls urls = PlayUrls.of("192.168.1.100", 8080, -1, -1, -1, null, "cam1");
        assertEquals("http://192.168.1.100:8080/cam1.live.flv", urls.getHttpFlv());
        PlayUrls blank = PlayUrls.of("192.168.1.100", 8080, -1, -1, -1, "  ", "cam1");
        assertEquals("http://192.168.1.100:8080/cam1.live.flv", blank.getHttpFlv());
    }

    @Test
    public void 未启动的协议不生成地址() {
        PlayUrls urls = PlayUrls.of("127.0.0.1", 8080, -1, -1, -1, "rtp", "cam1");
        assertNotNull(urls.getHttpFlv());
        assertNull(urls.getRtmp());
        assertNull(urls.getRtsp());
        assertNull(urls.getWebrtc());
    }

    // ==================== 辅助 ====================

    private InviteEvent invite(String subject) {
        return InviteEvent.builder()
                .subject(subject)
                .peer(SipPeer.of(CAMERA_ID, "127.0.0.1", 15061))
                .method("INVITE")
                .callId("test-call-id")
                .build();
    }

    private RtpSession fakeSession(int port, String ssrc) {
        return fakeSession(port, ssrc, MediaType.REALTIME, null);
    }

    private RtpSession fakeSession(int port, String ssrc, MediaType type, String mediaUri) {
        return new RtpSession(CAMERA_ID, CAMERA_ID, ssrc, "1234567890", 0,
                "test-call-id", null, port, type, mediaUri);
    }
}
