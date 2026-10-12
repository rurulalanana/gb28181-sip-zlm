package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_EVENTS;
import com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ZLMediaKit 媒体服务器封装（基于 zlm4j）。
 *
 * <p>职责：
 * <ul>
 *   <li>ZLM 环境初始化与 HTTP/RTMP/RTSP/WebRTC 服务启停</li>
 *   <li>全局流事件（上线 / 下线）广播，由 {@link StreamListener} 消费</li>
 *   <li>播放地址（FLV/HLS/WebRTC/RTSP/RTMP）生成</li>
 * </ul>
 *
 * <p><b>注意</b>：ZLMediaKit 的 {@code /index/api/*} RESTful 接口是由 MediaServer
 * 可执行文件实现的，C API 嵌入方（本项目）不会注册这些路由，因此不要依赖 HTTP API，
 * 状态一律走 {@link MK_EVENTS} 事件回调。
 *
 * <p>线程安全：{@link #start()} / {@link #stop()} 可重复调用（幂等）。
 */
public class ZlmMediaServer {

    private static final Logger log = LoggerFactory.getLogger(ZlmMediaServer.class);

    /** JNA 单例。整个 JVM 只需加载一次；zlm4j jar 内已打包各平台原生库。 */
    private static volatile ZLMApi api;

    private final MediaServerProperties props;
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 实际监听端口（0 表示未启动）。start 后有效。 */
    private volatile int httpPort;
    private volatile int rtmpPort;
    private volatile int rtspPort;
    private volatile int rtcPort;

    /** 流事件监听者（由 RtpSessionManager 设置）。 */
    private volatile StreamListener streamListener;

    /** 必须强引用：JNA 回调被 GC 后原生侧调用会崩溃。 */
    private MK_EVENTS events;

    public ZlmMediaServer(MediaServerProperties props) {
        this.props = props == null ? MediaServerProperties.builder().build() : props;
    }

    /** 获取 ZLM C API 实例（懒加载，幂等）。 */
    public static ZLMApi api() {
        if (api == null) {
            synchronized (ZlmMediaServer.class) {
                if (api == null) {
                    api = Native.load("mk_api", ZLMApi.class);
                }
            }
        }
        return api;
    }

    public void setStreamListener(StreamListener listener) {
        this.streamListener = listener;
    }

    // ==================== 生命周期 ====================

    /** 初始化 ZLM 环境并按配置启动各协议服务。幂等。 */
    public synchronized void start() {
        if (started.get()) {
            return;
        }
        ZLMApi a = api();
        // 负载类型需在 env 初始化前写入全局配置（国标默认 PS=96）
        applyPayloadTypes(a);
        // 线程数=1, 日志级别=1, 控制台日志开；不加载外部 ini（默认配置已适配 GB28181：ps_pt=96, rtp 超时 15s）
        a.mk_env_init2(1, 1, 1, null, 0, 0, null, 0, null, null);

        events = buildEvents(a);
        a.mk_events_listen(events);

        // 注意：ZLM 的 http/rtmp/rtsp/rtc 服务传 0 表示"用配置默认端口"（80/1935/554/8000），
        // 而 80 需要 root 才能绑定；这里把 0 解释为"自动选择空闲端口"，避免默认端口不可用。
        httpPort = checkPort(unsign16(a.mk_http_server_start((short) resolvePort(props.getHttpPort()), 0)),
                "HTTP", props.getHttpPort());
        rtmpPort = startOptional(a, props.getRtmpPort(),
                p -> unsign16(a.mk_rtmp_server_start((short) resolvePort(p), 0)), "RTMP");
        rtspPort = startOptional(a, props.getRtspPort(),
                p -> unsign16(a.mk_rtsp_server_start((short) resolvePort(p), 0)), "RTSP");
        rtcPort = startOptional(a, props.getRtcPort(),
                p -> unsign16(a.mk_rtc_server_start((short) resolvePort(p))), "WebRTC");

        started.set(true);
        log.info("ZLMediaKit 已启动: http={} rtmp={} rtsp={} rtc={} mediaIp={} app={}",
                httpPort, rtmpPort, rtspPort, rtcPort, props.getMediaIp(), props.getApp());
    }

    /** 停止所有服务。幂等。 */
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        try {
            api().mk_stop_all_server();
            log.info("ZLMediaKit 已停止");
        } catch (Throwable t) {
            log.warn("停止 ZLMediaKit 异常", t);
        } finally {
            httpPort = rtmpPort = rtspPort = rtcPort = 0;
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getRtmpPort() {
        return rtmpPort;
    }

    public int getRtspPort() {
        return rtspPort;
    }

    public int getRtcPort() {
        return rtcPort;
    }

    // ==================== 播放地址 ====================

    /** 生成某一路流的各协议播放地址。 */
    public PlayUrls playUrls(String stream) {
        return PlayUrls.of(props.getMediaIp(), httpPort, rtmpPort, rtspPort, rtcPort,
                props.getApp(), stream);
    }

    /**
     * 查询某路流的 RTP 接收状态。
     *
     * <p>底层为 ZLM 的 {@code mk_rtp_get_info}：{@code exist=1} 表示该端口已收到过
     * RTP 包（媒体确实在流入），{@code peerIp/peerPort} 为设备来源地址。
     *
     * <p>比事件回调可靠：MK_EVENTS 的回调是异步且未注册推流鉴权时不保证投递，
     * 而这里是主动查询 ZLM 内部状态。
     *
     * @return 收到过 RTP 包返回 true
     */
    public boolean isRtpReceiving(String app, String stream) {
        return rtpPeer(app, stream) != null;
    }

    /**
     * 查询某路流最近一个 RTP 发送方的地址。
     *
     * @return {@code ip:port}；未收到过包返回 {@code null}
     */
    public String rtpPeer(String app, String stream) {
        java.util.concurrent.CompletableFuture<String> future =
                new java.util.concurrent.CompletableFuture<>();
        try {
            api().mk_rtp_get_info(app, stream, (exist, localIp, localPort, peerIp, peerPort, ssrc) -> {
                if (exist != 0 && peerIp != null && !peerIp.isEmpty()) {
                    future.complete(peerIp + ":" + (peerPort & 0xFFFF));
                } else {
                    future.complete(null);
                }
            });
            return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 事件 ====================

    /** 流上线 / 下线监听者。回调来自 ZLM 原生线程，实现需线程安全且不得阻塞。 */
    public interface StreamListener {
        /** 流开始有数据（MediaSource 注册）。 */
        void onStreamOnline(String app, String stream);

        /** 流注销（RTP 超时 / 主动关闭）。 */
        void onStreamOffline(String app, String stream);
    }

    // ==================== 内部 ====================

    /** 按需覆盖 ZLM 的 RTP 负载类型（推非 PS 测试流时使用）。 */
    private void applyPayloadTypes(ZLMApi a) {
        setOption(a, "rtp.ps_pt", props.getPsPayloadType());
        setOption(a, "rtp.h264_pt", props.getH264PayloadType());
        setOption(a, "rtp.h265_pt", props.getH265PayloadType());
    }

    private void setOption(ZLMApi a, String key, int value) {
        if (value <= 0) {
            return;
        }
        try {
            a.mk_set_option(key, String.valueOf(value));
            log.info("ZLM 配置 {}={}", key, value);
        } catch (Throwable t) {
            log.warn("设置 ZLM 配置 {}={} 失败: {}", key, value, t.getMessage());
        }
    }

    private MK_EVENTS buildEvents(ZLMApi a) {
        MK_EVENTS ev = new MK_EVENTS();

        // 说明：这里<b>不注册</b> on_mk_media_publish。ZLM 在注册了推流鉴权回调时会等待
        // embedder 调用 auth invoker 放行，而 zlm4j 未映射该函数指针，直接调用会导致
        // JVM 崩溃；不注册则 ZLM 按默认配置自动放行，媒体可直接进入。
        // 流的上下线由下面的 on_mk_media_changed（注册/注销）感知。

        // 流注册(1)/注销(0)
        ev.on_mk_media_changed = (int regist, MK_MEDIA_SOURCE source) -> {
            try {
                String app = a.mk_media_source_get_app(source);
                String stream = a.mk_media_source_get_stream(source);
                StreamListener l = streamListener;
                if (l != null) {
                    if (regist != 0) {
                        l.onStreamOnline(app, stream);
                    } else {
                        l.onStreamOffline(app, stream);
                    }
                }
            } catch (Throwable t) {
                log.warn("处理 on_mk_media_changed 异常", t);
            }
        };

        return ev;
    }

    /**
     * ZLM 端口返回值的符号修正。
     *
     * <p>C 侧返回 {@code uint16_t}，而 zlm4j 将返回值绑定为有符号 {@code short}，
     * 因此端口号 ≥ 32768 时会回传负数（如 53158 → -12378），必须按无符号 16 位解读。
     */
    private static int unsign16(short port) {
        return port & 0xFFFF;
    }

    /** 配置端口 &lt;= 0 时自动选取一个空闲端口。 */
    private static int resolvePort(int configured) {
        if (configured > 0) {
            return configured;
        }
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法分配空闲媒体端口: " + e.getMessage(), e);
        }
    }

    private int startOptional(ZLMApi a, int configuredPort,
                              java.util.function.IntUnaryOperator starter, String name) {
        if (configuredPort < 0) {
            return 0;
        }
        return checkPort(starter.applyAsInt(configuredPort), name, configuredPort);
    }

    /** ZLM 启动失败返回 0（不抛异常），这里转成明确异常。 */
    private int checkPort(int actualPort, String name, int configuredPort) {
        if (actualPort <= 0) {
            throw new IllegalStateException(name + " 服务启动失败（端口 "
                    + configuredPort + " 可能被占用或不可绑定）");
        }
        return actualPort;
    }
}
