package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.security.CredentialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private WebRtcService webRtcService;
    private Gb28181SipServer sipServer;

    /**
     * 无人观看关流线程池；{@link #start()} 创建、{@link #stop()} 关闭。
     *
     * <p>ZLM 的无人观看回调是<b>同步派发</b>在它自己的 event poller 线程上的
     * （{@code NoticeCenter::emitEvent} 内联调用所有监听者），回调返回后 ZLM 还要
     * 继续用它自己的对象。而关流动作包含 SIP BYE（网络 I/O）与遍历注销媒体源，
     * 耗时且会阻塞该线程，因此必须异步执行，回调里只做入队。
     *
     * <p>用固定 2 线程而非单线程：关流可能涉及多个协议实例，单线程会让
     * 后到的请求排队；也不宜过多，避免大量设备同时超时时打爆 SIP 栈。
     */
    private volatile ExecutorService noReaderExecutor;

    public Gb28181MediaPlatform(MediaServerProperties props) {
        this.props = props == null ? MediaServerProperties.builder().build() : props;
    }

    /** 启动媒体服务与 SIP 信令服务。 */
    public synchronized void start() {
        if (mediaServer != null) {
            return;
        }
        // 关流线程池先建：无人观看回调注册时就要用到
        noReaderExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "gb28181-no-reader");
            t.setDaemon(true);
            return t;
        });
        // 1. 媒体服务（ZLMediaKit）
        mediaServer = new ZlmMediaServer(props);
        mediaServer.start();

        // 2. 会话管理 + 事件桥接
        sessionManager = new RtpSessionManager(mediaServer, props);
        // 录像服务需要会话管理器来把「通道编码」解析成 ZLM 实际 app/stream：
        // 单端口收流下 ZLM 流名是 <设备编码>_<通道编码>，与业务坐标不同
        recordingService = new RecordingService(mediaServer, props, sessionManager);
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

        // MP4 录制分片落盘：ZLM 决定文件名与日期子目录，只有它能给出真实路径与大小，
        // 因此录像条目的 filePath/fileSize 必须由本回调回填（见 RecordingService#onRecordMp4）。
        mediaServer.setRecordListener(recordingService);

        // 无人观看自动关流：ZLM 判定超时后回调，此处发 BYE 并释放会话。
        // 不让 ZLM 直接关流（forceClose 保持 false），因为还需通知 SIP 侧释放设备带宽。
        // 回调必须透传 app：ZLM 的流标识有两种布局，只给 stream 会拿到裸序号而查不到会话。
        //
        // 回调运行在 ZLM 的 event poller 线程上，且是同步派发（NoticeCenter::emitEvent）。
        // 而 onNoViewer 内部要发 SIP BYE（网络 I/O）并遍历注销媒体源，绝不能占用该线程：
        // 一旦同步阻塞，ZLM 的 Timer lambda 会在事件返回后继续调用 MediaSource::getMuxer()，
        // 此时监听者已被我们关掉，于是抛 "MediaSourceEvent is empty"（见 forwardZlmLog 注释）。
        // 因此这里只做入队，实际关闭交给独立线程。
        mediaServer.setForceCloseOnNoReader(false);
        mediaServer.setNoReaderListener(new ZlmMediaServer.NoReaderListener() {
            @Override
            public void onNoReader(String app, String stream, String schema) {
                ExecutorService executor = noReaderExecutor;
                // 捕获为局部变量：任务在别的线程执行，期间 stop() 可能把字段置 null
                Gb28181MediaService svc = mediaService;
                if (executor == null || executor.isShutdown() || svc == null) {
                    // 平台正在停止：此时会话会被 closeAll 统一清理，无需再关流
                    log.debug("平台已停止，忽略无人观看事件: app={} stream={}", app, stream);
                    return;
                }
                executor.execute(() -> {
                    try {
                        svc.onNoViewer(app, stream);
                    } catch (Throwable t) {
                        log.warn("无人观看关流失败: app={} stream={}", app, stream, t);
                    }
                });
            }
        });

        // 3. 级联转发 / 语音广播 + 信令服务（回调 = 流媒体服务）
        cascadeForwarder = new CascadeForwarder(mediaServer, sessionManager, props);
        broadcastService = new BroadcastService(mediaServer, sessionManager, props);
        webRtcService = new WebRtcService(mediaServer);
        mediaService = new Gb28181MediaService(mediaServer, sessionManager, props);
        mediaService.setCascadeForwarder(cascadeForwarder);
        mediaService.setBroadcastService(broadcastService);
        mediaService.setRecordingService(recordingService);
        sipServer = Gb28181SipServer.builder()
                .serverId(props.getPlatformId())
                .host(props.getSipHost())
                .advertisedHost(props.getSipAdvertisedIp())
                .port(props.getSipPort())
                .transport("UDP")
                .callback(mediaService)
                .credentialProvider(resolveCredentialProvider(props))
                .build()
                .start();
        mediaService.attach(sipServer);

        log.info("GB28181 流媒体平台已启动: sip={}:{} media={} http={}",
                props.getSipHost(), props.getSipPort(), props.getMediaIp(), mediaServer.getHttpPort());
    }

    /**
     * 解析注册鉴权策略。
     *
     * <p>优先级：{@code credentialProvider} &gt; {@code sipPassword} &gt; 不鉴权。
     * 返回 {@code null} 表示交给 SIP 服务器用默认的 {@code PERMISSIVE}（不鉴权）。
     *
     * <p><b>不校验用户名</b>：SIP 的 Authorization.username 只是查密码用的键，
     * 各厂商填法不一（SIP 域 / 平台编码 / 设备编号），强制校验会让大量设备注册失败。
     */
    private static CredentialProvider resolveCredentialProvider(MediaServerProperties props) {
        if (props.getCredentialProvider() != null) {
            log.info("SIP 注册鉴权：使用自定义凭据提供者");
            return props.getCredentialProvider();
        }
        String password = props.getSipPassword();
        if (password != null && !password.isBlank()) {
            log.info("SIP 注册鉴权：已启用（平台统一密码）");
            // 任何 username 都返回同一密码，即只校验密码本身
            return username -> password;
        }
        log.warn("SIP 注册鉴权：未启用（任何设备均可注册）。公网部署请设置 gb28181.sip-password");
        return null;
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
        webRtcService = null;
        sipServer = null;
        // 关流线程池最后关：前面的 closeAll 会触发 onSessionClosed 回调，
        // 可能还有排队中的关流任务需要跑完
        ExecutorService executor = noReaderExecutor;
        noReaderExecutor = null;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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

    /** WebRTC 播放信令（低延迟通路；ws-flv 由 ZLM 内置 HTTP 服务直接提供）。 */
    public WebRtcService webRtcService() {
        return webRtcService;
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
