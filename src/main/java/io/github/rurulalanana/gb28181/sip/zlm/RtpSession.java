package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.structure.MK_RTP_SERVER;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一路 GB28181 收流会话。
 *
 * <p>一个会话对应 ZLM 中一个独占端口的 RTP 服务器（{@code mk_rtp_server_create2}），
 * 生命周期：协商中（{@link #NEGOTIATING}）→ 有流（{@link #ONLINE}）→ 已关闭（{@link #CLOSED}）。
 *
 * <p>会话对象同时强引用 JNA detach 回调，防止回调被 GC 后原生侧调用崩溃。
 */
public class RtpSession {

    /** SIP 已应答 / 已发起 INVITE，等待首个 RTP 包。 */
    public static final String NEGOTIATING = "NEGOTIATING";
    /** 已收到 RTP 并成功解出轨道（ZLM on_mk_media_publish 事件）。 */
    public static final String ONLINE = "ONLINE";
    /** 会话已关闭（BYE / 主动停止）。 */
    public static final String CLOSED = "CLOSED";

    /** 通道编码（GB28181 DeviceID，同时作为 ZLM 流名）。 */
    private final String channelId;

    /** ZLM RTP 会话序号（同一通道多次点播时递增）。 */
    private final int streamIndex;
    /** 设备编码（SIP 层设备 ID，可能与通道相同）。 */
    private final String deviceId;
    /** 平台侧 SSRC（SDP {@code y=} 行，按设备编码生成）。 */
    private final String ssrc;
    /** 设备声明的 SSRC（RTP 过滤用；null/0 = 不过滤）。 */
    private final String deviceSsrc;
    /** 会话类型：实时 / 回放 / 下载。 */
    private final MediaType type;
    /** 回放或下载的媒体标识（SDP {@code u=} 行）：设备录像的 FilePath 等。 */
    private final String mediaUri;
    /** 传输模式：0 = UDP，1 = TCP 被动（ZLM 监听，设备接入）。 */
    private final int tcpMode;
    /** SIP Call-ID（BYE 时按此关闭）。 */
    private final String callId;
    /** ZLM RTP 服务器句柄。 */
    private final MK_RTP_SERVER handle;
    /** 实际监听端口。 */
    private final int port;

    private volatile String state = NEGOTIATING;
    private final AtomicBoolean detached = new AtomicBoolean(false);
    private final long createdAt = System.currentTimeMillis();

    public RtpSession(String channelId, String deviceId, String ssrc, String deviceSsrc,
                      int tcpMode, String callId, MK_RTP_SERVER handle, int port,
                      MediaType type, String mediaUri) {
        this(channelId, deviceId, ssrc, deviceSsrc, tcpMode, callId, handle, port, type, mediaUri, 0);
    }

    public RtpSession(String channelId, String deviceId, String ssrc, String deviceSsrc,
                      int tcpMode, String callId, MK_RTP_SERVER handle, int port,
                      MediaType type, String mediaUri, int streamIndex) {
        this.channelId = channelId;
        this.streamIndex = streamIndex;
        this.deviceId = deviceId;
        this.ssrc = ssrc;
        this.deviceSsrc = deviceSsrc;
        this.tcpMode = tcpMode;
        this.callId = callId;
        this.handle = handle;
        this.port = port;
        this.type = type == null ? MediaType.REALTIME : type;
        this.mediaUri = mediaUri;
    }

    public MediaType getType() {
        return type;
    }

    /** 回放/下载的媒体标识（SDP {@code u=}）。 */
    public String getMediaUri() {
        return mediaUri;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getSsrc() {
        return ssrc;
    }

    public String getDeviceSsrc() {
        return deviceSsrc;
    }

    public int getTcpMode() {
        return tcpMode;
    }

    public String getCallId() {
        return callId;
    }

    public MK_RTP_SERVER getHandle() {
        return handle;
    }

    public int getPort() {
        return port;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * ZLM 流名（GB28181 场景）。
     *
     * <p>注意：ZLMediaKit 的 {@code mk_rtp_server_create2} 会把 RTP 会话
     * 注册为 {@code <channelId>/<序号>}（实测注册日志为
     * {@code rtsp://__defaultVhost__/34020000001310000001/0}），
     * 而非传入的裸 channelId。播放地址必须用本方法拼出的真实流名，
     * 否则会出现「媒体已注册但按裸 channelId 访问 404」。
     */
    public String getStream() {
        return channelId + "/" + streamIndex;
    }

    /** 裸通道编码（用于按通道查会话，不是 ZLM 流名）。 */
    public String getChannelIdForLookup() {
        return channelId;
    }

    /**
     * 标记 RTP 服务器已 detach（被关闭或超时销毁）。
     *
     * @return true 表示本次调用是首个标记者（调用方需负责后续清理；
     *         detach 回调与主动关闭只有一个路径会拿到 true）
     */
    public boolean markDetached() {
        return detached.compareAndSet(false, true);
    }

    public boolean isDetached() {
        return detached.get();
    }

    @Override
    public String toString() {
        return "RtpSession{channel=" + channelId + ", type=" + type + ", port=" + port
                + ", ssrc=" + ssrc + ", tcp=" + (tcpMode == 1) + ", state=" + state + '}';
    }
}
