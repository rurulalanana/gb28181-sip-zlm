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
    public void 应答SDP应为国标PS格式() {
        RtpSession session = fakeSession(6000, "0100000001");
        SdpMessage sdp = service.buildAnswerSdp(session, false);
        String text = sdp.toText();

        assertTrue("s=Play 缺失: " + text, text.contains("s=Play"));
        assertTrue("o= 应含平台编码与媒体IP: " + text, text.contains("o=" + PLATFORM_ID + " 0 0 IN IP4 192.168.1.100"));
        assertTrue("c= 应为媒体IP: " + text, text.contains("c=IN IP4 192.168.1.100"));
        assertTrue("t=0 0 缺失: " + text, text.contains("t=0 0"));
        assertTrue("m= 行应为 UDP/96/协商端口: " + text, text.contains("m=video 6000 RTP/AVP 96"));
        assertTrue("a=rtpmap:96 PS/90000 缺失: " + text, text.contains("a=rtpmap:96 PS/90000"));
        assertTrue("y= 应为平台SSRC: " + text, text.contains("y=0100000001"));
        assertFalse("UDP 不应带 setup: " + text, text.contains("setup"));
    }

    @Test
    public void tcp应答SDP应带setupPassive与TCP协议() {
        RtpSession session = fakeSession(6001, "0100000002");
        SdpMessage sdp = service.buildAnswerSdp(session, true);
        String text = sdp.toText();

        assertTrue("TCP 应为 TCP/RTP/AVP: " + text, text.contains("m=video 6001 TCP/RTP/AVP 96"));
        assertTrue("TCP 应答应带 a=setup:passive: " + text, text.contains("a=setup:passive"));
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
        PlayUrls urls = PlayUrls.of("192.168.1.100", 8080, 1935, 8554, 8000, "rtp", "34020000001320000001");
        assertEquals("http://192.168.1.100:8080/rtp/34020000001320000001.live.flv", urls.getHttpFlv());
        assertEquals("ws://192.168.1.100:8080/rtp/34020000001320000001.live.flv", urls.getWsFlv());
        assertEquals("http://192.168.1.100:8080/rtp/34020000001320000001/hls.m3u8", urls.getHls());
        assertEquals("webrtc://192.168.1.100:8000/rtp/34020000001320000001", urls.getWebrtc());
        assertEquals("rtsp://192.168.1.100:8554/rtp/34020000001320000001", urls.getRtsp());
        assertEquals("rtmp://192.168.1.100:1935/rtp/34020000001320000001", urls.getRtmp());
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
        return new RtpSession(CAMERA_ID, CAMERA_ID, ssrc, "1234567890", 0,
                "test-call-id", null, port);
    }
}
