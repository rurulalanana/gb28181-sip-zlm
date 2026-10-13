package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_EVENTS;
import com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * 流事件监听者。
     *
     * <p>是<b>列表</b>而不是单个字段：媒体平台内部需要用它驱动会话状态
     * （{@code RtpSessionManager}），而调用方（如 spring 侧的事件桥）也要
     * 用同一份事件落库。早先只有一个 {@code setStreamListener}，后设的会
     * 覆盖先设的，导致会话状态永远停在 NEGOTIATING。
     */
    private final List<StreamListener> streamListeners = new CopyOnWriteArrayList<>();

    /** 无人观看回调监听者。 */
    private volatile NoReaderListener noReaderListener;

    /** 无人观看时是否由 ZLM 直接关流（否则交业务侧处理）。 */
    private volatile boolean forceCloseOnNoReader;

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

    /**
     * 注册流事件监听者（可注册多个，按注册顺序依次回调）。
     *
     * <p>等价于 {@code addStreamListener}，保留此名是为了兼容既有调用方。
     */
    public void setStreamListener(StreamListener listener) {
        addStreamListener(listener);
    }

    /** 追加一个流事件监听者（重复注册同一个实例不会重复回调）。 */
    public void addStreamListener(StreamListener listener) {
        if (listener != null && !streamListeners.contains(listener)) {
            streamListeners.add(listener);
        }
    }

    /** 移除流事件监听者。 */
    public void removeStreamListener(StreamListener listener) {
        streamListeners.remove(listener);
    }

    /** 注册无人观看监听者。 */
    public void setNoReaderListener(NoReaderListener listener) {
        this.noReaderListener = listener;
    }

    /** 无人观看时是否由 ZLM 直接关流。 */
    public void setForceCloseOnNoReader(boolean force) {
        this.forceCloseOnNoReader = force;
    }

    /** 无人观看事件监听者。 */
    public interface NoReaderListener {
        /**
         * 某流持续无人观看。
         *
         * @param app流所属应用
         * @param stream 流名（形如 {@code <channelId>/<序号>}）
         * @param schema 协议（rtp/ts/hls/fmp4/rtmp）
         */
        void onNoReader(String app, String stream, String schema);
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
        applyProtocolOptions(a);
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
        setOption(a, key, String.valueOf(value));
    }

    /**
     * 设置 ZLM 配置项。
     *
     * <p>库模式（{@code Native.load("mk_api")}）没有 ini 文件，
     * 所有 {@code [protocol] / [hls] / [general]} 段配置都要在此注入，
     * 否则转协议（HLS/TS/fMP4）与无人观看检测等能力不会启用。
     */
    private void setOption(ZLMApi a, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            a.mk_set_option(key, value);
            log.info("ZLM 配置 {}={}", key, value);
        } catch (Throwable t) {
            log.warn("设置 ZLM 配置 {}={} 失败: {}", key, value, t.getMessage());
        }
    }

    /**
     * 注入转协议与流管理相关配置。
     *
     * <p>对应 ZLM 的 {@code [protocol]} / {@code [hls]} / {@code [general]} 段。
     * 缺任何一项都会导致对应的播放协议不可用：
     * <ul>
     *   <li>{@code protocol.enable_hls} —— 不开则 HLS 切片不生成</li>
     *   <li>{@code hls.hls_save_path} / {@code http.rootPath} —— 切片落盘目录</li>
     *   <li>{@code general.streamNoneReaderDelayMS} —— 无人观看判定时长</li>
     * </ul>
     */
    private void applyProtocolOptions(ZLMApi a) {
        // ---- [protocol] 转协议开关 ----
        setOption(a, "protocol.enable_hls", props.isEnableHls() ? "1" : "0");
        setOption(a, "protocol.enable_ts", props.isEnableTs() ? "1" : "0");
        setOption(a, "protocol.enable_fmp4", props.isEnableFmp4() ? "1" : "0");
        setOption(a, "protocol.enable_rtmp", props.isEnableRtmp() ? "1" : "0");
        setOption(a, "protocol.enable_rtsp", props.isEnableRtsp() ? "1" : "0");
        setOption(a, "protocol.enable_audio", props.isEnableAudio() ? "1" : "0");
        setOption(a, "protocol.hls_demand", "0");
        setOption(a, "protocol.rtmp_demand", "0");
        setOption(a, "protocol.ts_demand", "0");
        setOption(a, "protocol.fmp4_demand", "0");

        // ---- [hls] 切片 ----
        setOption(a, "hls.hls_save_path", props.getHlsSavePath());
        setOption(a, "hls.segDur", String.valueOf(props.getHlsSegDur()));
        setOption(a, "hls.segNum", String.valueOf(props.getHlsSegNum()));
        setOption(a, "hls.segRetain", String.valueOf(props.getHlsSegRetain()));
        setOption(a, "hls.fastRegister", "1");

        // ---- [http] 静态目录（HLS 切片经此暴露给浏览器） ----
        setOption(a, "http.rootPath", props.getHlsSavePath());
        setOption(a, "http.dirMenu", "1");
        setOption(a, "http.allow_cross_domains", "1");

        // ---- [general] 流管理 ----
        setOption(a, "general.enableVhost", "0");
        setOption(a, "general.maxStreamWaitMS", String.valueOf(props.getMaxStreamWaitMs()));
        setOption(a, "general.streamNoneReaderDelayMS",
                String.valueOf(props.getStreamNoneReaderDelayMs()));
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
                // 依次通知全部监听者：单个监听者抛异常不能影响其它监听者
                for (StreamListener l : streamListeners) {
                    try {
                        if (regist != 0) {
                            l.onStreamOnline(app, stream);
                        } else {
                            l.onStreamOffline(app, stream);
                        }
                    } catch (Throwable t) {
                        log.warn("流事件监听者处理失败: app={} stream={} regist={}",
                                app, stream, regist, t);
                    }
                }
            } catch (Throwable t) {
                log.warn("处理 on_mk_media_changed 异常", t);
            }
        };

        // 无人观看回调（依赖 general.streamNoneReaderDelayMS 配置生效）：
        // 超过该时长仍无播放者时触发。平台据此发 BYE 释放设备带宽。
        // force=1 立即关闭该流，force=0 走正常关闭流程。
        ev.on_mk_media_no_reader = new com.aizuda.zlm4j.callback.IMKNoReaderCallBack() {
            @Override
            public void invoke(com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE ctx) {
                try {
                    String app = a.mk_media_source_get_app(ctx);
                    String stream = a.mk_media_source_get_stream(ctx);
                    String schema = a.mk_media_source_get_schema(ctx);
                    log.info("流无观看者: schema={} app={} stream={}", schema, app, stream);
                    NoReaderListener l = noReaderListener;
                    if (l != null) {
                        l.onNoReader(app, stream, schema);
                    }
                    if (forceCloseOnNoReader) {
                        a.mk_media_source_close(ctx, 1);
                        log.info("已按无人观看策略关闭流: {}:{}", app, stream);
                    }
                } catch (Throwable t) {
                    log.warn("处理无人观看回调异常", t);
                }
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
