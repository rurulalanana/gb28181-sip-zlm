package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_EVENTS;
import com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE;
import com.sun.jna.Callback;
import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** ZLM 默认虚拟主机名（{@code general.enableVhost=0} 时所有源都归于此）。 */
    static final String DEFAULT_VHOST = "__defaultVhost__";

    /**
     * ZLM 日志输出掩模：走 {@code on_mk_log} 回调（对应 C 侧 {@code LOG_CALLBACK = 1 << 2}）。
     *
     * <p>刻意不用 {@code LOG_CONSOLE}：直写控制台的日志 Java 侧拦不住，
     * 无法按级别映射到 SLF4J，也无法屏蔽已知无害的噪音（见 {@link #forwardZlmLog}）。
     */
    private static final int LOG_MASK_CALLBACK = 1 << 2;

    /**
     * ZLM 的字符串一律是 UTF-8（C++ 源码内的中文字面量、路径、日志都是）。
     *
     * <p>JNA 默认按<b>平台编码</b>解码 {@code const char*}，中文 Windows 上是 GBK，
     * 于是 ZLM 日志里的中文全变乱码（{@code 媒体注册} → {@code 濯掍綋娉ㄥ唽}）。
     * 必须在任何 JNA 调用发生前设置，故放在静态块；已显式设过则不覆盖。
     */
    static {
        if (System.getProperty("jna.encoding") == null) {
            System.setProperty("jna.encoding", "UTF-8");
        }
    }

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

    /**
     * 单端口模式的流名解析器：SSRC（十进制串）→ ZLM 流名。
     *
     * <p>单端口下 ZLM 用 SSRC 的十六进制串当流名，需要改回可读且唯一的
     * {@code <设备编码>_<通道编码>}。由 {@link RtpSessionManager} 装配，
     * 未装配时推流回调直接放行（不改名，退化为 SSRC 十六进制流名）。
     */
    private volatile java.util.function.Function<String, String> singlePortStreamNamer;

    /** 单端口模式下 ZLM 实际监听的端口（0 = 未启用）。 */
    private volatile int singlePort;

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
                    // 显式声明 UTF-8：ZLM 内部字符串均为 UTF-8，
                    // 否则中文（日志、含中文的路径）会被按 GBK 解码成乱码。
                    java.util.Map<String, Object> options = new java.util.HashMap<>();
                    options.put(com.sun.jna.Library.OPTION_STRING_ENCODING, "UTF-8");
                    api = Native.load("mk_api", ZLMApi.class, options);
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

    /**
     * 装配单端口模式的流名解析器（SSRC 十进制串 → ZLM 流名）。
     *
     * <p>必须在 {@link #start()} <b>之前</b>调用：单端口启动后设备随时可能推流，
     * 推流回调就要用到它。
     */
    public void setSinglePortStreamNamer(java.util.function.Function<String, String> namer) {
        this.singlePortStreamNamer = namer;
    }

    /** 单端口模式下 ZLM 实际监听的端口；未启用返回 0。 */
    public int getSinglePort() {
        return singlePort;
    }

    /** 是否运行在单端口收流模式。 */
    public boolean isSinglePortMode() {
        return singlePort > 0;
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
        // 线程数=1, 日志级别=1(DEBUG), 日志走 on_mk_log 回调而非控制台；
        // 不加载外部 ini（默认配置已适配 GB28181：ps_pt=96, rtp 超时 15s）。
        a.mk_env_init2(1, 1, LOG_MASK_CALLBACK, null, 0, 0, null, 0, null, null);

        events = buildEvents(a);
        // 必须在 mk_events_listen 之前固定回调线程：JNA 仅在回调首次交给原生侧时
        // 读一次线程初始化器（CallbackReference#getFunctionPointer），之后再设不生效
        pinCallbackThreads(events);
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

        // 单端口收流（可选）：所有设备推流到同一端口，ZLM 按 SSRC 分流。
        // 必须在 mk_events_listen 之后启动——推流回调要能立刻生效。
        if (props.isRtpSinglePort()) {
            int wanted = props.getRtpSinglePortNumber();
            int actual = unsign16(a.mk_rtp_server_start((short) wanted));
            if (actual == 0) {
                // 端口占用等：不静默降级，否则设备会推到没人收的端口上
                throw new IllegalStateException("单端口 RTP 服务启动失败: port=" + wanted
                        + "（端口被占用？改 gb28181.rtp-single-port-number）");
            }
            singlePort = actual;
            log.info("RTP 单端口收流已启用: port={}（所有设备共用一个端口，按 SSRC 分流）", actual);
        }

        started.set(true);
        log.info("ZLMediaKit 已启动: http={} rtmp={} rtsp={} rtc={} mediaIp={} app={} rtpMode={}",
                httpPort, rtmpPort, rtspPort, rtcPort, props.getMediaIp(), props.getApp(),
                singlePort > 0 ? ("single:" + singlePort) : "per-channel");
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

    /**
     * ZLM 等待流上线的最长时间（毫秒，{@code general.maxStreamWaitMS}）。
     *
     * <p>WebRTC 协商内部走 {@code MediaSource::findAsync}：源不存在时不会立刻失败，
     * 而是挂到 {@code kBroadcastMediaChanged} 上等流注册，最多等这么久才回调
     * 「流不存在」。因此调用方的等待超时必须大于它，否则只会得到 TimeoutException，
     * 拿不到 ZLM 的真实错误（stream not found）。
     */
    public int getMaxStreamWaitMs() {
        return props.getMaxStreamWaitMs();
    }

    // ==================== 播放地址 ====================

    /**
     * 生成某一路流的各协议播放地址。
     *
     * <p>必须同时给出 {@code app} 与 {@code stream}：ZLM 媒体源路径是
     * {@code /<app>/<stream>}，其中 {@code app} = 设备编码、{@code stream} = 通道编码。
     * 只传通道编码会 404，且跨设备同号通道会互相覆盖。
     *
     * @param app    应用名（= 设备编码）
     * @param stream 流名（= 通道编码）
     */
    public PlayUrls playUrls(String app, String stream) {
        return PlayUrls.of(props.getMediaIp(), httpPort, rtmpPort, rtspPort, rtcPort, app, stream);
    }

    /** 生成某一路流的各协议播放地址（{@link RtpSession} 版本，避免调用方拆字段）。 */
    public PlayUrls playUrls(RtpSession session) {
        return session == null ? null : playUrls(session.getZlmApp(), session.getStream());
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
            com.aizuda.zlm4j.callback.IMKRtpGetInfoCallBack cb =
                    (exist, localIp, localPort, peerIp, peerPort, ssrc) -> {
                        if (exist != 0 && peerIp != null && !peerIp.isEmpty()) {
                            future.complete(peerIp + ":" + (peerPort & 0xFFFF));
                        } else {
                            future.complete(null);
                        }
                    };
            // 先固定线程再交给原生侧（JNA 只在首次注册回调时读一次初始化器）
            pinCallbackThread(cb);
            api().mk_rtp_get_info(app, stream, cb);
            return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 关闭 ZLM 侧的媒体源（等价于 ZLM HTTP API 的 {@code close_streams?force=1}）。
     *
     * <p><b>为什么必须单独关</b>：{@code mk_rtp_server_release} 只销毁 RTP 收流服务器
     * （端口），不会注销已经注册的媒体源。残留的源会让下一次点播在
     * {@code MediaSource::regist()} 抛 {@code media source already existed:<schema>://…}；
     * 该异常发生在收流线程内、被 {@code RtpProcess} 转成 detach，于是设备每来一个
     * RTP 包就打一对 {@code onDetach} / {@code emitAllTrackReady} 日志（刷屏、CPU 升高），
     * 播放器不出画面，而平台侧会话已被摘除——表现为「停止直播停不下来」。
     *
     * <p>与 wvp-GB28181-pro 的 {@code closeStreams(mediaServer, app, stream)}
     * （RESTful {@code close_streams} + {@code force=1}）行为一致。
     *
     * <p>遍历时 ZLM 已把命中的源快照到强引用列表并释放了注册表锁，
     * 因此在回调里关闭源是安全的（不会边遍历边改容器）。
     *
     * @param app    应用名；{@code null} 或空串表示不限
     * @param stream 流名；{@code null} 或空串表示不限
     * @return 实际关闭的媒体源数量
     */
    public int closeMediaSource(String app, String stream) {
        ZLMApi a = api();
        AtomicInteger closed = new AtomicInteger();
        try {
            com.aizuda.zlm4j.callback.IMKSourceFindCallBack cb = (userData, source) -> {
                if (source == null) {
                    return;
                }
                try {
                    String schema = a.mk_media_source_get_schema(source);
                    String srcApp = a.mk_media_source_get_app(source);
                    String srcStream = a.mk_media_source_get_stream(source);
                    // force=1：有人观看也强制关闭，否则停播时播放器会把流钉住
                    if (a.mk_media_source_close(source, 1) != 0) {
                        closed.incrementAndGet();
                        log.info("已关闭 ZLM 媒体源: {}://{}/{}", schema, srcApp, srcStream);
                    }
                } catch (Throwable t) {
                    log.warn("关闭 ZLM 媒体源异常: {}", t.getMessage());
                }
            };
            pinCallbackThread(cb);
            a.mk_media_source_for_each(null, cb, "", "", app == null ? "" : app, stream == null ? "" : stream);
        } catch (Throwable t) {
            log.warn("遍历 ZLM 媒体源失败: app={} stream={}", app, stream, t);
        }
        return closed.get();
    }

    /**
     * 拼出 WebRTC 协商用的 {@code rtc://} 地址。
     *
     * <p>{@code mk_webrtc_get_answer_sdp} 的第 5 个参数是 {@code rtc://vhost/app/stream}，
     * ZLM 内部由 {@code play_plugin} 用 {@code MediaInfo info(args["url"])} 解析后
     * 再去 {@code MediaSource::findAsync} 找 <b>RTSP</b> 源——地址必须和已注册的
     * 媒体源完全一致，否则回调直接报 {@code stream not found}。
     *
     * <p>建流时已固定 {@code app = 设备编码}、{@code stream = 通道编码}
     * （见 {@link RtpSession#getSessionKey()}），命名确定，
     * 故此处直接拼即可，无需再反查 ZLM 的媒体源表。
     *
     * @param app    应用名（= 设备编码）
     * @param stream 流名（= 通道编码）
     * @return {@code rtc://vhost/app/stream}
     */
    public String resolveRtcUrl(String app, String stream) {
        String url = "rtc://" + DEFAULT_VHOST + "/" + app + "/" + stream;
        log.debug("WebRTC 协商地址: {}", url);
        return url;
    }

    /**
     * 是否为 GB28181 编码（20 位纯数字）。
     *
     * <p>用于区分「设备/通道编码」与「app/stream 组合标识」，
     * 也便于在接口层校验入参是否传成了流标识。
     */
    public static boolean isChannelId(String s) {
        return s != null && s.length() == 20 && s.chars().allMatch(Character::isDigit);
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

        // 推流鉴权 / 流名替换（仅单端口模式需要）。
        //
        // 单端口下 ZLM 用 SSRC 的十六进制串当流名（RtpSession.cpp: `_tuple.stream = printSSRC(_ssrc)`），
        // 通过本回调把流名改成 <设备编码>_<通道编码>，上游业务（播放地址、媒体源定位、
        // 录像索引）才能按可读名访问。
        //
        // 单端口模式下 `app` 由 ZLM 写死为 "rtp"（复用分支的 tuple 来自 server 级默认值），
        // stream_replace 只能改 stream，改不了 app —— 这是单端口的固有限制。
        //
        // 独占端口模式**不注册**本回调：不需要改名，且注册后 ZLM 会等 embedder 放行，
        // 一旦回调里抛异常就会让整个流起不来。
        if (props.isRtpSinglePort()) {
            ev.on_mk_media_publish = new com.aizuda.zlm4j.callback.IMKPublishCallBack() {
                @Override
                public void invoke(com.aizuda.zlm4j.structure.MK_MEDIA_INFO info,
                                   com.aizuda.zlm4j.structure.MK_PUBLISH_AUTH_INVOKER invoker,
                                   com.aizuda.zlm4j.structure.MK_SOCK_INFO sender) {
                    com.aizuda.zlm4j.structure.MK_INI ini = null;
                    try {
                        String ssrc = a.mk_media_info_get_stream(info);
                        java.util.function.Function<String, String> namer = singlePortStreamNamer;
                        String name = namer == null ? null : namer.apply(ssrc);
                        if (name == null || name.isBlank()) {
                            // 认不出 SSRC：放行但保留 ZLM 的默认流名（SSRC 十六进制），
                            // 至少能出画面，便于排查是哪个 SSRC 没登记
                            log.warn("单端口推流：SSRC 未匹配到会话，保留默认流名: ssrc={}", ssrc);
                            a.mk_publish_auth_invoker_do(invoker, null, 0, 0);
                            return;
                        }
                        ini = a.mk_ini_create();
                        a.mk_ini_set_option(ini, "stream_replace", name);
                        a.mk_publish_auth_invoker_do2(invoker, null, ini);
                        log.info("单端口推流已放行并改名: ssrc={} stream={}", ssrc, name);
                    } catch (Throwable t) {
                        // 回调内异常绝不能让流卡死：兜底放行
                        log.warn("单端口推流处理异常，已放行: {}", t.toString(), t);
                        try {
                            a.mk_publish_auth_invoker_do(invoker, null, 0, 0);
                        } catch (Throwable ignored) {
                            // 原生侧已释放 invoker，忽略
                        }
                    } finally {
                        if (ini != null) {
                            try {
                                a.mk_ini_release(ini);
                            } catch (Throwable ignored) {
                                // 释放失败不影响推流
                            }
                        }
                    }
                }
            };
        }

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

        // ZLM 日志回调：mk_env_init2 已把日志输出指向回调（不用控制台），
        // 这里统一转发到 SLF4J，便于按级别过滤与屏蔽已知无害噪音。
        ev.on_mk_log = this::forwardZlmLog;

        return ev;
    }

    /**
     * 把 ZLM 内部日志转发到 SLF4J。
     *
     * <p>由 {@code mk_env_init2} 的 {@code LOG_CALLBACK} 掩模驱动，按 ZLM 的
     * 级别映射：0 TRACE / 1 DEBUG / 2 INFO / 3 WARN / 4 ERROR。
     *
     * <p><b>已知无害噪音</b>：关闭媒体源时，ZLM 内部有个定时任务会抢在
     * {@code MediaSource} 析构之后调用 {@code getMuxer()}，此时它的监听者
     * （{@code weak_ptr}）已失效，于是抛 {@code MediaSourceEvent is empty}
     * 并被 {@code Timer} 捕获打成 ERROR。此刻该源及其全部协议实例都已注销完毕，
     * 不影响任何功能，故降级为 DEBUG。
     *
     * <p>回调运行在 ZLM 的异步日志线程上，只做日志转发，不得阻塞。
     */
    private void forwardZlmLog(int level, String file, int line, String function, String message) {
        String text = shortFileName(file) + ":" + line + " " + function + " | " + message;
        if (message != null && message.contains("MediaSourceEvent is empty")) {
            log.debug("ZLM[源已注销，定时器迟到] {}", text);
            return;
        }
        switch (level) {
            case 0:
                log.trace("ZLM {}", text);
                break;
            case 1:
                log.debug("ZLM {}", text);
                break;
            case 2:
                log.info("ZLM {}", text);
                break;
            case 3:
                log.warn("ZLM {}", text);
                break;
            default:
                log.error("ZLM {}", text);
                break;
        }
    }

    /** 取文件名部分（ZLM 传的是完整路径，日志里只留文件名更易读）。 */
    private static String shortFileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * 把回调线程固定为「常驻附着的守护线程」，消除 JNA 的
     * {@code JNA: could not detach thread} 噪音。
     *
     * <p>JNA 默认在每次回调结束后把原生线程从 JVM detach 掉。而 ZLM 的事件线程
     * 会连续投递多个回调（注销一路流就会按协议触发 5 次 {@code on_mk_media_changed}），
     * 反复 attach/detach 时 JNA 的 {@code DetachCurrentThread} 会失败，
     * 并直接 {@code fprintf(stderr)} 输出「JNA: could not detach thread」——
     * 那是 C 层直写，Java 侧拦不住，只能从源头避免：让它压根不去 detach。
     *
     * <p>{@code daemon=true} 是必须的：常驻附着意味着该线程不会自行 detach，
     * 非守护线程会让 JVM 无法退出。
     *
     * <p><b>时序要求</b>：必须在回调<b>首次</b>传给原生侧之前调用。
     * JNA 仅在创建 {@code CallbackReference} 时检查一次线程初始化器
     * （见 {@code CallbackReference#getFunctionPointer}），之后再设置不生效。
     */
    public static void pinCallbackThread(Callback cb) {
        if (cb == null) {
            return;
        }
        try {
            Native.setCallbackThreadInitializer(cb,
                    new CallbackThreadInitializer(true, false, "zlm-callback"));
        } catch (Throwable t) {
            log.warn("固定 JNA 回调线程失败: {}", t.getMessage());
        }
    }

    /**
     * 把结构体里所有非空回调字段的线程都固定下来。
     *
     * <p>用反射遍历而不是逐个列举，是为了以后给 {@link MK_EVENTS} 增加回调时
     * 不会漏掉固定步骤（漏掉的那个回调会重新出现 detach 噪音）。
     */
    private static void pinCallbackThreads(Object holder) {
        for (Field f : holder.getClass().getFields()) {
            try {
                Object value = f.get(holder);
                if (value instanceof Callback) {
                    pinCallbackThread((Callback) value);
                }
            } catch (Throwable ignore) {
                // 反射失败不影响功能，只是该回调可能仍有 detach 噪音
            }
        }
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
