package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SdpEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * GB28181 媒体服务：把 sip 模块的信令事件桥接到 ZLMediaKit 收流与分发。
 *
 * <p>支持三类媒体会话：
 * <ul>
 *   <li><b>实时点播</b>（标准 9.2）：平台主动 {@link #play} 或设备主动 INVITE</li>
 *   <li><b>历史回放</b>（标准 9.8）：{@link #queryRecordInfo} 检索 → {@link #playback} 回放，
 *       支持 {@link #playbackControl} 的播放/暂停/跳转/倍速控制（INFO，附录 D）</li>
 *   <li><b>文件下载</b>（标准 9.9）：{@link #download} 触发设备把文件推到平台收流端口</li>
 * </ul>
 *
 * <p>此外把级联点播委托给 {@link CascadeForwarder}，把语音广播委托给 {@link BroadcastService}。
 */
public class Gb28181MediaService implements SipCallback {

    private static final Logger log = LoggerFactory.getLogger(Gb28181MediaService.class);

    /** 标准拒绝码：SDP 无法满足（附录 G 注 17）。 */
    private static final int SDP_NOT_ACCEPTABLE = 488;

    /** 同一个来源的同类拒绝日志最小间隔（毫秒）：扫描器约 30 次/小时，5 分钟折叠一次足够。 */
    private static final long REJECT_LOG_INTERVAL_MS = 5 * 60 * 1000L;

    /** 拒绝日志的上次打印时间（key = 来源 IP + 原因）。 */
    private final java.util.Map<String, Long> lastRejectLogAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 拒绝日志的折叠计数（key 同上）。 */
    private final java.util.Map<String, Integer> lastRejectLogCount = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 「不限速」倍速取值。
     *
     * <p>1 表示设备按实时速度推流，也就是改造前的行为。
     * 取该值时不写 {@code a=downloadspeed}，报文与历史版本完全一致。
     */
    private static final int SPEED_UNLIMITED = 1;

    private final ZlmMediaServer mediaServer;
    private final RtpSessionManager sessionManager;
    private final MediaServerProperties props;

    /**
     * 设备对录像检索的<b>拒绝原因</b>（通道编码 → 人类可读原因）。
     *
     * <p><b>为什么需要它</b>：设备拒绝 RecordInfo 时往往会在 SIP 响应里说明原因，
     * 例如实测 LiveGBS 会回
     * {@code 400 wait for recordinfo timeout[15s]}即「我去问下级摄像机超时了」。
     * 而业务侧只看到「一条帧都没收到」，于是报「设备未应答」——
     * 运维拿到这个提示只能去查网络，而真正要查的是设备/摄像机。
     *
     * <p>放在这里而不是透传回调里，是为了不改动
     * {@code Consumer<List<RecordItem>>} 这个既有契约（调用方已经很多）。
     * 业务侧在超时后调 {@link #lastRecordFailure(String)} 取一次即可。
     */
    private final Map<String, String> recordFailures = new ConcurrentHashMap<>();

    /**
     * 观察设备响应的等待时长（毫秒）。
     *
     * <p>要比业务侧的检索超时略长：这样「设备先拒绝、业务后超时」的顺序成立，
     * 业务取原因时一定已经写好了。
     */
    private static final long RECORD_RESPONSE_WATCH_MS = 30_000L;

    /** 观察设备响应的线程（不阻塞信令线程，也不占用分页收口调度器）。 */
    private final java.util.concurrent.ExecutorService recordResponseWatcher =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gb28181-record-resp");
                t.setDaemon(true);
                return t;
            });

    /**
     * 取设备对上一次录像检索的拒绝原因。
     *
     * @return 形如 {@code 设备返回 400 wait for recordinfo timeout[15s]}；
     *         设备未拒绝（或仍在处理）时返回 {@code null}
     */
    public String lastRecordFailure(String channelId) {
        return channelId == null ? null : recordFailures.get(channelId);
    }

    /**
     * 录像分页聚合的收口调度器。
     *
     * <p><b>为什么要独立调度</b>：收口条件有两个 —— ①已收齐 {@code SumNum}；
     * ②设备不再发帧。①由应答处理顺带判断即可，②却必须靠<b>时间</b>推进：
     * 设备把该发的都发完就安静了，不会再有事件来触发检查。
     *
     * <p>早先的实现把②也挂在 {@code onManscdpResponse} 里「顺带触发」，
     * 于是「设备少报 SumNum」或「设备只发了一部分就停」的场景永远等不到收口，
     * 业务侧只能耗尽 {@code future.get(timeout)} 的全部超时（实测固定 15 秒），
     * 用户看到的就是「查询录像卡、慢、超时」。
     *
     * <p>单线程 + 守护性：只做「看时间戳、收口、回调」，每次几十微秒。
     */
    private final java.util.concurrent.ScheduledExecutorService recordPagerTicker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gb28181-record-pager");
                t.setDaemon(true);
                return t;
            });

    /** 信令服务器（start 后由平台装配），用于 respondInvite / invitePlayback 等。 */
    private volatile Gb28181SipServer sipServer;

    private volatile CascadeForwarder cascadeForwarder;
    private volatile BroadcastService broadcastService;
    private volatile RecordingService recordingService;

    /** RecordInfo 检索回调：设备编码 → 消费者。 */
    private final Map<String, AtomicReference<Consumer<List<RecordItem>>>> recordCallbacks = new ConcurrentHashMap<>();

    /** 设备上下线监听者（可选）。 */
    public interface DeviceListener {
        void onRegister(RegisterEvent event);

        void onKeepAlive(KeepAliveEvent event);
    }

    private volatile DeviceListener deviceListener;

    /**
     * 信令回调委派对象（可选）。
     *
     * <p>本服务是 {@link Gb28181SipServer} 唯一的回调接收者，集成方（如 Web 平台）
     * 需要感知目录应答、报警通知等 MANSCDP 事件时，可在此挂一个委派对象；
     * 本类<b>先执行自身逻辑，再把事件转发给委派对象</b>，因此不影响原有行为。
     *
     * <p>委派对象在 SIP 协议栈线程上被调用，<b>不应阻塞</b>。
     */
    private volatile SipCallback callbackDelegate;

    public Gb28181MediaService(ZlmMediaServer mediaServer, RtpSessionManager sessionManager,
                               MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.sessionManager = sessionManager;
        this.props = props;
        /*
         * 每 300ms 检查一次空闲批次。
         *
         * <p>周期要明显小于 {@link RecordPager#IDLE_MS}（1200ms）：
         * 否则收口会比「设备已停发」晚一整拍，用户仍会感觉到额外等待。
         * 300ms 兼顾响应速度与唤醒次数（每秒 3 次空转成本可忽略）。
         */
        recordPagerTicker.scheduleWithFixedDelay(this::expireIdleRecordPagers,
                300, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 挂载信令回调委派对象；传 {@code null} 取消委派。 */
    public void setCallbackDelegate(SipCallback delegate) {
        this.callbackDelegate = delegate;
    }

    /** 当前委派对象，未挂载时返回 {@code null}。 */
    public SipCallback callbackDelegate() {
        return callbackDelegate;
    }

    public void attach(Gb28181SipServer sipServer) {
        this.sipServer = sipServer;
        // 把「关闭会话前发 BYE」注册到会话管理器。
        //
        // 为什么必须在这里挂钩子：会话被顶替发生在 RtpSessionManager.open()
        // 内部（重复点播先关旧会话），调用方完全无感知，于是从不发 BYE ——
        // 设备继续推旧流，新 INVITE 又推一路，同一通道两路流并存、带宽翻倍。
        // 由会话管理器在关闭前回调，才能覆盖所有关闭路径。
        this.sessionManager.setTeardownHook(this::byeQuietly);
    }

    public void setCascadeForwarder(CascadeForwarder cascadeForwarder) {
        this.cascadeForwarder = cascadeForwarder;
    }

    public void setBroadcastService(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    public void setRecordingService(RecordingService recordingService) {
        this.recordingService = recordingService;
    }

    public void setDeviceListener(DeviceListener listener) {
        this.deviceListener = listener;
    }

    public RtpSessionManager sessionManager() {
        return sessionManager;
    }

    public RecordingService recordingService() {
        return recordingService;
    }

    // ==================== INVITE：设备主动 ====================

    @Override
    public void onInvite(InviteEvent event) {
        if (event.getSdp() == null) {
            log.warn("INVITE 无 SDP，拒绝: callId={}", event.getCallId());
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }
        String peerId = event.getPeer() == null ? null : event.getPeer().getDeviceId();

        /*
         * 级联上行：上级平台点播本级自有通道。
         *
         * <p><b>必须单独判断</b>：判断「是不是级联」不能只看下行注册表 ——
         * 上级永远不注册到本级，所以 registry 里查不到它。改造前这种 INVITE
         * 会落到下面的「设备 INVITE」分支：开一个<b>接收</b>会话、回 200 OK，
         * 然后等设备推流给我们 —— 而实际上需要的是我们<b>推</b>流给上级。
         * 结果是上级等 20 秒后报「none rtp data」，而我们这边日志一片正常。
         */
        if (peerId != null && props.getUplinkPlatformChecker() != null
                && props.getUplinkPlatformChecker().test(peerId)) {
            handleUplinkInvite(event, peerId);
            return;
        }

        // 级联点播：来自已登记的级联平台，转发媒体而非自己收流
        if (peerId != null && sipServer != null && sipServer.getCascade() != null
                && sipServer.getCascade().getRegistry().get(peerId) != null) {
            handleCascadeInvite(event);
            return;
        }

        // 语音广播：s=AudioBroadcast
        if ("AudioBroadcast".equalsIgnoreCase(event.getSdp().getSessionName())) {
            handleBroadcastInvite(event);
            return;
        }

        /*
         * 未注册的对端一律拒绝（403 Forbidden）。
         *
         * <h3>为什么必须先于「s=Play 才应答」这一步</h3>
         * GB/T 28181 要求先 REGISTER 再 INVITE，但我们原来<b>没有校验
         * 注册状态</b>：只要 SDP 写 {@code s=Play}，就拿 From 当作设备编码
         * 建一个收流会话并回 200 OK。后果很具体：
         * <ul>
         *   <li><b>公网扫描器</b>能凭空创建会话。本机实测 24 小时内从
         *       {@code 37.49.228.147} 收到 615 次随机 Call-ID 的探测；
         *       其中一次写了 {@code s=Play}，日志里留下了
         *       {@code 已应答 INVITE(200 OK)} 紧跟 {@code 发送 BYE 失败：设备未注册}；</li>
         *   <li>每接受一次就占一个会话（独占端口模式下还占一个 RTP 端口），
         *       足够多的请求可以把端口池吃光，真实设备反而点播不了；</li>
         *   <li>对扫描器回 200 OK 等于确认「这里有个活的 SIP 服务」，
         *       会被反复回访。</li>
         * </ul>
         *
         * <p><b>顺序很重要</b>：上级平台不注册到本级，所以必须在
         * {@code uplinkPlatformChecker} 与级联注册表判定<b>之后</b>才查设备注册表，
         * 否则会把上级的正常点播一并拒掉（那才是真正会打断业务的方向）。
         */
        if (peerId == null || !isRegisteredDevice(peerId)) {
            logRejectThrottled(peerId, event, "对端未注册");
            reject(event, 403);
            return;
        }

        MediaType type = MediaType.ofSessionName(event.getSdp().getSessionName());
        if (type == MediaType.REALTIME && !event.isPlay()) {
            logRejectThrottled(peerId, event, "SDP s= 不是 Play");
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }

        String channelId = resolveChannelId(event);
        boolean tcp = isTcpTransport(event);
        try {
            String mediaUri = type == MediaType.REALTIME ? null : event.getSdp().getUri();
            RtpSession session = sessionManager.open(channelId, peerId, event.getSsrc(),
                    tcp ? RtpSessionManager.TCP_MODE_PASSIVE : 0, event.getCallId(), type, mediaUri);
            SdpMessage answer = buildAnswerSdp(session, tcp);
            client().respondInvite(event.getRawRequestEvent(), answer);
            log.info("已应答 INVITE(200 OK): {} media={}:{} playUrls={}",
                    session, props.getMediaIp(), session.getPort(),
                    mediaServer.playUrls(session));
        } catch (Exception e) {
            log.error("处理 INVITE 失败: device={} channel={} callId={}",
                    peerId, channelId, event.getCallId(), e);
            sessionManager.closeByChannel(peerId, channelId);
            reject(event, SDP_NOT_ACCEPTABLE);
        }
    }

    @Override
    public void onAck(InviteEvent event) {
        RtpSession session = sessionManager.getByCallId(event.getCallId());
        if (session != null) {
            log.debug("ACK 确认: {}", session);
        }
    }

    /**
     * 处理设备对 INVITE 的响应。
     *
     * <p>平台主动点播时，设备回200 OK 会话才算真正建立：
     * 必须解析应答 SDP 的对端收流地址与端口，并补发 ACK 完成握手，
     * 否则会话永远停在 NEGOTIATING，媒体也不会开始推流。
     *
     * <p>此前本类未实现该回调，是真机点播「已发 INVITE 但无画面」的根因之一。
     */
    @Override
    public void onRawResponse(javax.sip.ResponseEvent event) {
        javax.sip.message.Response response = event.getResponse();
        if (response == null || response.getStatusCode() != javax.sip.message.Response.OK) {
            return;
        }
        // 用 CSeq 判断是否为 INVITE 的应答（jain-sip 的事务接口未暴露请求方法）
        javax.sip.header.CSeqHeader cseq =
                (javax.sip.header.CSeqHeader) response.getHeader("CSeq");
        if (cseq == null || !"INVITE".equalsIgnoreCase(cseq.getMethod())) {
            return;
        }
        String callId = callIdOf(response);
        RtpSession session = sessionManager.getByCallId(callId);
        if (session == null) {
            return;
        }

        // 应答 SDP 记录以备排查（收流端口由平台侧 INVITE 的 m= 行给出，
        // 设备据此主动推流，故无需在此另存设备地址）
        Object raw = response.getRawContent();
        if (raw != null) {
            String xml = new String((byte[]) raw,
                    java.nio.charset.Charset.forName("UTF-8"));
            log.info("INVITE 应答 SDP: callId={}\n{}", callId, xml);
        }

        // 补发 ACK 完成握手
        // 必须传通道编码：ACK 在 INVITE 对话框内，Request-URI/To 需与原 INVITE 一致，
        // 否则部分设备匹配不到对话框而丢弃 ACK，表现为「回了 200 OK 但不推流」
        String toTag = toTagOf(response);
        sessionManager.bindToTag(session, toTag);
        try {
            client().ackInvite(session.getDeviceId(), session.getChannelId(), callId, toTag, null);
            log.info("INVITE 已被设备接受，已回 ACK: {} toTag={}", session, toTag);
        } catch (Exception e) {
            log.error("回 ACK 失败: {}", session, e);
        }
    }

    private static String callIdOf(javax.sip.message.Response response) {
        javax.sip.header.CallIdHeader h =
                (javax.sip.header.CallIdHeader) response.getHeader("Call-ID");
        return h == null ? null : h.getCallId();
    }

    private static String toTagOf(javax.sip.message.Response response) {
        javax.sip.header.ToHeader h = (javax.sip.header.ToHeader) response.getHeader("To");
        return h == null ? null : h.getTag();
    }

    @Override
    public void onBye(InviteEvent event) {
        RtpSession session = sessionManager.getByCallId(event.getCallId());
        if (session != null) {
            sessionManager.closeByCallId(event.getCallId());
            log.info("BYE 关闭会话: {}", session);
            return;
        }
        if (cascadeForwarder != null && cascadeForwarder.context(event.getCallId()) != null) {
            cascadeForwarder.stopForward(event.getCallId());
            return;
        }
        // 无 Call-ID 索引时退化为按「设备编码 + 通道编码」定位
        String channelId = resolveChannelId(event);
        String peerId = event.getPeer() == null ? null : event.getPeer().getDeviceId();
        if (peerId != null) {
            sessionManager.closeByChannel(peerId, channelId);
        } else {
            // 设备编码缺失（罕见）：按通道关闭，可能命中多台设备的同号通道
            sessionManager.closeByChannel(channelId);
        }
    }

    /** 收到设备侧 INFO（回放控制应答等），仅记录。 */
    @Override
    public void onInfo(SdpEvent event) {
        log.info("收到 INFO: callId={} u={} t={}", event.getCallId(),
                event.getSdp() == null ? null : event.getSdp().getUri(),
                event.getSdp() == null ? null : event.getSdp().getTime());
    }

    // ==================== 平台主动：实时 / 回放 / 下载 ====================

    /**
     * 平台主动实时点播（标准 9.2）。
     *
     * @param deviceId  设备编码（SIP 目标，必须全局唯一）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示点播设备自身
     */
    public RtpSession play(String deviceId, String channelId) {
        return play(deviceId, channelId, -1);
    }

    /**
     * 平台主动实时点播（标准 9.2），可指定码流编号。
     *
     * @param deviceId  设备编码（SIP 目标，必须全局唯一）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示点播设备自身
     * @param streamNumber 码流编号（GB/T 28181—2022 附录 G：SSRC 后 4 位）。
     *                     {@code -1} 自动（域内自增序号）；{@code 0} 主码流、{@code 1} 子码流
     */
    public RtpSession play(String deviceId, String channelId, int streamNumber) {
        String channel = StringUtils.isBlank(channelId) ? deviceId : channelId;
        RtpSession session = sessionManager.open(channel, deviceId, null, 0, null,
                MediaType.REALTIME, null, streamNumber);
        try {
            SdpMessage offer = buildAnswerSdp(session, false);
            // 标准 9.2：Request-URI / To / Subject 都要填「被点播的通道编码」，
            // 不能填设备编码 —— 设备会在自己的通道列表里找这个编码，
            // 找不到就静默丢弃（模拟器不校验，所以只有真实设备/下级平台会暴露）
            String callId = client().invitePlay(deviceId, channel, offer);
            sessionManager.bindCallId(session, callId);
            log.info("已发起点播 INVITE: {} media={}:{} callId={}",
                    session, props.getMediaIp(), session.getPort(), callId);
            return session;
        } catch (RuntimeException e) {
            // INVITE 没发出去/被拒 → 会话永远不可能 ONLINE。
            // 必须回滚：留在索引里就是僵尸，后续按通道找会话会命中它，
            // 带着错误的 app/stream 长期占位，云端录像再也找不到媒体源。
            sessionManager.abort(session.getSessionKey());
            throw e;
        }
    }

    /**
     * 平台主动实时点播（设备直连：设备编码即通道编码）。
     *
     * <p>适用于直连摄像机或设备自身就是一路通道的场景；
     * 下挂子通道请用 {@link #play(String, String)}。
     */
    public RtpSession play(String deviceId) {
        return play(deviceId, deviceId);
    }

    /**
     * 平台主动历史回放（标准 9.8）。
     *
     * @param deviceId  设备编码（SIP 目标）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @param item      {@link #queryRecordInfo} 检索到的录像段
     */
    public RtpSession playback(String deviceId, String channelId, RecordItem item) {
        return playback(deviceId, channelId, item, SPEED_UNLIMITED);
    }

    /**
     * 平台主动历史回放，可指定下载倍速。
     *
     * <p><b>为什么回放也支持倍速</b>：标准 9.9 的文件下载（{@code s=Download}）
     * 设备支持度很差（多数国产摄像机直接 4xx），本平台的「下载」因此复用
     * 回放链路 + 本地录制。而 9.9.1 b) 规定倍速是由<b>请求方在 SDP 中携带</b>的
     * （{@code a=downloadspeed:N}），设备侧只按这个属性决定推流速率，
     * 与 {@code s=} 是 Playback 还是 Download 无关。所以下载时在回放 SDP 里
     * 带上倍速，就能让设备快进推流，而不必依赖 {@code s=Download}。
     *
     * @param downloadSpeed 倍速档位（设备 {@code Info/DownloadSpeed} 声明的值，
     *                      如 2/4/8）；{@code 1} 表示按实时速度推流
     */
    public RtpSession playback(String deviceId, String channelId, RecordItem item,
                               int downloadSpeed) {
        String channel = StringUtils.isBlank(channelId) ? deviceId : channelId;
        /*
         * 会话身份（mediaUri）：有 FilePath 用它，没有则用「起/止时间」。
         *
         * <p>不能用 null：大量设备（实测 LiveGBS、多数国产摄像机）不填
         * FilePath，只按时间段管理录像。mediaUri 为 null 时：
         *   1) 业务层拿不到任何区分信息，点不同时间段会复用同一个会话，
         *      界面上「怎么点都是同一路画面」；
         *   2) 回放 INVITE 的 u= 行为空，对端（部分平台）因此拒答。
         *
         * 标准 A.2.5.3 里 u= 的语义就是「录像文件路径或时间标识」，
         * 填时间区间是合法的。
         */
        String mediaUri = item.getFilePath();
        if (StringUtils.isBlank(mediaUri)) {
            String start = RecordItem.formatTime(item.getStartTime());
            String end = RecordItem.formatTime(item.getEndTime());
            if (start != null) {
                mediaUri = start + "/" + (end == null ? start : end);
            }
        }
        RtpSession session = sessionManager.open(channel, deviceId, null, 0, null,
                MediaType.PLAYBACK, mediaUri);
        try {
            SdpMessage offer = buildPlaybackSdp(session, item, downloadSpeed);
            // 同实时点播：Request-URI / To / Subject 填通道编码
            String callId = client().invitePlayback(deviceId, channel, offer);
            sessionManager.bindCallId(session, callId);
            log.info("已发起回放 INVITE: {} mediaUri={} 倍速={} callId={} playUrls={}",
                    session, mediaUri, downloadSpeed, callId, mediaServer.playUrls(session));
            return session;
        } catch (RuntimeException e) {
            // 同实时点播：INVITE 失败必须回滚，否则留下永不 ONLINE 的僵尸会话
            sessionManager.abort(session.getSessionKey());
            throw e;
        }
    }

    /** 平台主动历史回放（设备直连：设备编码即通道编码）。 */
    public RtpSession playback(String deviceId, RecordItem item) {
        return playback(deviceId, deviceId, item);
    }

    /**
     * 平台主动文件下载（标准 9.9）：设备把文件流推到平台收流端口。
     *
     * <p>与回放的差别只有 {@code s=}：下载是 {@code Download}、回放是
     * {@code Playback}。两者 SDP 的其余结构（{@code u=} / {@code t=} /
     * {@code m=} / {@code a=recvonly}）完全一致，因此复用同一个构造器，
     * 只把 sessionName 换掉。
     *
     * <p><b>上一版这里用的 {@code buildAnswerSdp} 是错的</b>：那是给
     * 被动收流（设备 INVITE 我们）用的，{@code t=} 固定写 {@code 0 0}。
     * 下载必须给出真实时间区间，否则设备不知道要打包哪一段。
     *
     * @param deviceId  设备编码（SIP 目标）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @param item      要下载的录像段（含时间区间；有 FilePath 时用路径）
     */
    public RtpSession download(String deviceId, String channelId, RecordItem item) {
        return download(deviceId, channelId, item, SPEED_UNLIMITED);
    }

    /**
     * 平台主动文件下载，可指定下载倍速（标准 9.9.1 b)）。
     *
     * @param downloadSpeed 倍速档位；{@code 1} 表示按实时速度推流
     */
    public RtpSession download(String deviceId, String channelId, RecordItem item,
                               int downloadSpeed) {
        String channel = StringUtils.isBlank(channelId) ? deviceId : channelId;
        String mediaUri = recordIdentity(item);
        RtpSession session = sessionManager.open(channel, deviceId, null, 0, null,
                MediaType.DOWNLOAD, mediaUri);
        try {
            SdpMessage offer = buildPlaybackSdp(session, item, downloadSpeed)
                    .sessionName(SdpMessage.S_DOWNLOAD);
            // Subject 传 null：由 sip 模块按标准生成
            // <通道编码>:<SSRC>,<平台编码>:0
            String callId = client().invite(deviceId, channel, offer, null);
            sessionManager.bindCallId(session, callId);
            log.info("已发起下载 INVITE: {} mediaUri={} 倍速={} callId={}",
                    session, mediaUri, downloadSpeed, callId);
            return session;
        } catch (RuntimeException e) {
            // 同实时点播：INVITE 失败必须回滚，否则留下永不 ONLINE 的僵尸会话
            sessionManager.abort(session.getSessionKey());
            throw e;
        }
    }

    /**
     * 录像段的「会话身份」：有 FilePath 用它，否则用「起/止时间」。
     *
     * <p>用于区分不同录像段，<b>不是</b> SDP 的 {@code u=}（那个由
     * {@link #buildPlaybackSdp} 单独决定）。
     */
    static String recordIdentity(RecordItem item) {
        if (item == null) {
            return null;
        }
        if (item.getFilePath() != null && !item.getFilePath().trim().isEmpty()) {
            return item.getFilePath();
        }
        String start = RecordItem.formatTime(item.getStartTime());
        if (start == null) {
            return null;
        }
        String end = RecordItem.formatTime(item.getEndTime());
        return start + "/" + (end == null ? start : end);
    }

    /** 平台主动文件下载（设备直连：设备编码即通道编码）。 */
    public RtpSession download(String deviceId, RecordItem item) {
        return download(deviceId, deviceId, item);
    }

    /**
     * 历史回放控制（标准 9.8 / 附录 D，经 {@code INFO} 下发）。
     *
     * <p><b>消息体是 RTSP 风格文本，不是 SDP</b> —— 这是最容易被
     * 「SDP 里放 u=play」这种写法带偏的地方。实测设备（LiveGBS、
     * 海康、大华系）只认 RTSP 风格：
     * <pre>
     * 暂停：PAUSE RTSP/1.0\r\nCSeq: N\r\nPauseTime: now\r\n
     * 继续：PLAY  RTSP/1.0\r\nCSeq: N\r\nRange: npt=now-\r\n
     * 拖动：PLAY  RTSP/1.0\r\nCSeq: N\r\nRange: npt=&lt;相对秒数&gt;-\r\n
     * 倍速：PLAY  RTSP/1.0\r\nCSeq: N\r\nScale: &lt;浮点&gt;\r\n
     * </pre>
     * 与 WVP-GB28181-pro 的 {@code playSeekCmd} / {@code playPauseCmd}
     * / {@code playResumeCmd} / {@code playSpeedCmd} 完全一致。
     *
     * @param deviceId 设备编码
     * @param callId   回放 INVITE 的 Call-ID（INFO 要落在该对话框内）
     * @param command  命令
     * @param seekSecond 拖动目标：<b>相对本段录像起点的秒数</b>。
     *                注意不是绝对时间戳 —— 国标设备的 npt 是段内偏移，
     *                传绝对秒数会跳到「开播 N 年后」而立即结束。
     * @param scale    倍速（SPEED 使用；1.0 为正常）
     */
    public void playbackControl(String deviceId, String callId, PlaybackCommand command,
                                long seekSecond, float scale) {
        // 通道编码从会话取：INFO 必须在 INVITE 对话框内（RFC 2976），
        // Request-URI / To 要与回放 INVITE 一致（都是通道编码），
        // 而调用方只持有 callId 与设备编码，此处按 callId 反查通道
        RtpSession session = sessionManager.getByCallId(callId);
        String channel = session == null ? deviceId : session.getChannelId();

        StringBuilder body = new StringBuilder(96);
        switch (command) {
            case PAUSE:
                body.append("PAUSE RTSP/1.0\r\n")
                        .append("CSeq: ").append(nextControlCSeq(callId)).append("\r\n")
                        .append("PauseTime: now\r\n");
                break;
            case RESUME:
                body.append("PLAY RTSP/1.0\r\n")
                        .append("CSeq: ").append(nextControlCSeq(callId)).append("\r\n")
                        .append("Range: npt=now-\r\n");
                break;
            case SEEK:
                body.append("PLAY RTSP/1.0\r\n")
                        .append("CSeq: ").append(nextControlCSeq(callId)).append("\r\n")
                        .append("Range: npt=").append(Math.max(0L, seekSecond))
                        .append("-\r\n");
                break;
            case SPEED:
                body.append("PLAY RTSP/1.0\r\n")
                        .append("CSeq: ").append(nextControlCSeq(callId)).append("\r\n")
                        .append("Scale: ").append(String.format("%.6f", scale))
                        .append("\r\n");
                break;
            default:
                log.warn("不支持的回放控制命令: {}", command);
                return;
        }
        client().infoRaw(deviceId, channel, callId, "application/MANSRTSP", body.toString());
        log.info("发送回放控制: channel={} callId={} command={} seek={}s scale={}",
                channel, callId, command, seekSecond, scale);
    }

    /** 回放控制的 CSeq：同一会话内递增，设备按它去重。 */
    private final Map<String, java.util.concurrent.atomic.AtomicLong> controlCSeq
            = new ConcurrentHashMap<>();

    private long nextControlCSeq(String callId) {
        return controlCSeq.computeIfAbsent(callId,
                k -> new java.util.concurrent.atomic.AtomicLong(1L)).getAndIncrement();
    }

    /**
     * 停止某会话的点播：发 BYE 让设备停止推流，再释放收流端口与媒体源。
     *
     * <p><b>必须先发 BYE</b>：仅释放本地收流端口不会通知设备，设备的 INVITE
     * 会话仍然有效，会继续向上行推流（推到已释放的端口上被丢弃），白白占用带宽
     * 直到设备侧超时。这一点与 {@link #onNoViewer} 一致。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @return 是否找到并关闭了会话（会话不存在时返回 {@code false}）
     */
    public boolean stop(String deviceId, String channelId) {
        String sessionKey = RtpSessionManager.sessionKey(deviceId, channelId);
        RtpSession session = sessionManager.getByKey(sessionKey);
        if (session == null || RtpSession.CLOSED.equals(session.getState())) {
            log.debug("停止点播：未找到活跃会话: {}", sessionKey);
            return false;
        }
        // 不在此处发 BYE：closeByKey 会通过 teardownHook 统一发送。
        // 两处都发会让设备收到重复 BYE（同一 Call-ID），且第二次在 native
        // 资源释放后发出，实测触发 JVM 崩溃（SIGABRT / core dumped）。
        sessionManager.closeByKey(sessionKey);
        return true;
    }

    /** 停止点播（设备直连：设备编码即通道编码）。 */
    public boolean stop(String deviceId) {
        return stop(deviceId, deviceId);
    }

    /**
     * 停止指定类型的会话（回放/下载与直播是独立会话，需按类型定位）。
     *
     * <p>直播与回放不再共用会话键，因此 {@link #stop(String, String)} 只关直播；
     * 停回放必须走本方法，否则会去关一个不存在的键（返回 false）或误关直播。
     *
     * @param type 会话类型；{@code null} 或 {@link MediaType#REALTIME} 等价于
     *             {@link #stop(String, String)}
     * @return 是否找到并关闭了会话
     */
    public boolean stop(String deviceId, String channelId, MediaType type) {
        if (type == null || type == MediaType.REALTIME) {
            return stop(deviceId, channelId);
        }
        String sessionKey = RtpSessionManager.sessionKey(deviceId, channelId, type);
        RtpSession session = sessionManager.getByKey(sessionKey);
        if (session == null || RtpSession.CLOSED.equals(session.getState())) {
            log.debug("停止会话：未找到活跃会话: {}", sessionKey);
            return false;
        }
        // 同 stop(device, channel)：BYE 由 closeByKey 统一发送
        sessionManager.closeByKey(sessionKey);
        return true;
    }

    /**
     * 无人观看超时：发 BYE 释放设备带宽并关闭会话。
     *
     * <p>由 {@code ZlmMediaServer} 的无人观看回调驱动（判定时长见
     * {@code general.streamNoneReaderDelayMS}）。
     *
     * <p>相比让 ZLM 直接 {@code mk_media_source_close}，走这里的好处是
     * 会发 SIP BYE 让设备停止推流，而不只是本地断开。
     *
     * <p><b>必须同时传 app 与 stream</b>：两者共同定位唯一媒体源
     * （{@code app=设备编码}、{@code stream=通道编码}）。
     *
     * @param app    ZLM 应用名（= 设备编码）
     * @param stream ZLM 流名（= 通道编码）
     * @return 是否找到并关闭了会话
     */
    public boolean onNoViewer(String app, String stream) {
        RtpSession session = sessionManager.resolve(app, stream);
        if (session == null || RtpSession.CLOSED.equals(session.getState())) {
            // ZLM 对同一个源会按协议各报一次（fmp4/rtmp/rtsp/ts/hls），
            // 首次关闭后剩余几次查不到会话属正常，降级为 debug 避免刷屏
            log.debug("无人观看，但未找到对应会话（可能已关闭）: app={} stream={}", app, stream);
            return false;
        }
        log.info("无人观看超时，关闭会话: {}", session);
        // 记冷却再关流：设备不认 BYE（实测 LiveGBS）会继续推流，
        // 不记冷却就会被孤儿流巡检立刻认领回来，形成
        // 「认领 → 无人观看关流 → 再认领」的循环，每 30 秒刷一轮。
        // 与 StreamService#reapIdleStreams 的关闭路径保持一致。
        // 按会话键记（不是 SSRC，见 RtpSessionManager#markReaped 的说明）。
        sessionManager.markReaped(session.getSessionKey());
        // BYE 由 closeByKey 统一发送（见 teardownHook）
        sessionManager.closeByKey(session.getSessionKey());
        return true;
    }

    /**
     * 为「上一轮运行遗留的对话」补发 BYE，释放远端仍在进行的推流。
     *
     * <p><b>为什么必须补</b>：进程被强杀（{@code gb.sh} 优雅停止超时会强制 kill）
     * 时来不及发 BYE，设备/下级平台不知道我方已退出，会<b>一直推下去</b>。
     * 实测 LiveGBS 每一路约 3 Mbps 且永不超时，重启几次就攒成多路重复推流，
     * 表现为「级联那边显示还有 N 个视频正在推送」。平台侧已经没有对话信息，
     * 只能靠库里持久化的 Call-ID 在启动时补发一次。
     *
     * <p><b>为什么不需要原对话的 tag</b>：实测 LiveGBS 按 Call-ID 匹配，只带
     * Call-ID 的 BYE 它照样回 200 OK 并停止推流。对严格实现该对话已不存在的
     * 设备，会回 481（无害）；已结束的对话再发 BYE 也不会造成副作用。
     *
     * @param deviceId  设备编码（BYE 的发送目标）
     * @param channelId 通道编码；为空时退回设备编码
     * @param callId    上一轮该对话的 Call-ID
     * @return 是否已发出（不代表对端一定停止）
     */
    public boolean byeQuietly(String deviceId, String channelId, String callId) {
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(callId) || sipServer == null) {
            return false;
        }
        String channel = StringUtils.isBlank(channelId) ? deviceId : channelId;
        try {
            // toTag 传 null：上一轮的 tag 没有持久化，且 LiveGBS 按 Call-ID 匹配即可
            client().bye(deviceId, channel, callId, null);
            return true;
        } catch (Throwable t) {
            log.debug("补发 BYE 失败: device={} callId={} {}", deviceId, callId, t.toString());
            return false;
        }
    }

    /**
     * 尽力发送 BYE 释放设备带宽；失败只告警，不阻断本地资源释放。
     *
     * <p>三种情况会跳过：会话无 Call-ID（平台侧尚未回填）、无设备编码、
     * 信令服务器未装配（{@code attach} 未调用）。
     */
    private void byeQuietly(RtpSession session) {
        String callId = session.getCallId();
        String deviceId = session.getDeviceId();
        if (callId == null || deviceId == null || sipServer == null) {
            return;
        }
        try {
            // 传通道编码：BYE 与 INVITE 同属一个对话框，
            // Request-URI / To 必须与原 INVITE 一致，否则设备忽略 BYE 继续推流
            client().bye(deviceId, session.getChannelId(), callId, session.getToTag());
        } catch (Exception e) {
            log.warn("发送 BYE 失败: device={} callId={}, {}", deviceId, callId, e.getMessage());
        }
    }

    /**
     * 某通道当前会话的播放地址。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     */
    public PlayUrls playUrls(String deviceId, String channelId) {
        RtpSession session = sessionManager.getByChannel(deviceId, channelId);
        return session == null ? null : mediaServer.playUrls(session);
    }

    /** 某通道当前会话的播放地址（设备直连：设备编码即通道编码）。 */
    public PlayUrls playUrls(String deviceId) {
        return playUrls(deviceId, deviceId);
    }

    /**
     * 检索设备录像（标准 9.8 RecordInfo），异步回调。
     *
     * @param startTime ISO 8601 {@code yyyy-MM-dd'T'HH:mm:ss}
     * @param endTime   同上
     */
    public void queryRecordInfo(String deviceId, String startTime, String endTime,
                                Consumer<List<RecordItem>> onRecords) {
        queryRecordInfo(deviceId, deviceId, startTime, endTime, onRecords);
    }

    /**
     * 检索设备录像（标准 9.8 RecordInfo），异步回调。
     *
     * <p><b>回调按通道编码索引，不按设备编码</b>：设备应答里的
     * {@code <DeviceID>} 是我们请求时写的<b>通道编码</b>（录像挂在通道上），
     * 用设备编码做 key 会在应答到来时匹配不到，
     * 表现为「设备明明回了录像列表，平台一直等到超时并报空」。
     *
     * @param sipTarget 设备编码（SIP 寻址用）
     * @param channelId 通道编码（{@code <DeviceID>} 与回调 key）
     * @param startTime ISO 8601 {@code yyyy-MM-dd'T'HH:mm:ss}
     * @param endTime   同上
     */
    public void queryRecordInfo(String sipTarget, String channelId,
                                String startTime, String endTime,
                                Consumer<List<RecordItem>> onRecords) {
        // 不能用 String.isBlank()：本模块锁 Java 8 API 级别（pom 里 <release>8</release>）
        String key = channelId == null || channelId.trim().isEmpty() ? sipTarget : channelId;
        recordCallbacks.put(key, new AtomicReference<>(onRecords));
        recordPagers.put(key, new RecordPager(key));
        // 上一轮的拒绝原因不能留到这一轮，否则会把旧故障当成新故障报给用户
        recordFailures.remove(key);
        String callId = client().queryRecordInfo(sipTarget, key, startTime, endTime);
        watchRecordResponse(key, callId);
    }

    /**
     * 后台观察本次检索的 SIP 最终响应，把设备的拒绝原因记下来。
     *
     * <p><b>为什么不在信令线程里等</b>：设备可能要十几秒才回（它自己在等下级），
     * 阻塞信令线程会直接影响注册、心跳、点播等所有信令。
     *
     * <p>只关心 {@code >= 300}：{@code 200 OK} 表示设备受理，
     * 真正的录像列表随后以独立的 MANSCDP 帧到达（走分页聚合）。
     */
    private void watchRecordResponse(String key, String callId) {
        if (callId == null) {
            return;
        }
        try {
            recordResponseWatcher.execute(() -> {
                try {
                    javax.sip.message.Response resp =
                            client().awaitResponse(callId, RECORD_RESPONSE_WATCH_MS);
                    if (resp == null || resp.getStatusCode() < 300) {
                        return;
                    }
                    String reason = "设备返回 " + resp.getStatusCode()
                            + (resp.getReasonPhrase() == null ? "" : " " + resp.getReasonPhrase());
                    recordFailures.put(key, reason);
                    log.warn("录像检索被设备拒绝: channel={} {}", key, reason);
                } catch (Exception e) {
                    log.debug("观察录像应答失败: channel={} {}", key, e.toString());
                }
            });
        } catch (Exception e) {
            // 线程池已关闭（服务停止中）时忽略
            log.debug("提交应答观察任务失败: {}", e.toString());
        }
    }

    /**
     * 录像应答的分页聚合状态。
     *
     * <p><b>为什么必须聚合</b>：标准 9.8 的 RecordInfo 应答支持分页 ——
     * {@code <SumNum>} 是总段数，而 {@code <RecordList Num="...">} 只携带其中一页。
     * 实测 LiveGBS v250103 严格按标准执行：一个通道有 13 段录像时，它连发
     * <b>13 个独立的 Response</b>，每帧 {@code SumNum=13} 但只含 1 个 {@code <Item>}。
     *
     * <p>改造前收到第一帧就 {@code remove} 掉回调并返回，于是界面上永远只显示
     * 1 段录像 —— 用户看到「只有一段」会以为设备录像缺失，实际是我们的聚合缺失。
     *
     * <p><b>收口条件</b>：已收条数 &ge; {@code SumNum}（正常收齐），
     * 或距最后一帧超过 {@link #RECORD_IDLE_MS}（设备少报/丢包，不能无限等）。
     */
    private static final class RecordPager {

        /**
         * 无新帧多久判定收口（毫秒）。
         *
         * <p><b>为什么是 2 秒而不是 1 秒</b>：设备返回录像列表时往往是
         * 「一大串快发 + 偶发停顿」。停顿超过阈値就会提前收口，
         * 丢掉后面那几段 —— 而这种丢法很难发现（列表看着正常，只是少了几行）。
         * 2 秒比正常帧间隔大一个数量级，仍远小于业务侧 15 秒超时。
         */
        static final long IDLE_MS = 2000L;

        private final String key;
        private final java.util.List<RecordItem> items = new java.util.ArrayList<>();
        private final Map<String, RecordItem> unique = new java.util.LinkedHashMap<>();
        /** 设备声明的总段数；{@code -1} 表示还没声明过。 */
        private volatile int sumNum = -1;
        private volatile long lastFrameAt = System.currentTimeMillis();
        /**
         * 是否收到过 RecordInfo 帧。
         *
         * <p><b>不能用 {@code unique.isEmpty()} 代替</b>：设备完全可以权威地答
         * 「这个时段没有录像」（{@code <SumNum>0</SumNum>} 且无 {@code <Item>}），
         * 那是有效答案而不是「没应答」。混为一谈会让「确实没录像」
         * 白白等满整个业务超时（实测 LiveGBS 对无录像的通道 1 秒就回了
         * {@code SumNum=0}，而平台仍爬到 20 秒超时）。
         */
        private volatile boolean framesReceived;
        private volatile boolean done;

        RecordPager(String key) {
            this.key = key;
        }

        /**
         * 累积一帧。
         *
         * @return {@code true} 表示已收齐，可以回调业务方
         */
        synchronized boolean accept(ManscdpMessage msg, List<RecordItem> frame) {
            lastFrameAt = System.currentTimeMillis();
            framesReceived = true;
            Integer declared = msg == null ? null : msg.intOf("SumNum");
            // 0 也要采信：那是设备在明确告知「该时段没有录像」
            if (declared != null && declared >= 0) {
                sumNum = declared;
            }
            for (RecordItem item : frame) {
                /*
                 * 按「起始时间 + 结束时间 + 名称」去重：
                 * 部分设备在重传时会重复上报同一段，
                 * 不去重会让界面上出现一模一样的两行。
                 */
                String id = item.getStartTime() + "|" + item.getEndTime()
                        + "|" + item.getName();
                unique.put(id, item);
            }
            /*
             * 收口条件一：设备声明了总数且已收齐。
             *
             * 收口条件二：设备声明 0 段且本帧确实没带条目 —— 这就是完整答案，
             * 立即回调，不要白等（否则界面上的「正在查询」会拖满整个超时）。
             */
            if (sumNum > 0 && unique.size() >= sumNum) {
                done = true;
                return true;
            }
            if (sumNum == 0 && unique.isEmpty()) {
                done = true;
                return true;
            }
            return false;
        }

        /**
         * 空闲超时兜底：设备少报或丢包时，把已收到的交上去。
         *
         * <p>两种场景靠它收口：
         * <ul>
         *   <li>设备根本<b>不发 {@code SumNum}</b>（实测部分通道如此）——
         *       此时 {@code sumNum} 恒为 -1，能收齐的条件永远不成立；</li>
         *   <li>设备少报 {@code SumNum}，或中途丢包。</li>
         * </ul>
         *
         * <p><b>只要收到过帧就收口</b>（哪怕条目为 0）：收到过就说明设备已应答，
         * 结论（含「没有录像」）是可信的。一帧都没收到才继续等 ——
         * 那种情况应归为「设备未应答」。
         */
        boolean expireIfIdle() {
            if (done) {
                return false;
            }
            if (System.currentTimeMillis() - lastFrameAt < IDLE_MS) {
                return false;
            }
            done = true;
            return framesReceived;
        }

        /** 该批次是否已经收到过帧（用于判断「应没应答」与「答了空的」）。 */
        boolean receivedAny() {
            return framesReceived;
        }

        List<RecordItem> snapshot() {
            return new java.util.ArrayList<>(unique.values());
        }

        @Override
        public String toString() {
            return "RecordPager{" + key + ", 已收 " + unique.size()
                    + "/" + (sumNum >= 0 ? sumNum : "?") + "}";
        }
    }

    /** 正在聚合的录像应答，key = 通道编码。 */
    private final Map<String, RecordPager> recordPagers = new ConcurrentHashMap<>();

    /**
     * 检查所有空闲超时的录像聚合批次并收口。
     *
     * <p>由 {@link #recordPagerTicker} 定期驱动，<b>不能只靠</b>
     * {@code onManscdpResponse} 顺带触发：设备停发之后就不会再有任何应答，
     * 靠事件驱动的收口永远不会发生（详见字段上的说明）。
     */
    private void expireIdleRecordPagers() {
        try {
            for (Map.Entry<String, RecordPager> e : recordPagers.entrySet()) {
                RecordPager pager = e.getValue();
                if (!pager.expireIfIdle()) {
                    continue;
                }
                if (recordPagers.remove(e.getKey(), pager)) {
                    completeRecordQuery(e.getKey(), pager);
                }
            }
        } catch (Exception ex) {
            // 调度任务里抛出异常会让后续周期全部取消，必须吞掉
            log.warn("录像聚合空闲收口异常: {}", ex.toString());
        }
    }

    /** 回调业务方并清理状态。 */
    private void completeRecordQuery(String key, RecordPager pager) {
        AtomicReference<Consumer<List<RecordItem>>> ref = recordCallbacks.remove(key);
        List<RecordItem> merged = pager.snapshot();
        log.info("录像检索聚合完成: channel={} 段数={} {}", key, merged.size(), pager);
        if (ref != null && ref.get() != null) {
            ref.get().accept(merged);
        }
    }

    /**
     * 一次录像检索「未正常收口」时取走的结果。
     *
     * @param items          已收到的录像段
     * @param framesReceived 是否<b>收到过任何</b> RecordInfo 帧
     */
    public static final class RecordDrain {
        private final List<RecordItem> items;
        private final boolean framesReceived;

        RecordDrain(List<RecordItem> items, boolean framesReceived) {
            this.items = items;
            this.framesReceived = framesReceived;
        }

        public List<RecordItem> items() {
            return items;
        }

        /**
         * 设备到底有没有应答。
         *
         * <p>这个区分比条数重要得多：<b>没收到任何帧</b>说明设备没答（离线、
         * 设备侧检索故障），<b>收到了帧但段数为 0</b> 说明设备明确回了
         * 「这段时间没有录像」。两者在业务侧的处理完全相反，
         * 混成一个空列表就是在报假警。
         */
        public boolean framesReceived() {
            return framesReceived;
        }
    }

    /**
     * 取走某通道尚未收口的录像聚合结果（并把回调置空）。
     *
     * <p>用途：业务侧等待超时后，把<b>已经收到的那些</b>拿走而不是整体丢弃 ——
     * 对用户来说「看到 5 段」远好于「一段都没有」，而「一帧都没收到」
     * 则要明确告知设备未应答。
     *
     * @return 已聚合结果（永不为 {@code null}）
     */
    public RecordDrain drainRecordQuery(String channelId) {
        if (channelId == null) {
            return new RecordDrain(java.util.Collections.emptyList(), false);
        }
        RecordPager pager = recordPagers.remove(channelId);
        recordCallbacks.remove(channelId);
        if (pager == null) {
            return new RecordDrain(java.util.Collections.emptyList(), false);
        }
        List<RecordItem> items = pager.snapshot();
        boolean any = pager.receivedAny();
        if (any) {
            log.info("录像检索超时取走: channel={} 已收 {} 段 {}", channelId, items.size(), pager);
        }
        return new RecordDrain(items, any);
    }

    /**
     * 取走某通道尚未收口的录像段（仅要数据、不关心是否有帧）。
     *
     * @return 已聚合的录像段；没有在处理中的批次时返回空列表
     */
    public List<RecordItem> takePendingRecords(String channelId) {
        return drainRecordQuery(channelId).items();
    }

    @Override
    public void onManscdpResponse(ManscdpEvent event) {
        if (event == null || event.getManscdp() == null) {
            return;
        }
        ManscdpMessage msg = event.getManscdp();
        String deviceId = msg.getDeviceId();
        /*
         * 录像检索应答：必须累积所有分页帧后再回调。
         * 见 {@link RecordPager} 的说明 —— 设备按标准一帧一页上报，
         * 收到第一帧就回调只会拿到 1 段录像。
         */
        if ("RecordInfo".equals(msg.getCmdType()) && deviceId != null) {
            RecordPager pager = recordPagers.get(deviceId);
            if (pager != null) {
                List<RecordItem> frame = RecordItem.parse(event);
                if (pager.accept(msg, frame)
                        && recordPagers.remove(deviceId, pager)) {
                    completeRecordQuery(deviceId, pager);
                }
            }
            // 顺带收口其它空闲批次（设备少报/丢包时的兜底）；定时器也会做同样的事
            expireIdleRecordPagers();
        }
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onManscdpResponse(event);
        }
    }

    @Override
    public Boolean onManscdpRequest(ManscdpEvent event) {
        SipCallback delegate = callbackDelegate;
        return delegate == null ? null : delegate.onManscdpRequest(event);
    }

    @Override
    public void onUnknownCommand(ManscdpEvent event) {
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onUnknownCommand(event);
        }
    }

    @Override
    public void onNotify(io.github.rurulalanana.gb28181.sip.event.SubscribeEvent event) {
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onNotify(event);
        }
    }

    @Override
    public void onCascadePlatformRegister(CascadeEvents.PlatformRegistered event) {
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onCascadePlatformRegister(event);
        }
    }

    @Override
    public void onCascadePlatformUnregister(CascadeEvents.PlatformUnregistered event) {
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onCascadePlatformUnregister(event);
        }
    }

    // ==================== 设备事件 ====================

    @Override
    public void onRegister(RegisterEvent event) {
        log.info("设备{}: deviceId={} ip={} expires={}",
                event.isUnregistered() ? "注销" : "注册",
                event.getDeviceId(), event.getPeer().getIp(), event.getExpires());
        DeviceListener l = deviceListener;
        if (l != null) {
            l.onRegister(event);
        }
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onRegister(event);
        }
    }

    @Override
    public void onKeepAlive(KeepAliveEvent event) {
        log.debug("心跳: deviceId={} status={}", event.getDeviceId(), event.getStatus());
        DeviceListener l = deviceListener;
        if (l != null) {
            l.onKeepAlive(event);
        }
        SipCallback delegate = callbackDelegate;
        if (delegate != null) {
            delegate.onKeepAlive(event);
        }
    }

    // ==================== 级联 / 广播 ====================

    /** 级联点播：上级点播本级通道，本级负责收流并转推给上级。 */
    private void handleCascadeInvite(InviteEvent event) {
        CascadeForwarder forwarder = cascadeForwarder;
        if (forwarder == null) {
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }
        String platformId = event.getPeer().getDeviceId();
        CascadeEvents.CascadeInvite cascadeEvent = CascadeEvents.CascadeInvite.builder()
                .platformId(platformId)
                .ssrc(event.getSsrc())
                .playback(event.isPlayback())
                .rawRequestEvent(event.getRawRequestEvent())
                .build();
        try {
            CascadeForwarder.ForwardContext ctx = forwarder.onCascadeInvite(cascadeEvent, event.getCallId());
            if (ctx == null) {
                reject(event, SDP_NOT_ACCEPTABLE);
                return;
            }
            String dstIp = event.getSdp().resolveMediaIp();
            int dstPort = event.getMediaPort();
            if (dstIp != null && dstPort > 0) {
                forwarder.startForward(event.getCallId(), dstIp, dstPort);
            }
            client().respondInvite(event.getRawRequestEvent(),
                    forwarder.buildUpstreamAnswer(platformId, ctx));
            log.info("级联点播已应答: 上级={} → {}:{} 本地端口={}",
                    platformId, dstIp, dstPort, ctx.localPort);
        } catch (Exception e) {
            log.error("级联点播处理失败: platform={}", platformId, e);
            reject(event, SDP_NOT_ACCEPTABLE);
        }
    }

    /**
     * 级联上行点播：上级点播本级自有通道。
     *
     * <p>媒体层只做两件事：<b>应答 200 OK（带 SDP）</b>，
     * 然后<b>把「该往哪推流」告诉业务方</b>。
     * 为什么不在这一层直接推：推流的前提是本地流已经在跑，而
     * 「哪台设备、怎么让它推流」属于业务层知识（本模块拿不到设备表）。
     */
    private void handleUplinkInvite(InviteEvent event, String platformId) {
        String channelId = resolveChannelId(event);
        if (channelId == null || channelId.trim().isEmpty()) {
            log.warn("级联上行 INVITE 无法解析通道编码: callId={}", event.getCallId());
            reject(event, 400);
            return;
        }
        String dstIp = event.getSdp().resolveMediaIp();
        int dstPort = event.getMediaPort();
        if (dstIp == null || dstIp.trim().isEmpty() || dstPort <= 0) {
            // 没有收流地址就无法推流。与其回 200 OK 让上级干等 20 秒后报
            // 「none rtp data」，不如立刻拒绝 —— 错误在两边都看得见。
            log.warn("级联上行 INVITE 缺少有效收流地址: channel={} dstIp={} dstPort={}",
                    channelId, dstIp, dstPort);
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }
        try {
            /*
             * 应答 SDP 声明本级媒体地址。上级其实从它自己的 INVITE 里就知道
             * 该在哪收流，这里主要是让它的解析器能正常建立会话。
             */
            SdpMessage answer = new SdpMessage()
                    .origin(props.getPlatformId(), props.getMediaIp())
                    .sessionName(SdpMessage.S_PLAY)
                    .connection(props.getMediaIp())
                    .realtime()
                    .mediaPort(props.getHttpPort())
                    .payloadType("96")
                    .rtpmap("96", "PS")
                    .ssrc(event.getSsrc());
            client().respondInvite(event.getRawRequestEvent(), answer);
            /*
             * 传输方式必须跟上级 SDP 走。
             *
             * 标准 9.2 用 m= 的协议字段区分承载：RTP/AVP 是 UDP，TCP/RTP/AVP 是 TCP；
             * a=setup:passive 表示对端被动监听、需要我们主动连过去，a=setup:active
             * 则相反。公网级联里上级经常只肯收 TCP（NAT 下 UDP 双向不通），
             * 此时仍按 UDP 推会一个包都到不了对端，上级就在超时后报
             * 「none rtp data receive」。
             */
            boolean tcp = isTcpMedia(event.getSdp());
            log.info("级联上行点播已应答: 上级={} 通道={} → 推流目标 {}:{} 传输={}",
                    platformId, channelId, dstIp, dstPort, tcp ? "TCP主动" : "UDP主动");
            // 交给业务方：启动本地流，并调用 ZlmMediaServer#startSendRtpToSource
            SipCallback delegate = callbackDelegate;
            if (delegate == null) {
                // 业务方未挂载：没有它就无法启动流与推流，直接挂断比让上级干等更好
                log.warn("未挂载业务回调，无法完成级联上行推流: channel={}", channelId);
                return;
            }
            delegate.onUplinkCascadePlay(CascadeEvents.UplinkPlay.builder()
                    .platformId(platformId)
                    .channelId(channelId)
                    .dstIp(dstIp)
                    .dstPort(dstPort)
                    .ssrc(event.getSsrc())
                    .callId(event.getCallId())
                    .tcp(tcp)
                    .build());
        } catch (Exception e) {
            log.error("级联上行点播处理失败: channel={}", channelId, e);
            reject(event, SDP_NOT_ACCEPTABLE);
        }
    }

    /**
     * 判断上级 SDP 是否要求用 TCP 承载 RTP。
     *
     * <p>规则（标准 9.2 与附录 G）：
     * <ul>
     *   <li>{@code m=video <port> TCP/RTP/AVP ...} → TCP；否则 UDP</li>
     *   <li>{@code a=setup:active} 表示<b>对端主动连我们</b>，即我们需要被动监听
     *       —— 当前推流实现只支持「我们主动连过去」，遇到这种情况按 TCP 主动处理
     *       会连不上，所以退回 UDP 更不容易出错；只有 {@code passive}（或未声明，
     *       国标惯例默认上级被动）才真正走 TCP 主动</li>
     * </ul>
     */
    private static boolean isTcpMedia(io.github.rurulalanana.gb28181.sip.sdp.SdpMessage sdp) {
        if (sdp == null || sdp.getMediaProto() == null) {
            return false;
        }
        if (!sdp.getMediaProto().toUpperCase(java.util.Locale.ROOT).contains("TCP")) {
            return false;
        }
        String setup = sdp.getSetup();
        // a=setup:active：对端要求它自己主动连，我们做被动端，当前不支持
        return setup == null || !"active".equalsIgnoreCase(setup.trim());
    }

    /** 语音广播 INVITE（设备按标准发起）。 */
    private void handleBroadcastInvite(InviteEvent event) {        BroadcastService broadcast = broadcastService;
        String deviceId = event.getPeer() == null ? null : event.getPeer().getDeviceId();
        if (broadcast == null || deviceId == null) {
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }
        RtpSession session = broadcast.onBroadcastInvite(deviceId, event.getCallId());
        if (session == null) {
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }
        client().respondInvite(event.getRawRequestEvent(), broadcast.buildAnswer(deviceId, session));
        log.info("语音广播已应答: device={} port={}", deviceId, session.getPort());
    }

    // ==================== 内部 ====================

    private io.github.rurulalanana.gb28181.sip.Gb28181SipClient client() {
        Gb28181SipServer server = sipServer;
        if (server == null) {
            throw new IllegalStateException("信令服务器未装配（attach 未调用）");
        }
        return server.client();
    }

    String resolveChannelId(InviteEvent event) {
        String fromSubject = parseSenderId(event.getSubject());
        if (fromSubject != null && !props.getPlatformId().equals(fromSubject)) {
            return fromSubject;
        }
        String receiver = parseReceiverId(event.getSubject());
        if (receiver != null) {
            return receiver;
        }
        String deviceId = event.getPeer() == null ? null : event.getPeer().getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("无法从 INVITE 解析通道编码: subject=" + event.getSubject());
        }
        return deviceId;
    }

    static String parseSenderId(String subject) {
        if (subject == null || subject.isEmpty()) {
            return null;
        }
        String pair = subject.split(",")[0];
        int colon = pair.indexOf(':');
        String sender = colon > 0 ? pair.substring(0, colon) : pair;
        return sender.trim().isEmpty() ? null : sender.trim();
    }

    static String parseReceiverId(String subject) {
        if (subject == null || subject.isEmpty()) {
            return null;
        }
        String pair = subject.split(",")[0];
        int colon = pair.indexOf(':');
        if (colon <= 0 || colon + 1 >= pair.length()) {
            return null;
        }
        return pair.substring(colon + 1).trim();
    }

    private boolean isTcpTransport(InviteEvent event) {
        String proto = event.getSdp().getMediaProto();
        return proto != null && proto.toUpperCase().contains("TCP");
    }

    /** 构造收流应答 SDP（实时 / 下载通用）：PS over RTP，{@code y=} 取会话 SSRC。 */
    SdpMessage buildAnswerSdp(RtpSession session, boolean tcp) {
        SdpMessage sdp = new SdpMessage()
                .origin(props.getPlatformId(), props.getMediaIp())
                .sessionName(session.getType().sdpSessionName())
                .connection(props.getMediaIp())
                .realtime()
                .mediaPort(session.getPort())
                .mediaProto(tcp ? SdpMessage.PROTO_TCP : SdpMessage.PROTO_UDP)
                // 国标约定：平台下行 INVITE 时 pt=96 固定为 PS 封装，
                // 并同时宣告 97/98/99（mpeg4/H264/H265）供设备选择内部编码。
                // 实际验证（WVP-GB28181-pro 与 EasyGBD_Demo）：只宣告 H264 会让
                // 设备解析 SDP 成功后回 500 —— 它按 PS 封装推流。
                .payloadType("96 97 98 99")
                .rtpmap("96", "PS")
                .rtpmap("98", "H264")
                .rtpmap("97", "MPEG4")
                .rtpmap("99", "H265")
                .recvonly()
                .ssrc(session.getSsrc());
        if (session.getMediaUri() != null) {
            sdp.uri(session.getMediaUri());
        }
        return sdp;
    }

    /**
     * 构造历史回放 SDP（标准 9.8）：{@code s=Playback} + {@code u=文件路径} + {@code t=时间段}。
     */
    /**
     * 构造历史回放的 SDP offer（标准 9.8 / 附录 G）。
     *
     * <p>字段取值对照 WVP-GB28181-pro 的 {@code playbackStreamCmd}
     * （实测与 LiveGBS、海康、大华互通）：
     * <pre>
     * v=0
     * o=&lt;平台编码&gt; 0 0 IN IP4 &lt;收流地址&gt;
     * s=Playback
     * u=&lt;通道编码&gt;:0            ← 按时间回放；按文件回放时填真实 FilePath
     * c=IN IP4 &lt;收流地址&gt;
     * t=&lt;起始 UNIX 秒&gt; &lt;结束 UNIX 秒&gt;   ← 必须是整数！
     * m=video &lt;端口&gt; RTP/AVP 96 97 98 99
     * a=recvonly
     * a=rtpmap:96 PS/90000 …
     * y=&lt;SSRC&gt;
     * </pre>
     *
     * <p><b>三个曾经踩过的坑</b>：
     * <ol>
     *   <li>{@code t=} 写成 ISO 时间字符串 → LiveGBS 直接回
     *       {@code 400 parse invite sdp error ... strconv.ParseInt}，
     *       设备根本不会推流。必须用 UNIX 秒。</li>
     *   <li>{@code u=} 写时间区间 → 非标准。按时间回放时标准写法是
     *       {@code <通道编码>:0}，设备据此自己按 {@code t=} 定位录像。</li>
     *   <li>只宣告 pt=96 且漏掉 {@code a=recvonly} → 部分设备解析后回 500。
     *       实时点播的 SDP 早就写成 96 97 98 99 + recvonly 且稳定工作，
     *       回放没有理由不同。</li>
     * </ol>
     */
    SdpMessage buildPlaybackSdp(RtpSession session, RecordItem item) {
        return buildPlaybackSdp(session, item, SPEED_UNLIMITED);
    }

    /**
     * 构造回放 / 下载 INVITE 的 SDP。
     *
     * @param downloadSpeed 请求设备按几倍速推送；{@link #SPEED_UNLIMITED}（1）
     *                      表示<b>不下发</b> {@code a=downloadspeed}，即按实时速度推流
     */
    SdpMessage buildPlaybackSdp(RtpSession session, RecordItem item, int downloadSpeed) {
        long start = RecordItem.epochSecond(item.getStartTime());
        long end = RecordItem.epochSecond(item.getEndTime());
        // t= 为「会话活跃时间」：结束时间未知时退化为起点（标准允许）
        String timeRange = (start <= 0 ? 0 : start) + " "
                + (end <= 0 ? Math.max(start, 0) : end);
        /*
         * u= 的语义：
         *   - 设备给了真实 FilePath → 按文件回放，填路径（2022 版 A.2.5.3）
         *   - 否则 → 按时间回放，填「通道编码:0」（2016 版 9.8 的写法，设备
         *     据此结合 t= 自行定位录像段）
         * session.getMediaUri() 可能被写成内部的「起/止时间」身份串，
         * 那不是合法 u=，所以这里以 item.getFilePath() 为准。
         */
        String uri = item.getFilePath();
        if (uri == null || uri.trim().isEmpty()) {
            String channel = session.getChannelId() == null
                    ? session.getDeviceId() : session.getChannelId();
            uri = (channel == null ? "" : channel) + ":0";
        }
        SdpMessage sdp = new SdpMessage()
                .origin(props.getPlatformId(), props.getMediaIp())
                /*
                 * 即使带倍速，会话类型仍用 s=Playback。
                 *
                 * <p><b>为什么不改成 s=Download</b>：实测过一次，收益为零、
                 * 副作用明确：
                 * <ul>
                 *   <li>速率没变 —— 录到的仍是 59 KB/s（约 1 倍速），
                 *       说明设备忽略 {@code a=downloadspeed} 与会话类型无关；</li>
                 *   <li>设备在 200 OK 里回 {@code t=0 0} +
                 *       {@code a=filesize:1050811420}，即<b>它打算推整个录像文件</b>，
                 *       而不是我们 {@code t=} 请求的那段时间。于是「下载
                 *       23:10~23:13」拿到的会是文件开头的内容，时间点完全错位。</li>
                 * </ul>
                 * 内容正确优先于「形式上更标准」，所以保持 Playback。
                 */
                .sessionName(SdpMessage.S_PLAYBACK)
                .uri(uri)
                .connection(props.getMediaIp())
                .time(timeRange)
                .mediaPort(session.getPort())
                .mediaProto(SdpMessage.PROTO_UDP)
                .payloadType("96 97 98 99")
                .rtpmap("96", "PS")
                .rtpmap("98", "H264")
                .rtpmap("97", "MPEG4")
                .rtpmap("99", "H265")
                .recvonly()
                .ssrc(session.getSsrc());
        /*
         * 只在确实要倍速时才写 a=downloadspeed。
         *
         * <p>普通回放（1 倍速）不写：一是与改造前的报文完全一致，
         * 不会让「看回放」被设备当成下载请求；二是部分设备严格校验
         * 倍速档位，写 1 反而可能被拒。
         */
        if (downloadSpeed > SPEED_UNLIMITED) {
            sdp.downloadspeed(downloadSpeed);
        }
        return sdp;
    }

    private void reject(InviteEvent event, int statusCode) {
        try {
            client().rejectInvite(event.getRawRequestEvent(), statusCode);
        } catch (Exception e) {
            log.error("拒绝 INVITE 失败: callId={} code={}", event.getCallId(), statusCode, e);
        }
    }

    /**
     * 对端是否为已注册的设备。
     *
     * <p>注册表不可用时<b>视为已注册</b>（放行）：宁可少拒一次，
     * 也不要因为装配顺序问题把所有设备点播挡在门外 —— 那会把一个
     * 「公网扫描骚扰」问题升级成「平台完全不可用」。
     */
    private boolean isRegisteredDevice(String peerId) {
        Gb28181SipServer server = sipServer;
        if (server == null) {
            return true;
        }
        try {
            // 不用 var：本模块的编译目标低于 Java 10，var 会报「找不到符号」
            io.github.rurulalanana.gb28181.sip.device.DeviceRegistry registry =
                    server.deviceRegistry();
            return registry != null && registry.get(peerId) != null;
        } catch (Exception e) {
            log.debug("查询设备注册表失败，本次放行: peer={} {}", peerId, e.getMessage());
            return true;
        }
    }

    /**
     * 被拒绝的 INVITE 的限流日志。
     *
     * <p>公网扫描器会持续打同一个端口（本机实测约 30 次/小时、24 小时不停），
     * 每次都打 WARN 会在一天里刷出几百行、一年几十万行，把真正有用的日志
     * 埋掉。这里按 <b>来源 IP + 拒绝原因</b> 限流：同一个来源 5 分钟内只打一条，
     * 并带上累计次数，既不丢信息也不刷屏。
     *
     * <p>日志里必须带<b>来源 IP 与 Request-URI</b>：只写
     * 「暂不支持的 INVITE 类型: s=-」等于没说，
     * 运维既看不出是谁在打、也分不清是自己的设备还是外部扫描。
     */
    private void logRejectThrottled(String peerId, InviteEvent event, String reason) {
        String ip = event.getPeer() == null ? "?" : event.getPeer().getIp();
        String key = ip + "|" + reason;
        long now = System.currentTimeMillis();
        Long lastAt = lastRejectLogAt.get(key);
        int hits = lastRejectLogCount.merge(key, 1, Integer::sum);
        if (lastAt != null && now - lastAt < REJECT_LOG_INTERVAL_MS) {
            return;
        }
        lastRejectLogAt.put(key, now);
        lastRejectLogCount.put(key, 0);
        log.warn("拒绝 INVITE: {} 来源={} 对端编码={} s={} subject={} callId={}（该来源累计 {} 次，同期同类已折叠）",
                reason, ip, peerId,
                event.getSdp() == null ? null : event.getSdp().getSessionName(),
                event.getSubject(), event.getCallId(), hits);
        // 避免 Map 无限增长：清理过期条目
        if (lastRejectLogAt.size() > 256) {
            long deadline = now - REJECT_LOG_INTERVAL_MS;
            lastRejectLogAt.entrySet().removeIf(e -> e.getValue() < deadline);
            lastRejectLogCount.keySet().retainAll(lastRejectLogAt.keySet());
        }
    }

    /** 回放控制命令（标准附录 D 的 {@code u=} 取值）。 */
    public enum PlaybackCommand {
        /** 播放（从当前位置继续）。 */
        RESUME,
        /** 暂停。 */
        PAUSE,
        /** 跳转到指定偏移（秒）。 */
        SEEK,
        /** 倍速。 */
        SPEED,
        /** 结束回放（由 BYE 完成，此处保留语义占位）。 */
        STOP
    }
}