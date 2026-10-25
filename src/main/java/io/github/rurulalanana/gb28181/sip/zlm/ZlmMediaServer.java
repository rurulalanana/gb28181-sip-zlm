package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_EVENTS;
import com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE;
import com.sun.jna.Callback;
import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    /** ZLM 日志输出掩模：直写控制台（C 侧 {@code LOG_CONSOLE = 1 << 0}）。默认值。 */
    private static final int LOG_CONSOLE = 1 << 0;

    /**
     * ZLM 日志输出掩模：走 {@code on_mk_log} 回调（C 侧 {@code LOG_CALLBACK = 1 << 2}）。
     *
     * <p><b>默认不用它</b>（见 {@link MediaServerProperties#isZlmLogToSlf4j()}）：这条通道
     * 会让嵌入了 ZLM 的进程<b>无法正常退出</b>。原因是 ZLM 原生库的静态析构
     * （{@code ~Logger} → {@code ~AsyncLogWriter} → {@code std::thread::join()}）会向
     * 该通道写最后一条日志，而此时 JVM 已在退出途中，于是：
     * <ul>
     *   <li>日志线程卡在 JNA 回调的 JNI 里（{@code JavaThread::block_if_vm_exited}），
     *       与退出线程互等 → 进程永不退出（{@code mvn test} 跑不完、优雅停机卡死）；</li>
     *   <li>即便绕开死锁，事件广播也会打到已析构的 {@code NoticeCenter} 上，
     *       抛 C++ 异常 {@code recursive_mutex lock failed} → SIGABRT（退出码 134）。</li>
     * </ul>
     * 打开它只是为了让 ZLM 日志按级别映射进 SLF4J，代价由调用方自行承担。
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

    /**
     * 退出钩子是否已注册（进程级只需一次）。
     *
     * <p>钩子只做一件事：清空 ZLM 事件回调，理由见 {@link #stop()}。
     * 有了它，调用方即使忘了 {@code stop()}，进程退出也不会卡在
     * ZLM 静态析构的线程 join 上。
     */
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean(false);

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
     * ZLM 回调之后的后续处理执行器（单线程、保序、守护）。
     *
     * <p><b>为什么必须有它</b>：ZLM 的回调是在它自己的 event poller 线程上
     * 同步调进来的（JNA 把该原生线程 attach 成 Java 线程 {@code zlm-callback}）。
     * 只要回调函数不返回，那个 poller 就<b>什么都不做</b> —— 不收 RTP、
     * 不跑转协议、不处理定时任务。而业务侧的监听者会做这些重活：
     *
     * <ul>
     *   <li>{@code RecordingService.onStreamOnline → mk_recorder_start}（原生调用）；</li>
     *   <li>{@code RecordingService.onStreamOffline → mk_recorder_stop}
     *       + <b>最多等 10 秒</b>等 MP4 收尾（{@code stopRecording} 里的轮询）；</li>
     *   <li>Spring 侧的落库、状态机、归档准备。</li>
     * </ul>
     *
     * <p>把这些都放在 poller 线程上做，等于让 ZLM 的收流主循环卡住：
     * RTP 包积压 → {@code RtpProcess timeout} → 触发流下线 → 又进一轮回调，
     * 雪崩。实测崩溃线程正是 {@code zlm-callback}
     * （hs_err：{@code SIGSEGV in getCodecByMpegId}）。
     *
     * <p><b>单线程而非线程池</b>：上线/下线事件的<b>顺序</b>有语义
     * （先上线再下线，否则会话状态机错乱）。单线程天然保序。
     *
     * <p><b>绝不能在回调线程里重入 ZLM 的销毁类 API</b>：ZLM 销毁
     * {@code MultiMediaSourceMuxer} 时会遍历内部列表，此时重入会把它
     * 从正在执行的栈上拆掉，表现为 {@code MediaSourceEvent::NotImplemented}
     * 逃逸出析构函数 → {@code std::terminate()} → SIGABRT，或直接 SIGSEGV。
     * 本执行器就是那道隔离墙。
     */
    private volatile java.util.concurrent.ExecutorService eventExecutor;

    /** MP4 录制分片落盘监听者。 */
    private volatile RecordListener recordListener;

    /**
     * 单端口模式的流名解析器：SSRC（十进制串）→ ZLM 流名。
     *
     * <p>单端口下 ZLM 用 SSRC 的十六进制串当流名，需要改回可读且唯一的
     * {@code <设备编码>_<通道编码>}。由 {@link RtpSessionManager} 装配，
     * 未装配时推流回调直接放行（不改名，退化为 SSRC 十六进制流名）。
     */
    private volatile java.util.function.Function<String, String> singlePortStreamNamer;

    /**
     * 孤儿流认领器：SSRC（十六进制串）→ 可读流名；认不出返回 {@code null}。
     *
     * <p>仅在 {@link #singlePortStreamNamer} 解析失败时兜底调用。用于处理
     * 「设备收 BYE 回 200 OK 却不停止推流」导致会话已拆、流仍在推的场景。
     */
    private volatile java.util.function.Function<String, String> orphanReclaimer;

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

    /**
     * 注册 MP4 录制分片监听者。
     *
     * <p>ZLM 在每段 MP4 落盘后广播一次，携带<b>真实文件路径与大小</b>。
     * 这是获取录制产物的唯一可靠途径：{@code mk_recorder_start} 的
     * {@code customized_path} 只是<b>目录</b>，文件名与日期子目录由 ZLM
     * 自行生成（{@code <dir>/<recordApp>/<app>/<stream>/<date>/<date>-<HH-MM-SS>-<idx>.mp4}），
     * 调用方无法事先拼出。
     */
    public void setRecordListener(RecordListener listener) {
        this.recordListener = listener;
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

    /**
     * 装配孤儿流认领器（SSRC 十六进制串 → 可读流名，认不出返回 {@code null}）。
     *
     * <p>用途：部分下级平台（实测 LiveGBS）收 BYE 回 200 OK 却不停止推流。
     * 此时平台侧会话已拆、SSRC 映射已清，设备推来的包认不出归属，
     * 只能退化成十六进制流名的孤儿流。装了这个认领器后，推流回调会在
     * 流名解析失败时再试一次「按 SSRC 墓碑重建会话」，把流重新纳管。
     */
    public void setOrphanReclaimer(java.util.function.Function<String, String> reclaimer) {
        this.orphanReclaimer = reclaimer;
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

    /** MP4 录制分片落盘监听者。回调来自 ZLM 原生线程，实现需线程安全且不得阻塞。 */
    public interface RecordListener {
        /**
         * 一段 MP4 录制分片已生成（临时文件已改名为正式文件）。
         *
         * @param app      源流应用名
         * @param stream   源流名
         * @param filePath 录像文件绝对路径（ZLM 生成，已定稿）
         * @param fileName 文件名
         * @param fileSize 文件大小（字节）
         * @param timeLen  该分片时长（秒）
         */
        void onRecordMp4(String app, String stream, String filePath,
                         String fileName, long fileSize, float timeLen);
    }

    // ==================== 生命周期 ====================

    /** 初始化 ZLM 环境并按配置启动各协议服务。幂等。 */
    public synchronized void start() {
        if (started.get()) {
            return;
        }
        // 回调后续执行器：必须在 buildEvents() 之前建好，回调闭包要捕获它。
        // 单线程 + 守护：开机即存在，进程退出时不会拖住 JVM。
        eventExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "zlm-event-worker");
            t.setDaemon(true);
            return t;
        });
        ZLMApi a = api();
        // 负载类型需在 env 初始化前写入全局配置（国标默认 PS=96）
        applyPayloadTypes(a);
        applyProtocolOptions(a);
        // 线程数=1, 日志级别=1(DEBUG)；不加载外部 ini（默认配置已适配 GB28181：
        // ps_pt=96, rtp 超时 15s）。
        // 日志掩模：默认 LOG_CONSOLE（ZLM 直写控制台，进程可正常退出）；
        // 只有显式开启 zlmLogToSlf4j 才走 on_mk_log 回调通道（后果见 LOG_MASK_CALLBACK 说明）。
        int logMask = props.isZlmLogToSlf4j() ? LOG_MASK_CALLBACK : LOG_CONSOLE;
        a.mk_env_init2(1, 1, logMask, null, 0, 0, null, 0, null, null);

        events = buildEvents(a);
        // 必须在 mk_events_listen 之前固定回调线程：JNA 仅在回调首次交给原生侧时
        // 读一次线程初始化器（CallbackReference#getFunctionPointer），之后再设不生效
        pinCallbackThreads(events);
        a.mk_events_listen(events);
        installShutdownHook();

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

    /**
     * 注册 JVM 退出钩子（幂等）：退出前清空 ZLM 事件回调，避免退出死锁。
     *
     * <p>只清回调、不碰服务，避免与调用方（Spring 的 destroyMethod 等）的停机顺序打架。
     * 钩子里刻意容忍一切异常：退出阶段抛异常会让 JVM 退出码异常。
     */
    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    ZLMApi a = api;
                    if (a != null) {
                        // 必须用局部变量：退出时 api() 若触发静态初始化会再加载一次原生库
                        a.mk_events_listen(null);
                    }
                } catch (Throwable ignore) {
                    // 退出阶段不容错会污染退出码
                }
            }, "gb28181-zlm-shutdown"));
        } catch (Throwable t) {
            log.warn("注册 ZLM 退出钩子失败（不影响启动）", t);
        }
    }

    /**
     * 把回调后续处理投递到 {@link #eventExecutor}，让 ZLM 的 poller 线程立刻返回。
     *
     * <p>已停机（执行器关闭）时静默丢弃：停机后流上下线事件已无业务意义，
     * 而此时打日志只会污染退出过程。
     *
     * <p>本方法自身绝不可抛异常 —— 它在 ZLM 回调栈上执行，
     * 异常逃逸到原生侧同样是未定义行为。
     */
    private void dispatch(Runnable task) {
        java.util.concurrent.ExecutorService ex = eventExecutor;
        if (ex == null || ex.isShutdown()) {
            return;
        }
        try {
            ex.execute(task);
        } catch (Throwable t) {
            // 执行器正在关闭：任务丢弃即可，不要回抛给原生回调
            log.debug("事件执行器已关闭，丢弃任务: {}", t.toString());
        }
    }

    /** 停止所有服务。幂等。 */
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        try {
            api().mk_stop_all_server();
            // 清空 ZLM 的事件回调（传 null 会让 ZLM 把内部 s_events 整体清零）。
            //
            // 原因：进程退出时 ZLM 原生库的静态析构会销毁自己的对象并广播最后几条事件，
            // 此时任何「原生 → JNI → Java」的回调都可能与退出线程互等
            // （日志线程卡在 JavaThread::block_if_vm_exited，退出线程卡在
            // AsyncLogWriter::~AsyncLogWriter 的 std::thread::join），
            // 表现为 mvn test 跑不完、生产优雅停机卡死；绕开死锁还可能打到已析构的
            // NoticeCenter 上并 SIGABRT。先清零回调，退出期就不会再进 JNI。
            api().mk_events_listen(null);
            log.info("ZLMediaKit 已停止");
        } catch (Throwable t) {
            log.warn("停止 ZLMediaKit 异常", t);
        } finally {
            httpPort = rtmpPort = rtspPort = rtcPort = 0;
            // 回调已清零，不会再有新任务进来：把已排队的处理跑完再关。
            // 队列里通常只有几条上下线事件，5 秒足够；超时则丢弃，
            // 留给下次启动时的录像对账（RecordService#reconcileAutoRecordings）。
            shutdownEventExecutor();
        }
    }

    /** 关闭回调执行器：先等在跑的任务结束，超时则中断。 */
    private void shutdownEventExecutor() {
        java.util.concurrent.ExecutorService ex = eventExecutor;
        eventExecutor = null;
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
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
    /**
     * 关闭匹配的 ZLM 媒体源。
     *
     * <p><b>每个流只关一个协议源</b>。同一个流在 ZLM 里注册了 5 个协议源
     * （fmp4/rtmp/rtsp/ts/hls），它们共享同一个 {@code MultiMediaSourceMuxer}；
     * 关掉其中一个会连带销毁整个 muxer 与其余源。此时若继续关闭剩下的源，
     * 拿到的是<b>已销毁的悬垂句柄</b>，ZLM 内部抛 C++ 异常：
     * <pre>
     * terminate called after throwing an instance of 'std::runtime_error'
     *   what():  mediakit::RtmpMediaSource::getOwnerPoller failed: rtmp://...
     * Aborted (core dumped)
     * </pre>
     * 实测线上因这个原因崩溃 16 次（每次中断约 4 分钟），
     * 触发时机正是「停止直播 / 会话被顶替」。
     *
     * <p><b>为什么还要分两阶段</b>：在 {@code mk_media_source_for_each} 的
     * 回调里直接关源，等于边遍历 ZLM 注册表边改它。虽然 ZLM 会先做快照，
     * 但关闭动作本身会触发内部事件与引用计数变更，仍然不安全。
     * 先收集完句柄、遍历结束后再关，彻底避开这个窗口。
     *
     * @param app    应用名；{@code null} 或空串表示不限
     * @param stream 流名；{@code null} 或空串表示不限
     * @return 实际关闭的媒体源数量
     */
    public int closeMediaSource(String app, String stream) {
        ZLMApi a = api();
        // 阶段一：只读遍历，按 app/stream 去重收集（不修改任何状态）
        java.util.Map<String, com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE> targets =
                new java.util.LinkedHashMap<>();
        try {
            com.aizuda.zlm4j.callback.IMKSourceFindCallBack cb = (userData, source) -> {
                if (source == null) {
                    return;
                }
                try {
                    String srcApp = a.mk_media_source_get_app(source);
                    String srcStream = a.mk_media_source_get_stream(source);
                    // 同一个流的多个协议源只保留一个：关一个即可连带销毁其余
                    targets.putIfAbsent(srcApp + "/" + srcStream, source);
                } catch (Throwable t) {
                    log.debug("收集媒体源失败: {}", t.toString());
                }
            };
            pinCallbackThread(cb);
            a.mk_media_source_for_each(null, cb, "", "",
                    app == null ? "" : app, stream == null ? "" : stream);
        } catch (Throwable t) {
            log.warn("遍历 ZLM 媒体源失败: app={} stream={}", app, stream, t);
            return 0;
        }
        if (targets.isEmpty()) {
            return 0;
        }
        // 阶段二：遍历已结束，每个流只关一次
        AtomicInteger closed = new AtomicInteger();
        for (java.util.Map.Entry<String, com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE> e
                : targets.entrySet()) {
            try {
                String schema = a.mk_media_source_get_schema(e.getValue());
                // force=1：有人观看也强制关闭，否则停播时播放器会把流钉住
                if (a.mk_media_source_close(e.getValue(), 1) != 0) {
                    closed.incrementAndGet();
                    log.info("已关闭 ZLM 媒体源: {}://{}", schema, e.getKey());
                }
            } catch (Throwable t) {
                log.warn("关闭 ZLM 媒体源异常: {} {}", e.getKey(), t.getMessage());
            }
        }
        return closed.get();
    }

    // ==================== 级联上行推流 ====================

    /**
     * 已提交给 ZLM、但尚未释放的 INI 参数。
     *
     * <p><b>为什么需要延迟释放</b>：{@code mk_media_source_start_send_rtp3} 内部是
     * {@code (*src)([=](MediaSource &sender){ ... })} —— 把任务<b>异步</b>投递到
     * ZLM 的 event poller，而 {@code ini} 是按<b>引用</b>传进去的。
     * 一旦在 API 返回后立刻 {@code mk_ini_release}，异步任务真正读取时内存已释放，
     * 读到的 {@code udp} 键是垃圾值，{@code RtpSender} 的传输类型变回无效，
     * 于是 {@code startSend} 断言失败、一个包都发不出去。
     *
     * <p>实测时序证据：我们的「推流已启动」日志与 ZLM 的断言只差 <b>3 毫秒</b> ——
     * 正是异步任务开始执行、读到已释放内存的时刻。
     *
     * <p>代价：每次级联点播保留一个几十字节的 INI 对象 60 秒。级联点播是低频操作
     * （一天几十次），完全可接受；比起「静默发不出流」这种排查成本极低的故障，
     * 这个取舍很划算。
     */
    private final java.util.Queue<PendingIni> pendingInis =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 延迟释放窗口：足够覆盖 ZLM 异步任务与首帧发送。 */
    private static final long INI_RELEASE_DELAY_MS = 60_000L;

    /** 一个待释放的 INI。 */
    private static final class PendingIni {
        private final com.aizuda.zlm4j.structure.MK_INI ini;
        private final long createdAt = System.currentTimeMillis();

        private PendingIni(com.aizuda.zlm4j.structure.MK_INI ini) {
            this.ini = ini;
        }
    }

    /**
     * dump INI 内容（诊断用）。
     *
     * <p>返回的字符串由 ZLM 分配，用完需按 C 侧约定释放；这里只读不释放 ——
     * 泄漏量极小（每次级联点播一次），换来的是「能看清 ZLM 到底读到了什么」，
     * 而这个信息在别处拿不到。
     */
    private static String dumpIni(com.aizuda.zlm4j.structure.MK_INI ini) {
        if (ini == null) {
            return "(null)";
        }
        try {
            com.sun.jna.Pointer p = api().mk_ini_dump_string(ini);
            if (p == null) {
                return "(dump=null)";
            }
            String s = p.getString(0, "UTF-8");
            // 日志里换行会打断阅读，改成可见分隔
            return s == null ? "(empty)" : s.replace("\n", " | ").trim();
        } catch (Throwable t) {
            return "(dump失败:" + t.getMessage() + ")";
        }
    }

    /**
     * 按 app/stream 查找已存在的媒体源。
     *
     * <p><b>为什么用 {@code mk_media_source_for_each} 而不是
     * {@code mk_media_source_find2}</b>：实测后者在嵌入式 ZLM 上即使流确实存在
     * （同一时刻 {@code for_each} 能遍历到、且设备已在推流）也返回 {@code null}，
     * 它的参数语义与预期不符。而 {@code for_each} 在本项目的
     * {@link #closeMediaSource} 里已被验证可用，行为可预测。
     *
     * @return 媒体源句柄；不存在返回 {@code null}
     */
    private MK_MEDIA_SOURCE findMediaSource(String app, String stream) {
        if (app == null || app.isEmpty() || stream == null || stream.isEmpty()) {
            return null;
        }
        java.util.concurrent.atomic.AtomicReference<MK_MEDIA_SOURCE> found =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            com.aizuda.zlm4j.callback.IMKSourceFindCallBack cb = (userData, source) -> {
                if (source == null || found.get() != null) {
                    return;
                }
                try {
                    String srcApp = api().mk_media_source_get_app(source);
                    String srcStream = api().mk_media_source_get_stream(source);
                    // 同名流的多个协议源取第一个即可：推流是流级别的，与协议无关
                    if (stream.equals(srcStream) && (srcApp == null || srcApp.equals(app))) {
                        found.set(source);
                    }
                } catch (Throwable t) {
                    log.debug("读取媒体源标识失败: {}", t.toString());
                }
            };
            pinCallbackThread(cb);
            api().mk_media_source_for_each(null, cb, "", "", app, stream);
        } catch (Throwable t) {
            log.debug("遍历媒体源失败: {}", t.toString());
        }
        return found.get();
    }

    /** 释放已超过延迟窗口的 INI。 */
    private void releaseExpiredInis() {
        long now = System.currentTimeMillis();
        while (true) {
            PendingIni head = pendingInis.peek();
            if (head == null || now - head.createdAt < INI_RELEASE_DELAY_MS) {
                return;
            }
            pendingInis.poll();
            try {
                api().mk_ini_release(head.ini);
            } catch (Throwable t) {
                log.debug("释放 INI 失败（忽略）: {}", t.toString());
            }
        }
    }

    /**
     * 把<b>已有的</b>媒体源用 PS/RTP 推给上级平台（国标级联上行点播）。
     *
     * <p><b>为什么不能沿用 {@code CascadeForwarder} 的写法</b>：那里用的是
     * {@code mk_media_create} + {@code mk_media_start_send_rtp} —— 参数是一个
     * <b>我们自己创建的、需要喂帧的</b> {@code MK_MEDIA}。级联上行点播时，
     * 流已经存在（设备正在往本级推），我们没有帧可以喂，所以那条路发不出任何数据，
     * 表现为上级报「none rtp data」而我们这边一片正常。
     *
     * <p>正确做法是用 {@code mk_media_source_find2} 找到<b>已存在</b>的媒体源，
     * 再 {@code mk_media_source_start_send_rtp} 直接转推它的 RTP。
     *
     * @param app     媒体源 app（单端口模式下是 {@code rtp}）
     * @param stream  媒体源 stream（{@code <设备编码>_<通道编码>}）
     * @param dstIp   上级平台的收流地址（来自其 INVITE 的 SDP {@code c=}）
     * @param dstPort 上级平台的收流端口（来自 SDP {@code m=}）
     * @param ssrc    上级 SDP {@code y=} 里指定的 SSRC；空则用源自身的
     * @param waitMs  等待媒体源出现的时长（设备刚被邀请，推流有几百毫秒到数秒延迟）
     * @return 是否成功发起
     */
    public boolean startSendRtpToSource(String app, String stream, String dstIp, int dstPort,
                                        String ssrc, long waitMs) {
        if (dstIp == null || dstIp.trim().isEmpty() || dstPort <= 0) {
            log.warn("级联推流目标地址无效: {}:{}", dstIp, dstPort);
            return false;
        }
        String useApp = app == null || app.trim().isEmpty() ? props.getApp() : app;
        /*
         * 只做一次快速探测，不轮询等待。
         *
         * 推流本身已确定不可用（原因见下方注释），再等 20 秒等媒体源出现毫无意义 ——
         * 只会让上级多等 20 秒才拿到失败结果。这里探一次只是为了在日志里
         * 区分「源都没起来」和「源在但推不出去」两种情况，便于排查。
         */
        MK_MEDIA_SOURCE source = findMediaSource(useApp, stream);
        if (source == null) {
            log.warn("级联推流失败：媒体源不存在 app={} stream={}", useApp, stream);
            return false;
        }
        /*
         * 【重要：此功能在当前内嵌 ZLM 上不可用，原因已定位到不可修复的层面】
         *
         * 调用 mk_media_source_start_send_rtp* 后 ZLM 原生层报：
         *   Assertion failed: (0, invalid con type), function startSend,
         *   file src/Rtp/RtpSender.cpp, line 214
         * RtpSender 的传输类型（UDP/TCP）保持无效值，startSend 直接断言，
         * 一个 RTP 包都不会发出。
         *
         * 已系统排除的可能（附证据）：
         *   1. 无 ini 的 mk_media_source_start_send_rtp  —— 同样断言
         *   2. ini 键 udp=1                              —— 同样断言
         *   3. ini 键 is_udp=1（default/create 两种 ini） —— mk_ini_dump_string
         *      证实键确实写进了 ZLM 的 mINI 对象，但 RtpSender 不读它
         *   4. dst_url 带 udp:// 前缀                    —— 同样断言
         *   5. pt=96 / pt=-1、ssrc=上级值 / null          —— 同样断言
         *   6. 立即释放 ini（异步读取到已释放内存）        —— 延迟释放后仍断言
         *   7. 复用被缓存的旧 RtpSender                  —— 先 stop 再 start 仍断言
         *   8. rtp3 / rtp4 两个变体                      —— 同样断言
         *
         * 结论：zlm4j 1.13.0 把 ini 传给 ZLM 原生库的这段链路上，
         * 传输类型没能传到 RtpSender 构造函数。这是封装层与原生层的兼容问题，
         * Java 侧没有其它入口可以绕过（C API 不暴露 setConType）。
         *
         * 因此这里**如实地返回失败**，而不是像早期版本那样打一条
         * 「推流已启动」让运维误以为成功（实际上级永远收不到流）。
         * 级联的注册 / 心跳 / 目录查询应答不受影响，它们工作正常。
         */
        log.warn("级联上行推流不可用：内嵌 ZLM（zlm4j 1.13.0）的 "
                + "mk_media_source_start_send_rtp* 无法指定传输类型，"
                + "原生层断言 invalid con type，RTP 无法发出。"
                + "级联注册与目录同步不受影响，上级可见通道但无法播放。"
                + "目标 {}:{} 源 {}:{}", dstIp, dstPort, useApp, stream);
        return false;
    }

    /**
     * 停止某个媒体源上的级联推流。
     *
     * <p>上级发 BYE 后必须调用，否则 ZLM 会一直往那个地址发 RTP ——
     * 对上级来说这是「未知来源的垃圾流」，长期跑会占带宽，且部分平台会因此
     * 判定 SSRC 冲突而拒绝后续正常的点播。
     */
    public void stopSendRtpToSource(String app, String stream) {
        String useApp = app == null || app.trim().isEmpty() ? props.getApp() : app;
        try {
            MK_MEDIA_SOURCE source = findMediaSource(useApp, stream);
            if (source == null) {
                return;
            }
            api().mk_media_source_stop_send_rtp(source);
            log.info("级联上行推流已停止: {}:{}", useApp, stream);
        } catch (Throwable t) {
            log.debug("停止级联推流异常: {}", t.toString());
        }
    }

    /**
     * 拼出 WebRTC 协商用的 {@code rtc://} 地址。
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
     * 统计「正在播放」的<b>观众总数</b>（含录制器），可按流名前缀过滤。
     *
     * <p>用于概览页的「播放」计数：录像点播走的是 MP4 解复用源
     * （{@code mk_load_mp4_file}），<b>不产生 RtpSession</b>，
     * 因此只遍历会话表永远统计不到它，必须问 ZLM 本身。
     *
     * <p><b>为什么用主动查询而不是 {@code on_mk_media_play} 回调</b>：
     * 那个回调本质是<b>播放鉴权</b>钩子 —— 一旦注册，ZLM 会挂起播放等待
     * embedder 调 {@code mk_auth_invoker_do} 放行，任何异常或遗漏都会让
     * 整个平台放不出画面（zlm4j 注释里对此有明确警告）。
     * 本平台不需要播放鉴权，为统计一个数字去承担「全平台播不了」的风险
     * 不划算。主动查询是只读的，无此副作用。
     *
     * <p><b>两个 reader API 的区别（实测数据）</b>：一路流会注册
     * fmp4/rtmp/rtsp/ts/hls 多个协议源，而
     * <ul>
     *   <li>{@code mk_media_source_get_reader_count} 是<b>该协议自己的</b>
     *       读者数 —— 只有实际被使用的那一个非零
     *       （用 FLV 播放 2 路时只有 rtmp=2，其余全 0）；</li>
     *   <li>{@code mk_media_source_get_total_reader_count} 是<b>整路流的</b>
     *       总读者数 —— 5 个源返回<b>同一个值</b>（都用 FLV 播时全为 2）。</li>
     * </ul>
     * 因此必须按 {@code app/stream} 归并后取一个 total：
     * 把 5 个源相加会重复 5 倍；只看「是否 &gt; 0」会把观众数压成 0/1，
     * 表现为「开两个播放窗口只显示 1」。
     *
     * <p><b>「播放」包含录制器</b>：ZLM 把 MP4 录制器也算作一个 reader
     * （{@code protocol.mp4_as_player=1}，见 {@link #applyOptions}），
     * 所以「只开录像、无人观看」的通道也会计为 1。这是刻意的：
     * 录像本身就在消费这路流，平台按「有多少消费者」展示更符合运维直觉。
     *
     * @param streamPrefix 流名前缀（如 {@code record_}）；{@code null} 或空串表示不限
     * @return 观众总数（含录制器）；查询失败返回 0
     */
    public int playingSourceCount(String streamPrefix) {
        // app/stream -> 该路流的总读者数。
        // 5 个协议源会写同一个键，取最大值（理论上相等；取 max 可避免
        // 并发下恰好读到某个源瞬时的 0）
        Map<String, Integer> byStream = new ConcurrentHashMap<>();
        try {
            com.aizuda.zlm4j.callback.IMKSourceFindCallBack cb = (userData, source) -> {
                if (source == null) {
                    return;
                }
                try {
                    String app = api().mk_media_source_get_app(source);
                    String stream = api().mk_media_source_get_stream(source);
                    // 先过滤流名再查读者数：遍历回调在原生侧执行，少一次 JNA 调用更省
                    if (streamPrefix != null && !streamPrefix.isEmpty()
                            && (stream == null || !stream.startsWith(streamPrefix))) {
                        return;
                    }
                    int readers = api().mk_media_source_get_total_reader_count(source);
                    if (readers <= 0) {
                        return;
                    }
                    byStream.merge(app + "/" + stream, readers, Math::max);
                } catch (Throwable t) {
                    // 单个源失败不影响其余源
                    log.debug("统计媒体源失败: {}", t.toString());
                }
            };
            pinCallbackThread(cb);
            // 四个过滤参数依次是 schema / vhost / app / stream，空串表示不限
            api().mk_media_source_for_each(null, cb, "", "", "", "");
        } catch (Throwable t) {
            log.debug("遍历媒体源失败: {}", t.toString());
        }
        int total = 0;
        for (Integer n : byStream.values()) {
            total += n;
        }
        return total;
    }

    /**
     * ZLM 的无人观看判定时长（毫秒，{@code general.streamNoneReaderDelayMS}）。
     *
     * <p>供平台侧做空闲流巡检时对齐宽限期：刚建立的会话读者数天然是 0
     * （还没人来得及播放），若立刻按「无观众」关闭就会把刚点播的流误杀。
     * 用与 ZLM 回调相同的阈值，语义一致。
     *
     * <p><b>返回的是启动配置值</b>，不反映 {@link #applyOption} 的热更新。
     * 需要「当前生效值」的调用方（如平台侧空闲流巡检）应自行从运行时配置读取 ——
     * 库层不持有平台侧的配置来源，硬塞一份副本只会让两边再次分叉。
     *
     * @return 判定时长（毫秒）
     */
    public int noneReaderDelayMs() {
        return props.getStreamNoneReaderDelayMs();
    }

    /**
     * 某路流当前的观众总数（含录制器）。
     *
     * <p><b>为什么需要主动查询</b>：ZLM 的 {@code on_mk_media_no_reader} 回调
     * <b>每个媒体源只触发一次</b>（内部 {@code _none_reader} 标志位），
     * 且只在「读者数由非零降为零」的那次检查中触发。因此
     * <ul>
     *   <li>平台重启后，设备仍在推流但平台无会话 —— 回调可能在会话重建
     *       <b>之前</b>就已触发过，之后永不重来（实测差 2 秒）；
     *   <li>重新纳管（认领孤儿流）时流早已注册，回调不会补发。</li>
     * </ul>
     * 于是这些流「既不会被无人观看关掉，也永远不会再触发回调」。
     * 靠回调无法覆盖，必须能主动问「这路流现在还有观众吗」。
     *
     * <p>按 {@code app/stream} 归并取 {@code total_reader_count} 的最大值：
     * 一路流会注册 fmp4/rtmp/rtsp/ts/hls 多个协议源，各自返回同一个总数
     * （见 {@link #playingSourceCount(String)} 的实测说明），
     * 相加会重复计数，取 max 才是这路流的真实观众数。
     *
     * <p>不传 {@code streamPrefix}：这里要查具体某一路，按完整 app/stream 匹配。
     *
     * @param app    ZLM 应用名
     * @param stream ZLM 流名
     * @return 观众总数（含录制器）；流不存在或查询失败返回 {@code 0}
     */
    public int readerCount(String app, String stream) {
        if (StringUtils.isBlank(app) || StringUtils.isBlank(stream)) {
            return 0;
        }
        java.util.concurrent.atomic.AtomicInteger max = new java.util.concurrent.atomic.AtomicInteger();
        try {
            com.aizuda.zlm4j.callback.IMKSourceFindCallBack cb = (userData, source) -> {
                if (source == null) {
                    return;
                }
                try {
                    // 先比对 app/stream 再查读者数：遍历回调在原生侧执行，
                    // 少一次 JNA 调用更省
                    if (!app.equals(api().mk_media_source_get_app(source))
                            || !stream.equals(api().mk_media_source_get_stream(source))) {
                        return;
                    }
                    int n = api().mk_media_source_get_total_reader_count(source);
                    max.accumulateAndGet(n, Math::max);
                } catch (Throwable t) {
                    log.debug("查询媒体源读者数失败: {}", t.toString());
                }
            };
            pinCallbackThread(cb);
            api().mk_media_source_for_each(null, cb, "", "", "", "");
        } catch (Throwable t) {
            log.debug("遍历媒体源失败: {}", t.toString());
        }
        return max.get();
    }

    /**
     * 列出所有「流名仍是 SSRC 十六进制串」的媒体源（= 未被平台纳管的孤儿流）。
     *
     * <p><b>为什么靠流名判定</b>：单端口模式下，被正常纳管的流其流名会被推流回调
     * 通过 {@code stream_replace} 改成 {@code <设备编码>_<通道编码>}；
     * 而认不出 SSRC 归属时回调只能放行并保留 ZLM 默认名（SSRC 的大写十六进制）。
     * 所以「流名是 8 位十六进制」就等价于「这路流没被纳管」。
     *
     * <p>典型成因：设备（下级平台）不理会 BYE 持续推流，而平台重启后
     * SSRC 索引为空、或恢复映射的时机晚于设备推流（{@code publish} 回调
     * 只在流首次注册时触发一次，错过就永久错过）。
     *
     * @return 孤儿流的 ZLM 流名（SSRC 十六进制串）列表
     */
    public List<String> orphanStreamNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try {
            com.aizuda.zlm4j.callback.IMKSourceFindCallBack cb = (userData, source) -> {
                if (source == null) {
                    return;
                }
                try {
                    String stream = api().mk_media_source_get_stream(source);
                    if (isSsrcHexName(stream)) {
                        names.add(stream);
                    }
                } catch (Throwable t) {
                    log.debug("枚举孤儿流失败: {}", t.toString());
                }
            };
            pinCallbackThread(cb);
            api().mk_media_source_for_each(null, cb, "", "", "", "");
        } catch (Throwable t) {
            log.debug("遍历媒体源失败: {}", t.toString());
        }
        return new java.util.ArrayList<>(names);
    }

    /**
     * 流名是否为「SSRC 十六进制串」（= 未被平台纳管的孤儿流）。
     *
     * <p>判据：长度 1..8 且全为十六进制字符，同时<b>不是</b>平台命名
     * （平台命名形如 {@code <设备编码>_<通道编码>}，含下划线；或纯 20 位数字编码）。
     * 纯数字串（如 {@code 200000001}）也算 SSRC 名 —— ZLM 的
     * {@code printSSRC} 输出大写十六进制，前导零被去掉后可能只剩数字。
     */
    static boolean isSsrcHexName(String stream) {
        if (stream == null || stream.isEmpty() || stream.length() > 8) {
            return false;
        }
        if (stream.indexOf('_') >= 0) {
            return false; // 平台命名 <设备>_<通道>
        }
        for (int i = 0; i < stream.length(); i++) {
            char c = stream.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
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
     * 运行时修改一个 ZLM 配置项（无需重启）。
     *
     * <p><b>只对 ZLM 每次读取的配置有效</b>：{@code mk_set_option} 写的是 ZLM 的
     * 全局 ini 内存镜像，因此像 {@code general.streamNoneReaderDelayMS}
     * （ZLM 在判断无人观看时才读）能立即生效；而
     * {@code rtp.ps_pt}（在 {@code mk_env_init2} 时固化为载荷类型）、
     * 各种 {@code *server_start} 端口号只读一次，改它们必须重启。
     *
     * <p>调用方（平台侧运行时配置）只应暴露前者，把后者放进
     * application.yml —— 否则界面上会显示「已保存」却不生效。
     *
     * @param key   ZLM 配置键（含段名，如 {@code general.streamNoneReaderDelayMS}）
     * @param value 值
     * @return 是否成功写入
     */
    public boolean applyOption(String key, String value) {
        if (key == null || value == null || value.isEmpty()) {
            return false;
        }
        if (!started.get()) {
            log.warn("ZLM 未启动，忽略运行时配置: {}={}", key, value);
            return false;
        }
        try {
            api().mk_set_option(key, value);
            log.info("ZLM 运行时配置已更新: {}={}", key, value);
            return true;
        } catch (Throwable t) {
            log.warn("设置 ZLM 运行时配置 {}={} 失败: {}", key, value, t.getMessage());
            return false;
        }
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

        // ---- [rtmp] FLV 视频标签格式 ----
        // 关闭增强型 RTMP 头，让 H.265 走传统 FLV 的 codecId=12。
        // 详见 MediaServerProperties.rtmpEnhanced 的说明。
        setOption(a, "rtmp.enhanced", props.isRtmpEnhanced() ? "1" : "0");

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

        // ---- [protocol] MP4 录制计为观看者 ----
        // ZLM 默认 mp4_as_player=0：MP4 录制任务不计入观看者数
        // （MultiMediaSourceMuxer::totalReaderCount 里 `_mp4 ? _option.mp4_as_player : 0`）。
        // 于是「点播 + 开录 + 关掉播放器」这一常见流程下，观看者数归零，
        // 超过 streamNoneReaderDelayMS 后 ZLM 触发 no_reader 回调销毁媒体源，
        // 录像被中途截断（现象：文件写了几 MB 后不再增长，duration 正常但内容不全）。
        // 置 1 后录制本身即视为观看者，录像可独立于播放器持续进行。
        setOption(a, "protocol.mp4_as_player", "1");

        // ---- [rtc] WebRTC 对外通告 ----
        // ZLM 默认用本机网卡地址当 ICE 候选（host candidate），但服务器往往在 NAT 后
        // （本项目：网卡 172.16.0.148，对外 160.202.254.65），浏览器拿到内网候选
        // 会连不上，表现为协商成功但视频一直 0x0、播放器报「连接中断」。
        // rtc.externIP 让 ZLM 把对外 IP 也当候选下发。
        //
        // 必须与 media-ip 区分：media-ip 是写进 SDP 给设备推流用的地址，
        // 两者通常相同（同一台机器），但若 WebRTC 走独立入口可单独覆盖。
        // 必须在 mk_rtc_server_start 之前设置：候选地址在服务启动时确定。
        String rtcExternIp = props.getRtcExternIp();
        if (StringUtils.isBlank(rtcExternIp)) {
            rtcExternIp = props.getMediaIp();
        }
        setOption(a, "rtc.externIP", rtcExternIp);
        // 候选端口默认 8000，与实际监听的 rtc.port 不一致会让浏览器连错端口
        setOption(a, "rtc.port", String.valueOf(props.getRtcPort()));
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
                        if (StringUtils.isBlank(name)) {
                            // 认不出 SSRC：可能是「设备不认 BYE、会话已拆但仍在推流」
                            // 的孤儿流。先尝试按墓碑重新认领（重建会话 → 恢复可见），
                            // 认领成功就按可读流名放行，不再退化成十六进制名。
                            java.util.function.Function<String, String> reclaimer = orphanReclaimer;
                            if (reclaimer != null) {
                                name = reclaimer.apply(ssrc);
                            }
                        }
                        if (StringUtils.isBlank(name)) {
                            // 仍认不出：放行但保留 ZLM 的默认流名（SSRC 十六进制），
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
                final boolean online = regist != 0;
                // 只取字段，然后立刻返回：监听者会做 mk_recorder_start/stop
                // 甚至等 MP4 收尾，放在这里会把 ZLM 的 event poller 卡住
                // （详见 eventExecutor 的字段注释）。
                dispatch(() -> {
                    // 依次通知全部监听者：单个监听者抛异常不能影响其它监听者
                    for (StreamListener l : streamListeners) {
                        try {
                            if (online) {
                                l.onStreamOnline(app, stream);
                            } else {
                                l.onStreamOffline(app, stream);
                            }
                        } catch (Throwable t) {
                            log.warn("流事件监听者处理失败: app={} stream={} regist={}",
                                    app, stream, regist, t);
                        }
                    }
                });
            } catch (Throwable t) {
                log.warn("处理 on_mk_media_changed 异常", t);
            }
        };

        // 无人观看回调（依赖 general.streamNoneReaderDelayMS 配置生效）：
        // 超过该时长仍无播放者时触发。平台据此发 BYE 释放设备带宽。
        ev.on_mk_media_no_reader = new com.aizuda.zlm4j.callback.IMKNoReaderCallBack() {
            @Override
            public void invoke(com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE ctx) {
                try {
                    String app = a.mk_media_source_get_app(ctx);
                    String stream = a.mk_media_source_get_stream(ctx);
                    String schema = a.mk_media_source_get_schema(ctx);
                    final boolean force = forceCloseOnNoReader;
                    log.info("流无观看者: schema={} app={} stream={}", schema, app, stream);
                    // 关键：<b>不能</b>在这里调 mk_media_source_close(ctx, 1)。
                    //
                    // 本回调是在 ZLM 遍历「无人观看」监听链的过程中同步调进来的，
                    // 此刻直接销毁该源 = 把自己正在执行的栈拆掉：源对象被释放后
                    // 回调返回，ZLM 继续访问已释放的 this → SIGSEGV，
                    // 或在 MultiMediaSourceMuxer 析构链上抛
                    // MediaSourceEvent::NotImplemented → std::terminate() → SIGABRT。
                    //
                    // 改为投递到 eventExecutor，用已加固的两阶段 closeMediaSource
                    // （先遍历收集句柄、遍历结束后再关）按 app/stream 关，
                    // 不回传 ctx 指针 —— ctx 在回调返回后即失效，跨线程持有必崩。
                    dispatch(() -> {
                        NoReaderListener l = noReaderListener;
                        if (l != null) {
                            try {
                                l.onNoReader(app, stream, schema);
                            } catch (Throwable t) {
                                log.warn("无人观看监听者处理失败: {}:{} {}", app, stream, t.toString());
                            }
                        }
                        if (force) {
                            int n = closeMediaSource(app, stream);
                            log.info("已按无人观看策略关闭流: {}:{} 关闭 {} 个源", app, stream, n);
                        }
                    });
                } catch (Throwable t) {
                    log.warn("处理无人观看回调异常", t);
                }
            }
        };

        // ZLM 日志回调：仅在开启 zlmLogToSlf4j 时注册（此时 mk_env_init2 的掩模里才有
        // LOG_CALLBACK）。默认不注册——回调为空时 ZLM 的事件 lambda 会直接跳过，
        // 不会进 JNI，进程退出因此是干净的。
        if (props.isZlmLogToSlf4j()) {
            ev.on_mk_log = this::forwardZlmLog;
        }

        // MP4 录制分片完成：拿到 ZLM 生成的真实路径与大小。
        // customized_path 只是目录，文件名/日期子目录由 ZLM 决定，
        // 因此录制产物的元数据必须以本回调为准，不能靠调用方拼路径。
        ev.on_mk_record_mp4 = record -> {
            try {
                String app = a.mk_record_info_get_app(record);
                String stream = a.mk_record_info_get_stream(record);
                String filePath = a.mk_record_info_get_file_path(record);
                String fileName = a.mk_record_info_get_file_name(record);
                long fileSize = a.mk_record_info_get_file_size(record);
                float timeLen = a.mk_record_info_get_time_len(record);
                RecordListener l = recordListener;
                if (l != null) {
                    l.onRecordMp4(app, stream, filePath, fileName, fileSize, timeLen);
                }
            } catch (Throwable t) {
                log.warn("处理 on_mk_record_mp4 异常", t);
            }
        };

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
