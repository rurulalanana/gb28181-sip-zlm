package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GB28181 流媒体平台组合根：信令（gb28181-sip）+ 媒体（ZLMediaKit）一把启动。
 *
 * <p>最简用法：
 * <pre>{@code
 * Gb28181MediaPlatform platform = new Gb28181MediaPlatform(
 *         MediaServerProperties.builder().platformId("34020000002000000001").build());
 * platform.start();
 *
 * // 设备注册后，平台主动点播：
 * RtpSession session = platform.mediaService().play("34020000001320000001");
 * System.out.println(platform.mediaService().playUrls("34020000001320000001"));
 *
 * platform.stop();
 * }</pre>
 *
 * <p>设备也可以主动发 INVITE（被动点播），{@link Gb28181MediaService#onInvite}
 * 会自动完成收流协商并应答。
 */
public class Gb28181MediaPlatform implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Gb28181MediaPlatform.class);

    private final MediaServerProperties props;
    private ZlmMediaServer mediaServer;
    private RtpSessionManager sessionManager;
    private Gb28181MediaService mediaService;
    private RecordingService recordingService;
    private CascadeForwarder cascadeForwarder;
    private BroadcastService broadcastService;
    private Gb28181SipServer sipServer;

    public Gb28181MediaPlatform(MediaServerProperties props) {
        this.props = props == null ? MediaServerProperties.builder().build() : props;
    }

    /** 启动媒体服务与 SIP 信令服务。 */
    public synchronized void start() {
        if (mediaServer != null) {
            return;
        }
        // 1. 媒体服务（ZLMediaKit）
        mediaServer = new ZlmMediaServer(props);
        mediaServer.start();

        // 2. 会话管理 + 事件桥接
        sessionManager = new RtpSessionManager(mediaServer, props);
        recordingService = new RecordingService(mediaServer, props);
        recordingService.setAutoRecord(props.isAutoRecord());
        mediaServer.setStreamListener(new ZlmMediaServer.StreamListener() {
            @Override
            public void onStreamOnline(String app, String stream) {
                sessionManager.onStreamOnline(app, stream);
                recordingService.onStreamOnline(app, stream);
            }

            @Override
            public void onStreamOffline(String app, String stream) {
                sessionManager.onStreamOffline(app, stream);
                recordingService.onStreamOffline(app, stream);
            }
        });

        // 3. 级联转发 / 语音广播 + 信令服务（回调 = 流媒体服务）
        cascadeForwarder = new CascadeForwarder(mediaServer, sessionManager, props);
        broadcastService = new BroadcastService(mediaServer, sessionManager, props);
        mediaService = new Gb28181MediaService(mediaServer, sessionManager, props);
        mediaService.setCascadeForwarder(cascadeForwarder);
        mediaService.setBroadcastService(broadcastService);
        mediaService.setRecordingService(recordingService);
        sipServer = Gb28181SipServer.builder()
                .serverId(props.getPlatformId())
                .host(props.getSipHost())
                .port(props.getSipPort())
                .transport("UDP")
                .callback(mediaService)
                .build()
                .start();
        mediaService.attach(sipServer);

        log.info("GB28181 流媒体平台已启动: sip={}:{} media={} http={}",
                props.getSipHost(), props.getSipPort(), props.getMediaIp(), mediaServer.getHttpPort());
    }

    /** 停止全部服务（先信令后媒体，最后清会话）。 */
    public synchronized void stop() {
        if (mediaServer == null) {
            return;
        }
        try {
            if (sipServer != null) {
                sipServer.stop();
            }
        } catch (Throwable t) {
            log.warn("停止 SIP 服务异常", t);
        }
        try {
            sessionManager.closeAll();
        } catch (Throwable t) {
            log.warn("清理收流会话异常", t);
        }
        try {
            mediaServer.stop();
        } catch (Throwable t) {
            log.warn("停止媒体服务异常", t);
        }
        mediaServer = null;
        sessionManager = null;
        mediaService = null;
        recordingService = null;
        cascadeForwarder = null;
        broadcastService = null;
        sipServer = null;
        log.info("GB28181 流媒体平台已停止");
    }

    public Gb28181MediaService mediaService() {
        return mediaService;
    }

    public ZlmMediaServer mediaServer() {
        return mediaServer;
    }

    public RtpSessionManager sessionManager() {
        return sessionManager;
    }

    public Gb28181SipServer sipServer() {
        return sipServer;
    }

    /** 平台侧录像服务（录实时流、检索、点播）。 */
    public RecordingService recordingService() {
        return recordingService;
    }

    /** 国标级联媒体转发。 */
    public CascadeForwarder cascadeForwarder() {
        return cascadeForwarder;
    }

    /** 语音广播 / 对讲。 */
    public BroadcastService broadcastService() {
        return broadcastService;
    }

    /** 信令客户端（主动查询目录 / 点播 / PTZ 控制等）。 */
    public Gb28181SipClient client() {
        return sipServer == null ? null : sipServer.client();
    }

    @Override
    public void close() {
        stop();
    }
}
