package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.structure.MK_MEDIA;
import com.aizuda.zlm4j.structure.MK_RTP_SERVER;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    /**
     * 录制 API 冒烟：造媒体源 → <b>推真实帧</b> → 启动录制 → 停止 → 校验索引。
     *
     * <p>ZLM 只有在收到真实帧、轨道 ready 之后才把源注册进 MediaSource 表
     * （日志「媒体注册」），此前 {@code mk_recorder_start} 一定返回 0。
     * 所以「造源 + 推帧」是启动录制的必要前置——早期用例没有媒体源就断言录制成功，
     * 属于旧语义遗留（{@code mk_recorder_start} 官方语义是 {@code 1=成功、0=失败}），
     * 会稳定失败。
     *
     * <p>帧夹具 {@code src/test/resources/tiny-h264-idr.h264} 是 ffmpeg 生成的单帧
     * 64x64 baseline H264（SPS+PPS+SEI+IDR，654 字节），复现命令：
     * <pre>ffmpeg -f lavfi -i color=c=black:s=64x64:d=1 -frames:v 1 -c:v libx264 \
     *   -preset ultrafast -tune zerolatency -profile:v baseline \
     *   -x264-params "keyint=1:min-keyint=1:scenecut=0" -f h264 tiny-h264-idr.h264</pre>
     */
    @Test
    public void 录制与转推句柄应可用() throws Exception {
        MediaServerProperties props = 录制用例配置();
        ZlmMediaServer server = new ZlmMediaServer(props);
        server.start();
        MK_MEDIA media = null;
        try {
            media = ZlmMediaServer.api()
                    .mk_media_create("__defaultVhost__", "rtp", "rec-cam-1", 1.0f, 1, 0);
            assertNotNull("媒体源应创建成功", media);
            assertEquals("视频轨应初始化成功", 1,
                    ZlmMediaServer.api().mk_media_init_video(media, 0, 64, 64, 25.0f, 512));
            ZlmMediaServer.api().mk_media_init_complete(media);

            RecordingService recording = new RecordingService(server, props);
            // 源注册是异步的（在 ZLM event poller 上「媒体注册」）：推帧后重试若干次
            Recording rec = null;
            for (int i = 0; i < 40 && rec == null; i++) {
                推一帧(media);
                TimeUnit.MILLISECONDS.sleep(150);
                try {
                    rec = recording.startRecording("rec-cam-1");
                } catch (IllegalStateException 源尚未注册) {
                    // 继续推帧重试
                }
            }
            assertNotNull("推流后应能启动录制（源注册为异步）", rec);
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
        } finally {
            if (media != null) {
                ZlmMediaServer.api().mk_media_release(media);
            }
            server.stop();
        }
    }

    /** 推一帧 H264（含 SPS/PPS/IDR），让 ZLM 建出轨道并注册媒体源。 */
    private static void 推一帧(MK_MEDIA media) throws Exception {
        byte[] frame;
        try (InputStream in = ZlmMediaServerTest.class.getResourceAsStream("/tiny-h264-idr.h264")) {
            assertNotNull("测试帧夹具应存在", in);
            // 不用 readAllBytes（Java 9+）：本模块字节码目标 Java 8
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            byte[] chunk = new byte[512];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            frame = out.toByteArray();
        }
        Memory buf = new Memory(frame.length);
        buf.write(0, frame, 0, frame.length);
        long ts = System.currentTimeMillis();
        ZlmMediaServer.api().mk_media_input_h264(media, buf, frame.length, ts, ts);
    }

    /**
     * 媒体源不存在时 {@code mk_recorder_start} 返回 0，{@link RecordingService} 必须
     * <b>显式报错</b>而不是静默记账——否则索引里会留下永远没有文件的假录像条目。
     */
    @Test
    public void 媒体源不存在时启动录制应报错() {
        MediaServerProperties props = 录制用例配置();
        ZlmMediaServer server = new ZlmMediaServer(props);
        server.start();
        try {
            RecordingService recording = new RecordingService(server, props);
            try {
                recording.startRecording("no-such-cam");
                fail("媒体源不存在时应抛 IllegalStateException");
            } catch (IllegalStateException expected) {
                assertTrue("异常应说明录制启动失败: " + expected.getMessage(),
                        expected.getMessage().contains("启动录制失败"));
            }
            assertTrue("失败时不应留下录像条目", recording.recordings("no-such-cam").isEmpty());
        } finally {
            server.stop();
        }
    }

    /** 录制用例共用的配置（媒体端口复用冒烟端口，录制目录指向临时目录）。 */
    private static MediaServerProperties 录制用例配置() {
        return MediaServerProperties.builder()
                .mediaIp("127.0.0.1")
                .httpPort(HTTP_PORT)
                .rtmpPort(-1).rtspPort(-1).rtcPort(-1)
                .vhost("__defaultVhost__")
                .recordRoot(System.getProperty("java.io.tmpdir") + "/gb28181-rec-test")
                .build();
    }
}
