package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SdpEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
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

    private final ZlmMediaServer mediaServer;
    private final RtpSessionManager sessionManager;
    private final MediaServerProperties props;

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

        MediaType type = MediaType.ofSessionName(event.getSdp().getSessionName());
        if (type == MediaType.REALTIME && !event.isPlay()) {
            log.warn("暂不支持的 INVITE 类型: s={} callId={}",
                    event.getSdp().getSessionName(), event.getCallId());
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
        String channel = StringUtils.isBlank(channelId) ? deviceId : channelId;
        RtpSession session = sessionManager.open(channel, deviceId, null, 0, null,
                MediaType.PLAYBACK, item.getFilePath());
        try {
            SdpMessage offer = buildPlaybackSdp(session, item);
            // 同实时点播：Request-URI / To / Subject 填通道编码
            String callId = client().invitePlayback(deviceId, channel, offer);
            sessionManager.bindCallId(session, callId);
            log.info("已发起回放 INVITE: {} file={} callId={} playUrls={}",
                    session, item.getFilePath(), callId, mediaServer.playUrls(session));
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
     * @param deviceId  设备编码（SIP 目标）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @param filePath  设备侧文件路径（来自 RecordInfo 的 FilePath）
     */
    public RtpSession download(String deviceId, String channelId, String filePath) {
        String channel = StringUtils.isBlank(channelId) ? deviceId : channelId;
        RtpSession session = sessionManager.open(channel, deviceId, null, 0, null,
                MediaType.DOWNLOAD, filePath);
        try {
            SdpMessage offer = buildAnswerSdp(session, false);
            // Subject 传 null：由 sip 模块按标准生成
            // <通道编码>:<SSRC>,<平台编码>:0（此处 SDP 的 y= 已由 buildAnswerSdp 填好）
            String callId = client().invite(deviceId, channel, offer, null);
            sessionManager.bindCallId(session, callId);
            log.info("已发起下载 INVITE: {} file={} callId={}",
                    session, filePath, callId);
            return session;
        } catch (RuntimeException e) {
            // 同实时点播：INVITE 失败必须回滚，否则留下永不 ONLINE 的僵尸会话
            sessionManager.abort(session.getSessionKey());
            throw e;
        }
    }

    /** 平台主动文件下载（设备直连：设备编码即通道编码）。 */
    public RtpSession download(String deviceId, String filePath) {
        return download(deviceId, deviceId, filePath);
    }

    /**
     * 历史回放控制（标准附录 D，RFC 2976）：播放 / 暂停 / 跳转 / 倍速。
     *
     * @param callId 回放 INVITE 的 Call-ID
     * @param from   跳转目标时间（SEEK 使用）
     * @param scale  倍速（SPEED 使用，1.0 为正常）
     */
    public void playbackControl(String deviceId, String callId, PlaybackCommand command,
                                Date from, float scale) {
        SdpMessage sdp = new SdpMessage()
                .origin(props.getPlatformId(), props.getMediaIp())
                .uri(command.command())
                .sessionName("Playback")
                .realtime();
        if (command == PlaybackCommand.SEEK && from != null) {
            String text = RecordItem.formatTime(from);
            sdp.time(text + " " + text);
        }
        if (command == PlaybackCommand.SPEED) {
            sdp.attr("Scale: " + scale);
        }
        // 通道编码从会话取：INFO 必须在 INVITE 对话框内（RFC 2976），
        // Request-URI / To 要与回放 INVITE 一致（都是通道编码），
        // 而调用方只持有 callId 与设备编码，此处按 callId 反查通道
        RtpSession session = sessionManager.getByCallId(callId);
        String channel = session == null ? deviceId : session.getChannelId();
        client().info(deviceId, channel, callId, sdp);
        log.info("发送回放控制: channel={} callId={} command={} scale={}",
                channel, callId, command.command(), scale);
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
        recordCallbacks.put(deviceId, new AtomicReference<>(onRecords));
        client().queryRecordInfo(deviceId, startTime, endTime);
    }

    @Override
    public void onManscdpResponse(ManscdpEvent event) {
        if (event == null || event.getManscdp() == null) {
            return;
        }
        String deviceId = event.getManscdp().getDeviceId();
        AtomicReference<Consumer<List<RecordItem>>> ref =
                deviceId == null ? null : recordCallbacks.remove(deviceId);
        if (ref != null && ref.get() != null) {
            List<RecordItem> items = RecordItem.parse(event);
            log.info("收到录像检索结果: device={} items={}", deviceId, items.size());
            ref.get().accept(items);
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

    /** 语音广播 INVITE（设备按标准发起）。 */
    private void handleBroadcastInvite(InviteEvent event) {
        BroadcastService broadcast = broadcastService;
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
    SdpMessage buildPlaybackSdp(RtpSession session, RecordItem item) {
        SdpMessage sdp = new SdpMessage()
                .origin(props.getPlatformId(), props.getMediaIp())
                .sessionName(SdpMessage.S_PLAYBACK)
                .uri(item.getFilePath())
                .connection(props.getMediaIp())
                .mediaPort(session.getPort())
                .mediaProto(SdpMessage.PROTO_UDP)
                .payloadType("96")
                .rtpmap("96", "PS")
                .ssrc(session.getSsrc());
        String start = RecordItem.formatTime(item.getStartTime());
        String end = RecordItem.formatTime(item.getEndTime());
        sdp.time((start == null ? "0" : start) + " " + (end == null ? "0" : end));
        if (item.getFileSize() > 0) {
            sdp.filesize(item.getFileSize());
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

    /** 回放控制命令（标准附录 D 的 {@code u=} 取值）。 */
    public enum PlaybackCommand {
        /** 播放/继续。 */
        PLAY("play"),
        /** 暂停。 */
        PAUSE("pause"),
        /** 跳转到指定时间。 */
        SEEK("seek"),
        /** 倍速（配合 {@code a=Scale:}）。 */
        SPEED("play"),
        /** 结束回放。 */
        STOP("stop");

        private final String command;

        PlaybackCommand(String command) {
            this.command = command;
        }

        public String command() {
            return command;
        }
    }
}