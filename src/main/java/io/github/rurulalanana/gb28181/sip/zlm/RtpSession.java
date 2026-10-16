package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.callback.IMKRtpServerDetachCallBack;
import com.aizuda.zlm4j.structure.MK_RTP_SERVER;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;

/**
 * 一路 GB28181 收流会话。
 *
 * <p>生命周期：协商中（{@link #NEGOTIATING}）→ 有流（{@link #ONLINE}）→ 已关闭（{@link #CLOSED}）。
 *
 * <p><b>唯一标识是「设备编码 + 通道编码」</b>（{@link #getSessionKey()}），
 * 不是裸通道编码——通道编码只在单设备内唯一，跨设备会重复。
 *
 * <p><b>逻辑标识与 ZLM 命名是两套坐标</b>：
 * <ul>
 *   <li>{@link #getSessionKey()} = {@code <设备编码>/<通道编码>}，业务侧唯一键
 *       （数据库 {@code gb_stream.stream_key}、接口返回、日志），<b>不随收流模式变化</b>。</li>
 *   <li>{@link #getZlmApp()} / {@link #getStream()} = ZLM 媒体源的实际 {@code app/stream}，
 *       由收流模式决定：
 *       <ul>
 *         <li>独占端口（默认）：{@code app=设备编码}、{@code stream=通道编码}</li>
 *         <li>单端口：{@code app=rtp}（ZLM 固定值）、{@code stream=<设备编码>_<通道编码>}
 *             （由 {@code stream_replace} 改名而来，否则会是 SSRC 的十六进制）</li>
 *       </ul>
 *   </li>
 * </ul>
 * 两套坐标分开的原因：单端口模式下 ZLM 的 app 是写死的 {@code rtp}，无法承载设备编码，
 * 若让 {@link #getSessionKey()} 跟着变，数据库键与接口契约都会被收流模式污染。
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

    /** 通道编码（GB28181 DeviceID，作为 ZLM 的<b>流名</b>）。 */
    private final String channelId;

    /**
     * ZLM 应用名。
     *
     * <p>独占端口模式下是<b>设备编码</b>；单端口模式下是 ZLM 写死的 {@code rtp}
     * （复用分支的 tuple 来自 server 级默认值，{@code stream_replace} 改不到 app）。
     *
     * <p><b>为什么独占端口要用设备编码而不是固定 app</b>：通道编码只在单个设备内唯一，
     * 不同设备可以出现相同通道编码（标准只保证设备编码全局唯一）。
     * 若 ZLM 命名空间只用通道编码，两台设备点播同号通道时会撞在同一个
     * {@code app/stream} 上——后建的流会因 {@code media source already existed}
     * 注册失败，两台设备的会话互相顶掉。
     *
     * <p>注意：这是 <b>ZLM 侧</b>的命名，与业务侧唯一键 {@link #getSessionKey()} 无关。
     */
    private final String zlmApp;

    /**
     * ZLM 流名。
     *
     * <p>独占端口模式下是<b>通道编码</b>；单端口模式下是
     * {@code <设备编码>_<通道编码>}（由推流回调经 {@code stream_replace} 归一，
     * 否则 ZLM 会用 SSRC 的十六进制串当流名）。
     *
     * <p><b>不含会话序号</b>：同一通道重复点播时 ZLM 复用的是同一个
     * {@code app/stream}（旧源先被关闭），流名恒定。历史上曾拼过
     * {@code <channelId>/<序号>}，那是把 {@code stream_id} 误当成 ssrc 过滤器的产物。
     */
    private final String zlmStream;

    /** 设备编码（SIP 层设备 ID）。 */
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
    /**
     * SIP Call-ID（BYE 时按此关闭）。
     *
     * <p>设备主动 INVITE 时构造即确定；平台主动点播时先建会话、后发 INVITE，
     * 构造传 null，由 {@link #setCallId} 回填，故此处不能是 final。
     */
    private volatile String callId;
    /** ZLM RTP 服务器句柄。 */
    private final MK_RTP_SERVER handle;
    /** 实际监听端口。 */
    private final int port;

    private volatile String state = NEGOTIATING;

    /** 对端 To-tag（设备 200 OK 中的 tag），发 BYE 时需要。 */
    private volatile String toTag;
    private final AtomicBoolean detached = new AtomicBoolean(false);
    private final long createdAt = System.currentTimeMillis();

    /**
     * JNA detach 回调的强引用。
     *
     * <p><b>为什么必须持有</b>：JNA 只以 {@code WeakHashMap} 弱引用登记回调
     * （{@code CallbackReference extends WeakReference<Callback>}），回调对象一旦
     * 被 GC，JNA 会回收其原生 trampoline，原生侧再调用即崩溃或静默丢失。
     * 对应 zlm4j 开发规范：常驻内存的 native 资源需由 Java 侧持有并提供释放方法。
     *
     * <p>生命周期与 ZLM RTP 服务器一致：创建会话时置入，
     * release / detach 后由 {@link #clearDetachCallback()} 清空。
     */
    private volatile IMKRtpServerDetachCallBack detachCallback;

    public RtpSession(String channelId, String deviceId, String ssrc, String deviceSsrc,
                      int tcpMode, String callId, MK_RTP_SERVER handle, int port,
                      MediaType type, String mediaUri) {
        this(channelId, deviceId, ssrc, deviceSsrc, tcpMode, callId, handle, port,
                type, mediaUri, null, null);
    }

    /**
     * 完整构造。
     *
     * @param zlmApp    ZLM 应用名；{@code null} 表示按独占端口模式取设备编码
     * @param zlmStream ZLM 流名；{@code null} 表示按独占端口模式取通道编码
     */
    public RtpSession(String channelId, String deviceId, String ssrc, String deviceSsrc,
                      int tcpMode, String callId, MK_RTP_SERVER handle, int port,
                      MediaType type, String mediaUri, String zlmApp, String zlmStream) {
        this.channelId = channelId;
        this.deviceId = deviceId;
        // 设备编码缺失时退回通道编码：单设备场景下二者相同，
        // 至少保证 app 非空（ZLM 要求 app/stream 均非空）
        String fallbackApp = StringUtils.isBlank(deviceId) ? channelId : deviceId;
        this.zlmApp = StringUtils.isBlank(zlmApp) ? fallbackApp : zlmApp;
        this.zlmStream = StringUtils.isBlank(zlmStream) ? channelId : zlmStream;
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

    /**
     * 回填 Call-ID（平台主动点播：INVITE 发出后调用）。
     *
     * <p>必须同时写回会话对象，不能只更新 {@code RtpSessionManager} 的索引：
     * 无人观看关流与索引解绑都读 {@link #getCallId()}，若恒为 null 则
     * 发不出 BYE（设备继续推流）、索引条目也永远摘不掉。
     */
    public void setCallId(String callId) {
        this.callId = callId;
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

    /** ZLM 流名，见 {@link #zlmStream}。 */
    public String getStream() {
        return zlmStream;
    }

    /**
     * ZLM 应用名，见 {@link #zlmApp}。
     *
     * <p>与 {@link #getStream()} 一起构成 ZLM 媒体源的唯一标识，
     * 也是 {@code mk_media_source_for_each} / {@code close_streams} 的定位参数。
     */
    public String getZlmApp() {
        return zlmApp;
    }

    /**
     * 会话唯一键：{@code <设备编码>/<通道编码>}，历史流（回放/下载）再拼类型后缀。
     *
     * <p><b>这是业务侧坐标，不是 ZLM 的 {@code app/stream}</b>：单端口模式下
     * ZLM 的 app 固定为 {@code rtp}、stream 为 {@code <设备>_<通道>}，
     * 但本键恒定不变，数据库 {@code gb_stream.stream_key} 与接口返回都依赖它。
     *
     * <p>不可退化成裸通道编码：通道编码只在设备内唯一，跨设备会重复（见 {@link #zlmApp}）。
     *
     * <p><b>为什么历史流要加后缀</b>：直播与回放共用同一键时，两者在 ZLM 里
     * 也共用同一 {@code app/stream}，而一个 {@code app/stream} 只能有一个媒体源 ——
     * 「有人看直播时另一人点回放」会把正在观看的直播踢掉。加后缀后两者是
     * 独立会话、独立媒体源，可并存。直播（{@code REALTIME}）后缀为空，
     * 键保持原样，不影响既有调用与数据库记录。
     */
    public String getSessionKey() {
        return zlmAppOf(deviceId, channelId) + "/" + channelId
                + RtpSessionManager.typeSuffix(type);
    }

    /** 设备编码归一（缺失时退回通道编码，保证非空）。 */
    private static String zlmAppOf(String deviceId, String channelId) {
        return StringUtils.isBlank(deviceId) ? channelId : deviceId;
    }

    /**
     * 标记 RTP 服务器已 detach（被关闭或超时销毁）。
     *
     * @return true 表示本次调用是首个标记者（调用方需负责后续清理；
     *         detach 回调与主动关闭只有一个路径会拿到 true）
     */
    public String getToTag() {
        return toTag;
    }

    public void setToTag(String toTag) {
        this.toTag = toTag;
    }

    public boolean markDetached() {
        return detached.compareAndSet(false, true);
    }

    /** 置入 JNA detach 回调强引用（见 {@link #detachCallback}）。 */
    public void setDetachCallback(IMKRtpServerDetachCallBack callback) {
        this.detachCallback = callback;
    }

    /**
     * 释放 JNA detach 回调强引用。
     *
     * <p>RTP 服务器销毁后回调不会再触发，此处清空让回调对象可被回收
     * （JNA 的 Cleaner 会随之释放原生 trampoline）。
     */
    public void clearDetachCallback() {
        this.detachCallback = null;
    }

    public boolean isDetached() {
        return detached.get();
    }

    @Override
    public String toString() {
        return "RtpSession{key=" + getSessionKey() + ", type=" + type + ", port=" + port
                + ", ssrc=" + ssrc + ", tcp=" + (tcpMode == 1) + ", state=" + state + '}';
    }
}
