package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.callback.IMKRtpServerDetachCallBack;
import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_RTP_SERVER;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RTP 收流会话管理：一路通道一个独占端口的 ZLM RTP 服务器。
 *
 * <p><b>会话唯一标识是「设备编码 + 通道编码」</b>（{@code app=设备编码}、
 * {@code stream=通道编码}），不是裸通道编码——GB/T 28181 只保证设备编码全局唯一，
 * 通道编码只在单设备内唯一，跨设备可重复。
 *
 * <p>关闭路径有两类，均保证 {@code mk_rtp_server_release} 最多调用一次：
 * <ul>
 *   <li><b>主动关闭</b>（BYE / stop）：调用 release → 触发 ZLM on_detach 回调 → 只做账务清理</li>
 *   <li><b>ZLM 销毁</b>（如内部超时）：on_detach 回调 → 只做账务清理，不再 release</li>
 * </ul>
 *
 * <p>RTP 数据超时（默认 15s 无数据）只会让<b>流下线</b>（media source 注销），
 * RTP 服务器（端口）仍然保留等待设备恢复推流，直到会话被关闭。
 */
public class RtpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RtpSessionManager.class);

    /** TCP 被动模式（ZLM 监听，设备接入）。 */
    public static final int TCP_MODE_PASSIVE = 1;

    /** ZLM 默认 vhost（{@code general.enableVhost=0} 时唯一可用值）。 */
    private static final String DEFAULT_VHOST = "__defaultVhost__";

    /**
     * 单端口模式下 ZLM 固定的 app 名（对应 C 侧 {@code kRtpAppName = "rtp"}）。
     *
     * <p>复用分支的 tuple 来自 {@code RtpServer} 的默认参数，{@code stream_replace}
     * 只能改 stream，app 改不了，故单端口下所有源都挂在这个 app 下。
     */
    private static final String SINGLE_PORT_APP = "rtp";

    private final ZlmMediaServer mediaServer;
    private final MediaServerProperties props;

    /**
     * 会话索引，键为 {@code <设备编码>/<通道编码>}（见 {@link RtpSession#getSessionKey()}）。
     *
     * <p><b>不能只用通道编码作键</b>：通道编码仅在单设备内唯一，两台设备出现
     * 相同通道编码时（标准允许）会互相顶掉会话——后一次点播把前一次的会话
     * 当成「同通道旧会话」关掉，表现为另一台设备的画面突然中断。
     */
    private final Map<String, RtpSession> byChannel = new ConcurrentHashMap<>();
    private final Map<String, RtpSession> byCallId = new ConcurrentHashMap<>();

    /**
     * 按 ZLM 的 {@code app/stream} 索引会话（单端口模式下与 {@link #byChannel} 的键不同）。
     *
     * <p>单端口：ZLM 键为 {@code rtp/<设备>_<通道>}，而业务键是 {@code <设备>/<通道>}，
     * 两者不再相等，必须单独索引。
     */
    private final Map<String, RtpSession> byZlmKey = new ConcurrentHashMap<>();

    /**
     * 单端口模式的 SSRC 索引：SSRC（<b>数值</b>）→ 会话。
     *
     * <p>单端口下 ZLM 用 RTP 头里的 SSRC 分流并据此命名流，平台必须在推流回调里
     * 反查「这个 SSRC 属于哪个通道」才能把流名改回可读形式。
     *
     * <p>同时登记<b>平台 SSRC</b>（我方 SDP {@code y=} 宣告的）与<b>设备 SSRC</b>
     * （设备 SDP 声明的）：多数设备按平台宣告值推流，但部分设备坚持用自己的，
     * 两者都登记才能都认出来。
     *
     * <p><b>键是数值不是字符串</b>：SSRC 在 RTP 头里是 {@code uint32} 字段，
     * 设备会按数字处理并丢掉前导零——平台宣告 {@code 0200000001}（10 位十进制）
     * 时设备实际推的是 {@code 200000001}。用字符串当键会因前导零差异而匹配不上，
     * 表现为「SSRC 未匹配到会话」、流名退化成十六进制。
     */
    private final Map<Long, RtpSession> bySsrc = new ConcurrentHashMap<>();
    /**
     * 「流上线事件先于会话登记到达」的暂存。
     *
     * <p>{@code mk_rtp_server_create2} 一返回，RTP 服务器就开始监听，设备可能立刻
     * 推流并触发 ZLM 的流注册事件——而此时 {@link #byChannel} 还没 put 进会话，
     * {@link #findByStream} 查不到，事件会被丢掉，会话就永远停在 NEGOTIATING。
     * 先记在这里，等会话登记时补一次上线。键为 {@code <设备编码>/<通道编码>}。
     */
    private final Map<String, Boolean> pendingOnline = new ConcurrentHashMap<>();
    private final AtomicInteger ssrcSeq = new AtomicInteger(1);
    /** 固定端口范围的轮询游标。 */
    private final AtomicInteger nextPort = new AtomicInteger(0);

    /**
     * 会话键：{@code <设备编码>/<通道编码>}，与 ZLM 的 {@code app/stream} 一致。
     *
     * <p>缺失值统一按「设备直连」归一：设备编码为空取通道编码、通道编码为空取设备编码。
     * 否则 {@code stop(device, null)} 之类调用会拼出 {@code <设备>/null} 这种
     * 永远查不到会话的键（表现为「停止直播没反应」）。
     */
    static String sessionKey(String deviceId, String channelId) {
        String app = zlmAppOf(deviceId, channelId);
        String stream = channelOf(deviceId, channelId);
        return app + "/" + stream;
    }

    /**
     * ZLM app（= 设备编码）；设备编码缺失时退回通道编码。
     *
     * <p>二者都为空时返回 {@code null}（调用方应视为非法入参）。
     */
    static String zlmAppOf(String deviceId, String channelId) {
        return StringUtils.isBlank(deviceId) ? channelId : deviceId;
    }

    /**
     * ZLM stream（= 通道编码）；通道编码缺失时退回设备编码（设备直连）。
     *
     * <p>二者都为空时返回 {@code null}（调用方应视为非法入参）。
     */
    static String channelOf(String deviceId, String channelId) {
        return StringUtils.isBlank(channelId) ? deviceId : channelId;
    }

    /** 会话事件监听者（业务侧）。 */
    public interface SessionListener {
        void onSessionOpened(RtpSession session);

        void onSessionOnline(RtpSession session);

        void onSessionOffline(RtpSession session);

        void onSessionClosed(RtpSession session);
    }

    /**
     * 单端口模式下的 ZLM 流名：{@code <设备编码>_<通道编码>}。
     *
     * <p>下划线而非斜杠：ZLM 的流名会进入 URL 路径（{@code /rtp/<stream>.live.flv}），
     * 斜杠会被当成路径分隔符导致 404。设备编码与通道编码都是纯数字，下划线不会歧义。
     */
    static String singlePortStreamName(String deviceId, String channelId) {
        return deviceId + "_" + channelId;
    }

    /** 当前模式下的 ZLM app（独占端口 = 设备编码；单端口 = 固定 {@code rtp}）。 */
    private String zlmAppFor(String deviceId) {
        return mediaServer.isSinglePortMode() ? SINGLE_PORT_APP : deviceId;
    }

    /** 当前模式下的 ZLM stream。 */
    private String zlmStreamFor(String deviceId, String channelId) {
        return mediaServer.isSinglePortMode()
                ? singlePortStreamName(deviceId, channelId) : channelId;
    }

    private volatile SessionListener listener;

    public RtpSessionManager(ZlmMediaServer mediaServer, MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.props = props;
        // 单端口模式：把「SSRC 十六进制流名 → 可读流名」的解析器交给媒体服务器，
        // 供推流回调在流注册前改名（ZLM 用它替换 stream_id）。
        mediaServer.setSinglePortStreamNamer(this::streamNameForSsrc);
    }

    /**
     * 单端口推流回调的流名解析：ZLM 传来的 SSRC（十六进制）→ {@code <设备编码>_<通道编码>}。
     *
     * <p>返回 {@code null} 表示认不出该 SSRC（会话未登记或已关闭），
     * 此时放行但保留 ZLM 默认流名，便于排查。
     */
    private String streamNameForSsrc(String ssrcHex) {
        RtpSession session = resolveBySsrcName(ssrcHex);
        return session == null ? null : session.getStream();
    }

    public void setListener(SessionListener listener) {
        this.listener = listener;
    }

    // ==================== 会话生命周期 ====================

    /**
     * 为通道打开收流会话（创建 ZLM RTP 服务器）。
     *
     * @param channelId  通道编码（= ZLM 流名 {@code stream}）
     * @param deviceId   设备编码（= ZLM {@code app}，全局唯一）
     * @param deviceSsrc 设备 SDP {@code y=} 声明的 SSRC；传 null/"0"/非数字表示不过滤
     * @param tcpMode    0 = UDP；1 = TCP 被动
     * @param callId     SIP Call-ID（可为 null，如平台主动点播尚未建立时）
     * @return 新会话；若该通道已有会话，先关闭旧会话
     */
    public RtpSession open(String channelId, String deviceId, String deviceSsrc, int tcpMode, String callId) {
        return open(channelId, deviceId, deviceSsrc, tcpMode, callId, MediaType.REALTIME, null);
    }

    /**
     * 为通道打开收流会话（实时 / 回放 / 下载共用收流机制）。
     *
     * @param type     会话类型，决定 SDP 的 {@code s=} 与 SSRC 历史位
     * @param mediaUri 回放/下载的媒体标识（SDP {@code u=}）
     */
    public RtpSession open(String channelId, String deviceId, String deviceSsrc, int tcpMode,
                           String callId, MediaType type, String mediaUri) {
        // 归一缺失值（设备直连场景 deviceId == channelId），否则键会拼出 "null" 段
        final String channel = channelOf(deviceId, channelId);
        final String device = zlmAppOf(deviceId, channelId);
        // 会话键 = 设备编码 + 通道编码：通道编码跨设备会重复，不能单独作键
        String sessionKey = sessionKey(device, channel);
        RtpSession old = byChannel.get(sessionKey);
        if (old != null) {
            log.info("通道 {} 已有会话，先关闭旧会话: {}", sessionKey, old);
            closeByKey(sessionKey);
            // mk_rtp_server_release 会同步触发 native detach 回调，
            // 必须等它跑完再建新句柄，否则 ZLM 侧存在二次释放竞争（表现为 JVM 崩溃）
            awaitDetachQuietly(old, 20);
        }
        // 防御性清理：通道上可能残留着旧媒体源（历史版本释放端口时未注销源，
        // 或 ZLM 内部超时销毁后源仍注册）。不先清掉，新流的 regist() 会抛
        // "media source already existed"，设备每来一个 RTP 包就刷一对 detach 日志。
        int stale = mediaServer.closeMediaSource(zlmAppFor(device), zlmStreamFor(device, channel));
        if (stale > 0) {
            log.info("建立新会话前清理了 {} 个残留媒体源: key={}", stale, sessionKey);
        }

        ZLMApi api = ZlmMediaServer.api();
        MediaType mediaType = type == null ? MediaType.REALTIME : type;
        // SSRC 首位：实时 0 / 历史 1（GB/T 28181 附录 G）；buildSsrc 第三个参数是 realtime
        String ssrc = GbId.buildSsrc(channel, ssrcSeq.getAndIncrement(), !mediaType.historical());
        String ssrcFilter = normalizeSsrc(deviceSsrc);

        boolean singlePort = mediaServer.isSinglePortMode();
        MK_RTP_SERVER handle;
        int actualPort;
        IMKRtpServerDetachCallBack onDetach = null;

        if (singlePort) {
            // 单端口：所有会话共用一个 ZLM RTP 服务器，不再创建独立端口。
            // 会话登记后由推流回调按 SSRC 反查并改名（见 ZlmMediaServer#buildEvents）。
            handle = null;
            actualPort = mediaServer.getSinglePort();
        } else {
            int port = allocatePort();
            // 参数序：mk_rtp_server_create2(port, tcp_mode, vhost, app, stream_id)
            //   app       = 设备编码（全局唯一，避免跨设备同号通道撞源）
            //   stream_id = 通道编码（即 ZLM 的 stream）
            // 注意：此接口没有 ssrc 过滤参数，过滤需另调 mk_rtp_server_update_ssrc（见下）。
            handle = api.mk_rtp_server_create2((short) port, tcpMode,
                    DEFAULT_VHOST, device, channel);
            if (handle == null) {
                throw new IllegalStateException("创建 RTP 服务器失败: key=" + sessionKey
                        + ", port=" + port + ", tcpMode=" + tcpMode);
            }
            // 设备 SDP 声明的 SSRC：非 0 时才开启过滤（传 0 表示不过滤）
            if (!"0".equals(ssrcFilter)) {
                try {
                    api.mk_rtp_server_update_ssrc(handle, Integer.parseUnsignedInt(ssrcFilter));
                } catch (Throwable t) {
                    log.warn("设置 RTP SSRC 过滤失败: key={} ssrc={} {}", sessionKey, ssrcFilter, t.getMessage());
                }
            }
            actualPort = api.mk_rtp_server_port(handle) & 0xFFFF;
        }

        RtpSession session = new RtpSession(channel, device, ssrc, deviceSsrc, tcpMode,
                callId, handle, actualPort, mediaType, mediaUri,
                singlePort ? SINGLE_PORT_APP : null,
                singlePort ? singlePortStreamName(device, channel) : null);

        if (!singlePort) {
            // detach 回调：ZLM 侧服务器被关闭/销毁时触发，只做账务清理，绝不重复 release。
            // 该回调在 native 侧常驻（服务器存活期间随时可能触发），而 JNA 只以
            // WeakHashMap 弱引用登记回调——Java 侧不强引用则回调被 GC 后原生调用会崩溃。
            // 故交给会话对象持有强引用，release / detach 时清空（见 RtpSession#detachCallback）。
            //
            // 单端口模式不注册：ZLM 的复用分支不创建 RtcpHelper，setOnDetach 是空操作
            // （RtpServer.cpp: `if (_rtcp_helper) { _rtcp_helper->setOnDetach(...) }`），
            // 注册了也不会触发，超时改由流下线事件感知。
            onDetach = ptr -> onRtpDetached(session);
            session.setDetachCallback(onDetach);
            // 与 ZlmMediaServer 的事件回调同理：先把回调线程固定为常驻，再交给原生侧。
            // 否则 JNA 会在回调结束后尝试 detach 失败，往 stderr 打「could not detach thread」
            ZlmMediaServer.pinCallbackThread(onDetach);
            api.mk_rtp_server_set_on_detach2(handle, onDetach, null, null);
        }

        byChannel.put(sessionKey, session);
        byZlmKey.put(session.getZlmApp() + "/" + session.getStream(), session);
        if (singlePort) {
            registerSsrc(ssrc, session);
            if (!"0".equals(ssrcFilter)) {
                registerSsrc(ssrcFilter, session);
            }
        }
        if (callId != null) {
            byCallId.put(callId, session);
        }
        log.info("打开收流会话: {} rtpPort={} ssrc={} filterSsrc={} tcp={} mediaUri={} zlm={}/{}",
                session, actualPort, ssrc, ssrcFilter, tcpMode == TCP_MODE_PASSIVE, mediaUri,
                session.getZlmApp(), session.getStream());
        // 补一次「事件先于会话登记到达」的流上线（见 pendingOnline 注释）
        if (pendingOnline.remove(sessionKey) != null) {
            log.info("流上线事件先于会话登记到达，现补记上线: {}", sessionKey);
            onStreamOnline(session.getZlmApp(), session.getStream());
        }
        SessionListener l = listener;
        if (l != null) {
            l.onSessionOpened(session);
        }
        return session;
    }

    /** 按会话键关闭（{@code <设备编码>/<通道编码>}）。 */
    public void closeByKey(String sessionKey) {
        // 用条件删除：仅当映射仍指向本会话时才摘除。
        // 直接 remove 会把「键上已换成新会话」的场景一起清掉，
        // 那是重复点播崩溃的直接原因（native detach 回调竞争 → 访问违例）。
        RtpSession session = byChannel.get(sessionKey);
        if (session != null && !byChannel.remove(sessionKey, session)) {
            return; // 期间已被新会话替换，无需处理
        }
        if (session != null) {
            unbindCallId(session);
            unindexSession(session);
            release(session);
        }
    }

    /**
     * 摘除会话的辅助索引（ZLM 键、SSRC）。
     *
     * <p>用条件删除：键上可能已换成新会话，无条件 remove 会把新会话一并摘掉。
     */
    private void unindexSession(RtpSession session) {
        byZlmKey.remove(session.getZlmApp() + "/" + session.getStream(), session);
        bySsrc.values().removeIf(s -> s == session);
    }

    /**
     * 登记 SSRC → 会话（单端口分流用）。
     *
     * <p>按数值归一：SSRC 在 RTP 头里是 {@code uint32}，设备会丢前导零，
     * 必须与 {@link #resolveBySsrcName} 的解析口径一致，否则匹配不上。
     */
    private void registerSsrc(String ssrc, RtpSession session) {
        Long key = parseSsrc(ssrc);
        if (key == null) {
            return;
        }
        RtpSession prev = bySsrc.put(key, session);
        if (prev != null && prev != session) {
            // SSRC 回绕撞号：旧会话可能还活着，记一条便于排查
            log.warn("SSRC {} 已登记给会话 {}，被 {} 覆盖", key, prev.getSessionKey(),
                    session.getSessionKey());
        }
    }

    /**
     * SSRC 字符串 → 数值键。
     *
     * <p>只接受十进制数字串（平台 SSRC 与设备 SDP 的 {@code y=} 都是十进制）；
     * {@code null} / 空 / {@code "0"} / 非数字（如十六进制）一律返回 {@code null}。
     *
     * <p>按 {@code Long} 解析后天然忽略前导零：{@code "0200000001"} 与
     * {@code "200000001"} 得到同一个键。
     */
    static Long parseSsrc(String ssrc) {
        if (ssrc == null) {
            return null;
        }
        String s = ssrc.trim();
        if (s.isEmpty() || "0".equals(s)) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        try {
            long v = Long.parseLong(s);
            return (v <= 0 || v > 0xFFFFFFFFL) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 单端口模式：按 ZLM 回调给出的流名（SSRC 的十六进制串）反查会话。
     *
     * <p>ZLM 在单端口下把流名设为 SSRC 的<b>大写十六进制</b>（{@code printSSRC}），
     * 需要转成数值后查 {@link #bySsrc}。
     *
     * @param zlmStream ZLM 回调里的流名（SSRC 十六进制串）
     * @return 匹配到的会话；无法解析或未登记时返回 {@code null}
     */
    public RtpSession resolveBySsrcName(String zlmStream) {
        Long ssrc = hexSsrcToValue(zlmStream);
        return ssrc == null ? null : bySsrc.get(ssrc);
    }

    /**
     * SSRC 十六进制流名 → 数值。
     *
     * <p>ZLM 的 {@code printSSRC} 输出 8 位大写十六进制（不足补零）。
     * 解析失败返回 {@code null}（调用方按「未匹配」处理）。
     */
    static Long hexSsrcToValue(String hex) {
        String s = hex == null ? null : hex.trim();
        if (s == null || s.isEmpty() || s.length() > 8) {
            return null;
        }
        try {
            long v = Long.parseLong(s, 16);
            return (v <= 0 || v > 0xFFFFFFFFL) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按通道关闭会话。
     *
     * <p><b>仅用于无法确定设备编码的场景</b>（如 BYE 只带通道编码）：通道编码
     * 跨设备可能重复，这里会关闭<b>所有</b>匹配该通道编码的会话。
     * 能拿到设备编码时请用 {@link #closeByKey(String)}。
     *
     * @return 实际关闭的会话数
     */
    public int closeByChannel(String channelId) {
        if (StringUtils.isBlank(channelId)) {
            return 0;
        }
        List<String> keys = new ArrayList<>();
        for (RtpSession s : byChannel.values()) {
            if (channelId.equals(s.getChannelId())) {
                keys.add(s.getSessionKey());
            }
        }
        for (String key : keys) {
            closeByKey(key);
        }
        if (keys.size() > 1) {
            log.warn("按通道关闭命中了 {} 个会话（通道编码 {} 跨设备重复）", keys.size(), channelId);
        }
        return keys.size();
    }

    /** 按「设备编码 + 通道编码」关闭（推荐；通道编码可能跨设备重复）。 */
    public void closeByChannel(String deviceId, String channelId) {
        closeByKey(sessionKey(deviceId, channelId));
    }

    /** 解绑 Call-ID 索引（平台主动发起的会话 Call-ID 可能尚未回填，不可传 null）。 */
    private void unbindCallId(RtpSession session) {
        String callId = session.getCallId();
        if (callId != null) {
            byCallId.remove(callId);
        }
    }

    /** 按 Call-ID 关闭会话（BYE 处理的首选路径）。 */
    public void closeByCallId(String callId) {
        RtpSession session = byCallId.remove(callId);
        if (session != null) {
            // 同 closeByKey：必须条件删除。键上可能已换成新会话，
            // 无条件 remove 会把新会话一并摘掉，后续 point 会报「设备未注册」
            byChannel.remove(session.getSessionKey(), session);
            unindexSession(session);
            release(session);
        }
    }

    /** 关闭全部会话（服务停止时）。 */
    public void closeAll() {
        for (String key : new ArrayList<>(byChannel.keySet())) {
            closeByKey(key);
        }
    }

    /**
     * 按会话键取会话（{@code <设备编码>/<通道编码>}）。
     *
     * <p>需要精确定位时用本方法；只有通道编码时用
     * {@link #getByChannel(String)}（通道编码可能跨设备重复）。
     */
    public RtpSession getByKey(String sessionKey) {
        return byChannel.get(sessionKey);
    }

    /** 按「设备编码 + 通道编码」取会话（推荐）。 */
    public RtpSession getByChannel(String deviceId, String channelId) {
        return byChannel.get(sessionKey(deviceId, channelId));
    }

    /**
     * 按通道编码取会话。
     *
     * <p><b>仅在无法确定设备编码时使用</b>：通道编码跨设备可能重复，
     * 这里返回任意一个匹配的会话（优先非 CLOSED）。
     * 能拿到设备编码时请用 {@link #getByChannel(String, String)}。
     */
    public RtpSession getByChannel(String channelId) {
        if (StringUtils.isBlank(channelId)) {
            return null;
        }
        // 先按「设备编码=通道编码」直连场景（app == stream）尝试，最常见且无歧义
        RtpSession direct = byChannel.get(channelId + "/" + channelId);
        if (direct != null && !RtpSession.CLOSED.equals(direct.getState())) {
            return direct;
        }
        RtpSession found = null;
        for (RtpSession s : byChannel.values()) {
            if (!channelId.equals(s.getChannelId())) {
                continue;
            }
            if (!RtpSession.CLOSED.equals(s.getState())) {
                return s;
            }
            found = s;
        }
        return direct != null ? direct : found;
    }

    /**
     * 平台主动点播时 INVITE 发出后回填 Call-ID，使 BYE 能按 Call-ID 关闭。
     *
     * <p>回填必须落到会话对象本身（{@link RtpSession#setCallId}），不能只写索引：
     * 无人观看关流读的是 {@code session.getCallId()}，只写索引会让它恒为 null，
     * 表现为「无人观看不关流」；同时 {@link #unbindCallId} 也摘不掉索引条目。
     */
    public void bindCallId(RtpSession session, String callId) {
        if (callId != null && session != null) {
            session.setCallId(callId);
            byCallId.put(callId, session);
        }
    }

    /** 记录对端 To-tag（收到设备 200 OK 后调用），BYE 时需要。 */
    public void bindToTag(RtpSession session, String toTag) {
        if (session != null) {
            session.setToTag(toTag);
        }
    }

    public RtpSession getByCallId(String callId) {
        return byCallId.get(callId);
    }

    public Collection<RtpSession> sessions() {
        return byChannel.values();
    }

    public int sessionCount() {
        return byChannel.size();
    }

    // ==================== ZLM 事件接入 ====================

    /**
     * 按 ZLM 回调的 {@code (app, stream)} 查找会话。
     *
     * <p>查 {@link #byZlmKey} 而非 {@link #byChannel}：单端口模式下两者不同
     * （ZLM 键是 {@code rtp/<设备>_<通道>}，业务键是 {@code <设备>/<通道>}）。
     *
     * <p>独占端口模式下二者恰好相等（{@code app=设备编码}、{@code stream=通道编码}），
     * 直接命中。历史上 {@code stream_id} 被误当成 ssrc 过滤器，导致 ZLM 里的流名是
     * 裸序号（{@code "0"}）而 app 是通道编码，才需要兼容两种布局。
     */
    private RtpSession findByStream(String app, String stream) {
        if (StringUtils.isBlank(app) || StringUtils.isBlank(stream)) {
            return null;
        }
        RtpSession session = byZlmKey.get(app + "/" + stream);
        if (session == null && SINGLE_PORT_APP.equals(app)) {
            // 单端口兜底：若 stream_replace 未生效（流名仍是 SSRC 十六进制），
            // 按 SSRC 反查，避免会话永远停在 NEGOTIATING。
            // 仅在 app 确为单端口固定值时才尝试——否则独占端口下形如 "11010000001320000238"
            // 的通道编码会被当成十六进制解析，误命中别的会话。
            session = resolveBySsrcName(stream);
        }
        return session;
    }

    /**
     * 事件对应的会话键（用于 {@link #pendingOnline} 匹配）。
     *
     * <p>返回<b>业务键</b>（{@link #byChannel} 的键），不是 ZLM 键：单端口下二者不同，
     * 而 {@link #pendingOnline} 是按业务键在 {@code open()} 里查的。
     *
     * <p>会话已登记时直接取 {@link RtpSession#getSessionKey()}；尚未登记（事件先到）时
     * 从 ZLM 命名反推——单端口流名形如 {@code <设备编码>_<通道编码>}，可无损还原。
     */
    private String channelKeyOf(String app, String stream) {
        RtpSession session = findByStream(app, stream);
        if (session != null) {
            return session.getSessionKey();
        }
        // 事件先于会话登记：按 ZLM 命名规则反推业务键
        if (SINGLE_PORT_APP.equals(app)) {
            int sep = stream == null ? -1 : stream.indexOf('_');
            if (sep > 0 && sep < stream.length() - 1) {
                return stream.substring(0, sep) + "/" + stream.substring(sep + 1);
            }
            return null;
        }
        return (StringUtils.isBlank(app) || StringUtils.isBlank(stream))
                ? null : app + "/" + stream;
    }

    /**
     * 按 ZLM 回调的 {@code (app, stream)} 定位会话（公开版，供无人观看等外部回调使用）。
     *
     * @return 匹配到的会话；无法归属时返回 {@code null}
     */
    public RtpSession resolve(String app, String stream) {
        return findByStream(app, stream);
    }

    public void onStreamOnline(String app, String stream) {
        RtpSession session = findByStream(app, stream);
        if (session == null) {
            // 事件先于会话登记到达：暂存，等 open() 登记会话时补一次上线
            String key = channelKeyOf(app, stream);
            if (key != null) {
                pendingOnline.put(key, Boolean.TRUE);
                log.debug("流上线事件暂存（会话尚未登记）: app={} stream={} key={}", app, stream, key);
            }
            return;
        }
        // ZLM 对同一个源会按协议各报一次（fmp4/rtmp/rtsp/ts/hls），
        // 只在「非 ONLINE → ONLINE」这一次真正变化时回调，避免重复落库与刷屏
        if (RtpSession.ONLINE.equals(session.getState())
                || RtpSession.CLOSED.equals(session.getState())) {
            return;
        }
        session.setState(RtpSession.ONLINE);
        log.info("流上线: {} (app={} stream={})", session, app, stream);
        SessionListener l = listener;
        if (l != null) {
            l.onSessionOnline(session);
        }
    }

    /** 流下线（on_mk_media_changed(0)）。端口保留，等待设备恢复。 */
    public void onStreamOffline(String app, String stream) {
        // 下线事件与暂存的上线事件相抵，避免会话建立后又误判为在线
        String key = channelKeyOf(app, stream);
        if (key != null) {
            pendingOnline.remove(key);
        }
        RtpSession session = findByStream(app, stream);
        // 同样只在「ONLINE → 非 ONLINE」时回调一次（ZLM 按协议各报一次下线）
        if (session != null && RtpSession.ONLINE.equals(session.getState())) {
            session.setState(RtpSession.NEGOTIATING);
            log.info("流下线（RTP 端口 {} 保留等待恢复）: app={} stream={}",
                    session.getPort(), app, stream);
            SessionListener l = listener;
            if (l != null) {
                l.onSessionOffline(session);
            }
        }
    }

    // ==================== 内部 ====================

    /** detach 回调（可能来自 ZLM 超时销毁，也可能来自我们主动 release 后的连锁触发）。 */
    private void onRtpDetached(RtpSession session) {
        if (!session.markDetached()) {
            return; // 已由主动关闭路径处理过
        }
        log.info("RTP 服务器 detach: {}", session);
        // ZLM 侧服务器已销毁，回调不会再触发，释放其强引用
        session.clearDetachCallback();
        // 重复点播时键上可能已挂着新会话，只有映射仍指向本会话才清理，
        // 否则会把刚建立的新会话一并摘掉，导致后续 point 报「设备未注册」
        byChannel.remove(session.getSessionKey(), session);
        unbindCallId(session);
        unindexSession(session);
        pendingOnline.remove(session.getSessionKey());
        session.setState(RtpSession.CLOSED);
        // closeMediaSource 内部会判断通道上是否已有新会话，有新会话时不误关
        closeMediaSource(session);
        SessionListener l = listener;
        if (l != null) {
            l.onSessionClosed(session);
        }
    }

    /**
     * 主动释放 ZLM RTP 服务器。
     * 若会话已被 detach（ZLM 侧销毁），跳过 release 避免二次释放。
     */
    /**
     * 等待旧会话的 native 释放彻底结束，再创建新句柄。
     *
     * <p>{@code mk_rtp_server_release} 会回到 ZLM 的 C++ 侧销毁对象，
     * 该过程与 detach 回调在同一线程/事件循环内完成；此处等待其返回，
     * 避免紧接着创建新句柄时与之竞争，造成 JVM 访问违例。
     *
     * <p>release 是同步调用，返回即表示 native 侧已处理完，
     * 这里只做一次让出，确保 detach 回调有机会在事件循环跑完。
     */
    private void awaitDetachQuietly(RtpSession session, int rounds) {
        for (int i = 0; i < rounds; i++) {
            if (RtpSession.CLOSED.equals(session.getState())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!RtpSession.CLOSED.equals(session.getState())) {
            log.warn("旧会话 {} 释放后仍处于非 CLOSED 状态，继续建立新会话", session);
        }
    }

    private void release(RtpSession session) {
        if (session.markDetached()) {
            if (session.getHandle() != null) {
                try {
                    ZlmMediaServer.api().mk_rtp_server_release(session.getHandle());
                    log.info("已释放 RTP 服务器: {}", session);
                } catch (Throwable t) {
                    log.warn("释放 RTP 服务器异常: {}", session, t);
                } finally {
                    // 服务器已销毁，detach 回调不会再触发，释放其强引用
                    session.clearDetachCallback();
                }
            }
            // 必须连带注销媒体源：mk_rtp_server_release 只销毁收流端口，
            // 不会注销已注册的 MediaSource。残留的源会让下次点播在 regist() 抛
            // "media source already existed"，被 RtpProcess 转成 detach 后
            // 设备每来一个 RTP 包就打一对 onDetach / emitAllTrackReady 日志，
            // 播放器不出画面、CPU 持续升高 —— 即「停止直播停不下来」。
            // 等价于 ZLM HTTP API 的 close_streams(force=1)，
            // 与 wvp-GB28181-pro 的 closeRTPServer() 双关闭行为一致。
            //
            // 单端口模式同样要注销：源挂在共享端口上，不注销会让设备继续
            // 往里推（ZLM 按 SSRC 仍能解出流），表现为「停止后画面还能播」。
            closeMediaSource(session);
        }
        pendingOnline.remove(session.getSessionKey());
        session.setState(RtpSession.CLOSED);
        SessionListener l = listener;
        if (l != null) {
            l.onSessionClosed(session);
        }
    }

    /** 注销会话对应的 ZLM 媒体源（释放端口不会注销媒体源）。 */
    private void closeMediaSource(RtpSession session) {
        try {
            // 同一个 app/stream 上若已挂着另一个活跃会话（重复点播 / BYE 迟到），
            // 其媒体源正在被使用，绝不能一并关掉——那会让新流也断掉。
            RtpSession current = byChannel.get(session.getSessionKey());
            if (current != null && current != session
                    && !RtpSession.CLOSED.equals(current.getState())) {
                log.debug("会话 {} 已有新实例 {}，跳过媒体源注销", session.getSessionKey(), current);
                return;
            }
            // 按会话自己的 ZLM 命名定位：单端口与独占端口的 app/stream 不同，
            // 不能按 session.getChannelId() 硬拼（见 RtpSession#getStream()）。
            int closed = mediaServer.closeMediaSource(session.getZlmApp(), session.getStream());
            if (closed > 0) {
                log.info("已注销 ZLM 媒体源: {} 共 {} 个", session.getSessionKey(), closed);
            }
        } catch (Throwable t) {
            log.warn("注销 ZLM 媒体源异常: {}", session, t);
        }
    }

    /** 固定端口范围轮询分配；未配置范围时返回 0（ZLM 自动随机分配）。 */
    private synchronized int allocatePort() {
        int start = props.getRtpPortRangeStart();
        int end = props.getRtpPortRangeEnd();
        if (start <= 0 || end < start) {
            return 0;
        }
        int span = end - start + 1;
        for (int i = 0; i < span; i++) {
            int candidate = start + Math.floorMod(nextPort.getAndIncrement(), span);
            boolean used = false;
            for (RtpSession s : byChannel.values()) {
                // 单端口模式下所有会话共用同一端口，不能据此判重
                // （否则第一次分配后其余通道全被当成「端口已占用」）
                if (s.getHandle() != null && s.getPort() == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
        }
        throw new IllegalStateException("RTP 固定端口范围耗尽: [" + start + ", " + end + "]");
    }

    /** 设备 SSRC 归一化：十进制数字串返回原值，其余（null/"0"/非数字）返回 "0"（不过滤）。 */
    static String normalizeSsrc(String deviceSsrc) {
        if (deviceSsrc == null) {
            return "0";
        }
        String s = deviceSsrc.trim();
        if (s.isEmpty() || "0".equals(s)) {
            return "0";
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return "0";
            }
        }
        return s;
    }
}
