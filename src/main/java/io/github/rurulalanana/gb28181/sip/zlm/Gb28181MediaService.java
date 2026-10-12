package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GB28181 流媒体服务：把 gb28181-sip 的信令事件桥接到 ZLMediaKit 收流。
 *
 * <p><b>实时点播（设备发起 INVITE）</b>：
 * <ol>
 *   <li>{@link #onInvite} 收到 INVITE → 为通道创建 ZLM RTP 服务器（独占端口）</li>
 *   <li>回复 200 OK，SDP 携带 ZLM 媒体地址 / 端口 / 平台 SSRC（{@code a=rtpmap:96 PS/90000}）</li>
 *   <li>设备向该端口推 RTP/PS → ZLM 解复用并发布为多协议流（FLV/HLS/WebRTC/RTSP/RTMP）</li>
 *   <li>BYE / 主动 stop → 释放 RTP 服务器</li>
 * </ol>
 *
 * <p><b>实时点播（平台主动发起）</b>：{@link #play(String)} 先分配 RTP 服务器，
 * 再通过信令模块 {@code client().invitePlay(deviceId, sdp)} 向设备发起 INVITE。
 *
 * <p>回调运行在 SIP 协议栈线程上，本类不做阻塞操作；ZLM 调用均为本地 JNI，毫秒级返回。
 */
public class Gb28181MediaService implements SipCallback {

    private static final Logger log = LoggerFactory.getLogger(Gb28181MediaService.class);

    /** 标准拒绝码：SDP 无法满足（附录 G 注 17）。 */
    private static final int SDP_NOT_ACCEPTABLE = 488;

    private final ZlmMediaServer mediaServer;
    private final RtpSessionManager sessionManager;
    private final MediaServerProperties props;

    /** 信令服务器（start 后由平台装配），用于 respondInvite / invitePlay。 */
    private volatile Gb28181SipServer sipServer;

    /** 设备上下线监听者（可选）。 */
    public interface DeviceListener {
        void onRegister(RegisterEvent event);

        void onKeepAlive(KeepAliveEvent event);
    }

    private volatile DeviceListener deviceListener;

    public Gb28181MediaService(ZlmMediaServer mediaServer, RtpSessionManager sessionManager,
                               MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.sessionManager = sessionManager;
        this.props = props;
    }

    /** 平台装配：注入信令服务器（提供 client）。 */
    public void attach(Gb28181SipServer sipServer) {
        this.sipServer = sipServer;
    }

    public void setDeviceListener(DeviceListener listener) {
        this.deviceListener = listener;
    }

    public RtpSessionManager sessionManager() {
        return sessionManager;
    }

    // ==================== SIP 回调 ====================

    @Override
    public void onInvite(InviteEvent event) {
        if (event.getSdp() == null) {
            log.warn("INVITE 无 SDP，拒绝: callId={} peer={}", event.getCallId(), event.getPeer());
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }
        if (!event.isPlay()) {
            // 回放 / 下载需要 ZLM 侧录像检索配合，v1 仅支持实时点播
            log.warn("暂不支持的 INVITE 类型（仅实时点播）: s={} callId={}",
                    event.getSdp().getSessionName(), event.getCallId());
            reject(event, SDP_NOT_ACCEPTABLE);
            return;
        }

        String channelId = resolveChannelId(event);
        boolean tcp = isTcpTransport(event);
        int tcpMode = tcp ? RtpSessionManager.TCP_MODE_PASSIVE : 0;

        try {
            RtpSession session = sessionManager.open(channelId, event.getPeer().getDeviceId(),
                    event.getSsrc(), tcpMode, event.getCallId());
            SdpMessage answer = buildAnswerSdp(session, tcp);
            client().respondInvite(event.getRawRequestEvent(), answer);
            log.info("已应答 INVITE(200 OK): channel={} media={}:{} ssrc={} playUrls={}",
                    channelId, props.getMediaIp(), session.getPort(), session.getSsrc(),
                    mediaServer.playUrls(session.getStream()));
        } catch (Exception e) {
            log.error("处理 INVITE 失败: channel={} callId={}", channelId, event.getCallId(), e);
            sessionManager.closeByChannel(channelId);
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

    @Override
    public void onBye(InviteEvent event) {
        RtpSession session = sessionManager.getByCallId(event.getCallId());
        if (session != null) {
            sessionManager.closeByCallId(event.getCallId());
            log.info("BYE 关闭会话: {}", session);
        } else {
            // 部分设备 BYE 的 Call-ID 与 INVITE 不一致时按通道兜底
            String channelId = resolveChannelId(event);
            if (sessionManager.getByChannel(channelId) != null) {
                sessionManager.closeByChannel(channelId);
                log.info("BYE 关闭会话(按通道兜底): channel={}", channelId);
            }
        }
    }

    @Override
    public void onRegister(RegisterEvent event) {
        if (event.isUnregistered()) {
            log.info("设备注销: deviceId={} ip={}", event.getDeviceId(), event.getPeer().getIp());
        } else {
            log.info("设备注册: deviceId={} ip={} expires={}",
                    event.getDeviceId(), event.getPeer().getIp(), event.getExpires());
        }
        DeviceListener l = deviceListener;
        if (l != null) {
            l.onRegister(event);
        }
    }

    @Override
    public void onKeepAlive(KeepAliveEvent event) {
        log.debug("心跳: deviceId={} status={}", event.getDeviceId(), event.getStatus());
        DeviceListener l = deviceListener;
        if (l != null) {
            l.onKeepAlive(event);
        }
    }

    // ==================== 平台主动点播 ====================

    /**
     * 平台主动向设备发起实时点播。
     *
     * <p>先分配 ZLM 收流端口，再用信令模块发送 INVITE（SDP 携带收流地址）。
     * 设备应答 200 OK 后即向该端口推流。
     *
     * @param deviceId 设备/通道编码
     * @return 会话（含播放地址）
     */
    public RtpSession play(String deviceId) {
        RtpSession session = sessionManager.open(deviceId, deviceId, null, 0, null);
        SdpMessage offer = buildAnswerSdp(session, false);
        String callId = client().invitePlay(deviceId, offer);
        sessionManager.bindCallId(session, callId);
        log.info("已发起点播 INVITE: channel={} media={}:{} callId={} playUrls={}",
                deviceId, props.getMediaIp(), session.getPort(), callId,
                mediaServer.playUrls(session.getStream()));
        return session;
    }

    /** 停止某通道的点播（释放收流端口；BYE 由业务侧按需发送）。 */
    public void stop(String deviceId) {
        sessionManager.closeByChannel(deviceId);
    }

    /** 某通道的播放地址（未点播返回 null）。 */
    public PlayUrls playUrls(String deviceId) {
        RtpSession session = sessionManager.getByChannel(deviceId);
        return session == null ? null : mediaServer.playUrls(session.getStream());
    }

    // ==================== 内部 ====================

    private io.github.rurulalanana.gb28181.sip.Gb28181SipClient client() {
        Gb28181SipServer server = sipServer;
        if (server == null) {
            throw new IllegalStateException("信令服务器未装配（attach 未调用）");
        }
        return server.client();
    }

    /**
     * 解析通道编码（同时作为 ZLM 流名）。
     *
     * <p>Subject 格式（标准 9.2）：{@code 发送者ID:接收者ID,发送者序号:接收者序号}。
     * 设备发起 INVITE 时发送者即通道；防御性地把平台编码换成为接收者。
     */
    String resolveChannelId(InviteEvent event) {
        String fromSubject = parseSenderId(event.getSubject());
        if (fromSubject != null && !props.getPlatformId().equals(fromSubject)) {
            return fromSubject;
        }
        // subject 缺失或首个编码是平台本身时，尝试取接收者 / 退回来路设备编码
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

    /** 设备 SDP 声明 TCP 承载时走 TCP 被动模式（ZLM 监听，设备接入）。 */
    private boolean isTcpTransport(InviteEvent event) {
        String proto = event.getSdp().getMediaProto();
        return proto != null && proto.toUpperCase().contains("TCP");
    }

    /**
     * 构造 200 OK 应答 SDP（GB28181 PS over RTP）：
     * <pre>
     * v=0
     * o={platformId} 0 0 IN IP4 {mediaIp}
     * s=Play
     * c=IN IP4 {mediaIp}
     * t=0 0
     * m=video {port} RTP/AVP 96
     * a=rtpmap:96 PS/90000
     * y={ssrc}
     * </pre>
     */
    SdpMessage buildAnswerSdp(RtpSession session, boolean tcp) {
        SdpMessage sdp = new SdpMessage()
                .origin(props.getPlatformId(), props.getMediaIp())
                .sessionName(SdpMessage.S_PLAY)
                .connection(props.getMediaIp())
                .realtime()
                .mediaPort(session.getPort())
                .mediaProto(tcp ? SdpMessage.PROTO_TCP : SdpMessage.PROTO_UDP)
                .payloadType("96")
                .rtpmap("96", "PS")
                .ssrc(session.getSsrc());
        if (tcp) {
            sdp.setup("passive");
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
}
