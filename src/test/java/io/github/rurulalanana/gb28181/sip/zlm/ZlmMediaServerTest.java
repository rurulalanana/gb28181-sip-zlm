package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.structure.MK_RTP_SERVER;
import com.sun.jna.Native;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ZLMediaKit 原生库冒烟测试：环境初始化、服务启动、RTP 服务器创建/释放、播放地址。
 *
 * <p>zlm4j jar 内打包了 win/linux/mac 全平台原生库，测试在对应平台直接可跑；
 * 平台不匹配时 {@link Assume} 跳过。
 */
public class ZlmMediaServerTest {

    private static final int HTTP_PORT = 18082;
    private static final int RTMP_PORT = 11936;
    private static final int RTSP_PORT = 15555;
    private static final int RTC_PORT = 18001;

    @BeforeClass
    public static void 检查原生库可用() {
        try {
            Native.load("mk_api", com.aizuda.zlm4j.core.ZLMApi.class);
        } catch (Throwable t) {
            Assume.assumeTrue("当前平台无可用 ZLM 原生库，跳过: " + t, false);
        }
    }

    @Test
    public void 服务启动与Rtp服务器创建释放() {
        MediaServerProperties props = MediaServerProperties.builder()
                .mediaIp("127.0.0.1")
                .httpPort(HTTP_PORT)
                .rtmpPort(RTMP_PORT)
                .rtspPort(RTSP_PORT)
                .rtcPort(RTC_PORT)
                .app("rtp")
                .build();
        ZlmMediaServer server = new ZlmMediaServer(props);
        server.start();

        try {
            assertTrue("HTTP 端口应生效", server.getHttpPort() == HTTP_PORT);
            assertTrue("RTMP 端口应生效", server.getRtmpPort() == RTMP_PORT);
            assertTrue("RTSP 端口应生效", server.getRtspPort() == RTSP_PORT);
            assertTrue("WebRTC 端口应生效", server.getRtcPort() == RTC_PORT);

            // UDP 模式，port=0 让 ZLM 随机分配。
            // 参数序：mk_rtp_server_create2(port, tcp_mode, vhost, app, stream_id)
            // app = 设备编码，stream_id = 通道编码
            MK_RTP_SERVER handle = ZlmMediaServer.api().mk_rtp_server_create2(
                    (short) 0, 0, "__defaultVhost__", "smoke-dev-1", "smoke-cam-1");
            assertNotNull("RTP 服务器应创建成功", handle);
            int port = ZlmMediaServer.api().mk_rtp_server_port(handle) & 0xFFFF;
            assertTrue("RTP 端口应大于 0: " + port, port > 0);

            // SSRC 过滤走独立接口（create2 没有 ssrc 参数）
            ZlmMediaServer.api().mk_rtp_server_update_ssrc(handle, 1234567890);

            // TCP 被动模式
            MK_RTP_SERVER tcpHandle = ZlmMediaServer.api().mk_rtp_server_create2(
                    (short) 0, 1, "__defaultVhost__", "smoke-dev-2", "smoke-cam-2");
            assertNotNull("TCP RTP 服务器应创建成功", tcpHandle);
            assertTrue("TCP RTP 端口应大于 0",
                    (ZlmMediaServer.api().mk_rtp_server_port(tcpHandle) & 0xFFFF) > 0);

            // 播放地址路径必须是 app/stream（app=设备编码、stream=通道编码）
            PlayUrls urls = server.playUrls("smoke-dev-1", "smoke-cam-1");
            assertTrue("HTTP-FLV 应含 app/stream: " + urls.getHttpFlv(),
                    urls.getHttpFlv().contains("/smoke-dev-1/smoke-cam-1.live.flv"));
            assertTrue("RTMP 应含 app/stream: " + urls.getRtmp(),
                    urls.getRtmp().contains("/smoke-dev-1/smoke-cam-1"));
            assertTrue("RTSP 应含 app/stream: " + urls.getRtsp(),
                    urls.getRtsp().contains("/smoke-dev-1/smoke-cam-1"));

            // WebRTC 协商地址同样要带 app/stream
            assertEquals("rtc://__defaultVhost__/smoke-dev-1/smoke-cam-1",
                    server.resolveRtcUrl("smoke-dev-1", "smoke-cam-1"));

            ZlmMediaServer.api().mk_rtp_server_release(handle);
            ZlmMediaServer.api().mk_rtp_server_release(tcpHandle);
        } finally {
            server.stop();
        }
    }

    @Test
    public void stop后应可再次start() {
        MediaServerProperties props = MediaServerProperties.builder()
                .mediaIp("127.0.0.1")
                .httpPort(HTTP_PORT)
                .rtmpPort(-1)
                .rtspPort(-1)
                .rtcPort(-1)
                .build();
        ZlmMediaServer server = new ZlmMediaServer(props);
        server.start();
        assertTrue(server.isStarted());
        server.stop();
        assertTrue(!server.isStarted());
        // ZLM 进程级单例：再次 start 复用同一环境（幂等）
        server.start();
        assertTrue(server.isStarted());
        assertTrue(server.getRtmpPort() == 0);
        server.stop();
    }

    /** 录制 API：流不存在时 ZLM 挂起任务返回 0，能正确注册/停止，不崩溃。 */
    @Test
    public void 录制与转推句柄应可用() {
        MediaServerProperties props = MediaServerProperties.builder()
                .mediaIp("127.0.0.1")
                .httpPort(HTTP_PORT)
                .rtmpPort(-1).rtspPort(-1).rtcPort(-1)
                .vhost("__defaultVhost__")
                .recordRoot(System.getProperty("java.io.tmpdir") + "/gb28181-rec-test")
                .build();
        ZlmMediaServer server = new ZlmMediaServer(props);
        server.start();
        try {
            RecordingService recording = new RecordingService(server, props);
            Recording rec = recording.startRecording("rec-cam-1");
            assertNotNull("应返回录像条目", rec);
            assertTrue("录制任务应处于进行中", recording.isRecording("rec-cam-1"));
            assertEquals("录像条目应索引在通道下", 1, recording.recordings("rec-cam-1").size());

            // 检索：按通道与时间窗
            assertEquals("按通道检索应命中", 1,
                    recording.search("rec-cam-1", System.currentTimeMillis() - 60000L,
                            System.currentTimeMillis() + 60000L).size());
            assertEquals("按其它通道检索应为空", 0, recording.search("other-cam", null, null).size());

            Recording closed = recording.stopRecording("rec-cam-1");
            assertNotNull("停止应返回条目", closed);
            assertTrue("停止后状态应为 CLOSED", Recording.CLOSED.equals(closed.getStatus()));
            assertTrue(!recording.isRecording("rec-cam-1"));

            // 转推句柄（级联/对讲下行用）：MK_MEDIA 可创建与释放
            com.aizuda.zlm4j.structure.MK_MEDIA media = ZlmMediaServer.api()
                    .mk_media_create("__defaultVhost__", "rtp", "rec-cam-1", 1.0f, 1, 0);
            assertNotNull("MK_MEDIA 应创建成功", media);
            ZlmMediaServer.api().mk_media_release(media);
        } finally {
            server.stop();
        }
    }
}
