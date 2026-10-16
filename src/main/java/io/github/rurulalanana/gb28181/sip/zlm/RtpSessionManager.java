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
     * 会话被顶替/关闭前的「通知设备」钩子（发 SIP BYE）。
     *
     * <p><b>为什么必须由会话管理器回调，而不是让调用方自己发</b>：
     * 会话被关闭有两条路径 —— 显式 {@code stop()} 与「重复点播时
     * {@code open()} 内部先关旧会话」。后者在 {@link #open} 里发生，
     * 调用方（{@code Gb28181MediaService.play/playback}）根本不知道
     * 有个旧会话被顶掉了，于是<b>从不发 BYE</b>。
     *
     * <p>后果是设备不知道自己已被顶替，继续往旧端口推流；同时新 INVITE
     * 又让设备推第二路 —— 实测同一通道出现两个源端口同时推流
     * （{@code 40673} 与 {@code 40867}），带宽白白翻倍。
     *
     * <p>钩子在 {@code mk_rtp_server_release} <b>之前</b>调用：BYE 需要一个
     * 尚存活的会话对象读取 Call-ID / To-tag。
     */
    public interface SessionTeardownHook {
        /**
         * 关闭会话<b>之前</b>调用（发 BYE 通知设备停止推流）。
         *
         * <p>尽力而为，异常不得阻断本地释放。
         *
         * @param session 即将被关闭的会话
         */
        void beforeClose(RtpSession session);
    }

    private volatile SessionTeardownHook teardownHook;

    /**
     * 媒体源注销前的监听者（归档录像用）。
     *
     * <p><b>为什么必须是独立钩子</b>：{@link SessionTeardownHook} 由
     * {@code Gb28181MediaService} 注册（它要发 SIP BYE，属于本模块职责）；
     * 而归档录像需要查数据库，只有上层业务（starter）能做。两者由不同的人
     * 注册，共用一个钩子会互相覆盖。
     *
     * <p><b>为什么时机是「媒体源注销前」</b>：录像的结束时间、文件路径、时长
     * 都来自 ZLM 的内存录像索引；{@code closeMediaSource} 会把索引一并清掉，
     * 之后再查什么都拿不到 —— 这正是「录像要等 60 秒对账才出现」的原因。
     */
    private volatile java.util.function.Consumer<RtpSession> preSourceCloseListener;

    /**
     * 注册会话关闭前的通知钩子（发 BYE）。
     *
     * <p>由 {@code Gb28181MediaService} 在装配 SIP 客户端后注入。
     */
    public void setTeardownHook(SessionTeardownHook hook) {
        this.teardownHook = hook;
    }

    /**
     * 注册「媒体源注销前」监听者（归档录像）。
     *
     * <p>由上层业务（starter）注入；在 ZLM 媒体源被注销之前回调，
     * 此时录像索引仍可读。
     */
    public void setPreSourceCloseListener(java.util.function.Consumer<RtpSession> listener) {
        this.preSourceCloseListener = listener;
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
        return zlmStreamFor(deviceId, channelId, null);
    }

    /**
     * 当前模式下的 ZLM stream（按会话类型区分）。
     *
     * <p><b>为什么必须按类型加后缀</b>：ZLM 里一个 {@code app/stream} 只能有一个媒体源。
     * 直播与回放原先共用同一 stream，于是「有人看直播时另一个人点回放」会走到
     * {@code open()} 里的「已有会话先关闭旧会话」分支——把正在观看的直播踢掉
     * （实测日志：{@code 已有会话，先关闭旧会话: type=REALTIME, state=ONLINE}
     * 紧跟 {@code 打开收流会话: type=PLAYBACK}），观看端画面直接中断。
     * 回放与下载同理，故历史流统一加 {@code _playback} / {@code _download} 后缀。
     *
     * <p>后缀只加在<b>历史流</b>上，直播 stream 保持原样：直播的流名是对外契约
     * （播放地址、转推、录像源名都由它推导），改动会波及面过大。
     *
     * @param type 会话类型；{@code null} 视为实时
     */
    private String zlmStreamFor(String deviceId, String channelId, MediaType type) {
        String base = mediaServer.isSinglePortMode()
                ? singlePortStreamName(deviceId, channelId) : channelId;
        return base + typeSuffix(type);
    }

    /**
     * 历史流的 stream 后缀。
     *
     * <p>用下划线而非斜杠：流名会进 URL 路径，斜杠会被当路径分隔符导致 404。
     *
     * <p><b>包级可见</b>：{@link RtpSession#getSessionKey()} 必须用同一套规则，
     * 否则业务键与 ZLM 流名会不一致（会话查不到）。
     */
    static String typeSuffix(MediaType type) {
        if (type == null || type == MediaType.REALTIME) {
            return "";
        }
        return type == MediaType.DOWNLOAD ? "_download" : "_playback";
    }

    /**
     * 业务会话键：{@code <设备编码>/<通道编码>}，历史流再拼类型后缀。
     *
     * <p>与 {@link #zlmStreamFor} 同步加后缀，保证「一个通道的直播与回放」
     * 是两个独立会话，互不顶替；同时 {@code stop(device, channel)} 这类
     * 按裸通道的调用仍能命中直播会话（后缀为空，键不变）。
     */
    static String sessionKey(String deviceId, String channelId, MediaType type) {
        return sessionKey(deviceId, channelId) + typeSuffix(type);
    }

    private volatile SessionListener listener;

    public RtpSessionManager(ZlmMediaServer mediaServer, MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.props = props;
        // 单端口模式：把「SSRC 十六进制流名 → 可读流名」的解析器交给媒体服务器，
        // 供推流回调在流注册前改名（ZLM 用它替换 stream_id）。
        mediaServer.setSinglePortStreamNamer(this::streamNameForSsrc);
        // 兜底认领：部分下级平台（实测 LiveGBS）收 BYE 回 200 OK 却不停止推流，
        // 会话拆掉后这路流就成了认不出归属的孤儿。装了这个认领器后，
        // 推流回调在解析失败时会按 SSRC 墓碑重建会话，把流重新纳管。
        mediaServer.setOrphanReclaimer(this::reclaimOrphanName);
    }

    /**
     * 孤儿流认领的流名解析（供推流回调兜底调用）。
     *
     * @return 认领成功返回可读流名；失败返回 {@code null}（回调将保留 ZLM 默认名）
     */
    private String reclaimOrphanName(String ssrcHex) {
        try {
            RtpSession session = reclaimOrphanBySsrc(ssrcHex);
            return session == null ? null : session.getStream();
        } catch (Throwable t) {
            log.warn("认领孤儿流失败: ssrc={} {}", ssrcHex, t.toString());
            return null;
        }
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
        final MediaType mediaType = type == null ? MediaType.REALTIME : type;
        // 会话键 = 设备编码 + 通道编码 + 类型后缀：通道编码跨设备会重复，不能单独作键；
        // 直播与回放/下载也必须是不同会话，否则互相顶替（见 typeSuffix 注释）。
        String sessionKey = sessionKey(device, channel, mediaType);
        RtpSession old = byChannel.get(sessionKey);
        if (old != null) {
            log.info("通道 {} 已有会话，先关闭旧会话: {}", sessionKey, old);
            // closeByKey 内部会先发 BYE 通知设备再拆本地资源。
            // 少了 BYE，设备不知道自己已被顶替，会继续往旧端口推流；
            // 紧接着的 INVITE 又让它推第二路 —— 实测同一通道出现两个源端口
            // 同时推流，带宽白白翻倍（「重复拉流」）。
            closeByKey(sessionKey);
            // mk_rtp_server_release 会同步触发 native detach 回调，
            // 必须等它跑完再建新句柄，否则 ZLM 侧存在二次释放竞争（表现为 JVM 崩溃）
            awaitDetachQuietly(old, 20);
        }
        // 防御性清理：通道上可能残留着旧媒体源（历史版本释放端口时未注销源，
        // 或 ZLM 内部超时销毁后源仍注册）。不先清掉，新流的 regist() 会抛
        // "media source already existed"，设备每来一个 RTP 包就刷一对 detach 日志。
        // 按「本类型」的流名清理：直播与回放是不同流名，不能互相关掉。
        int stale = mediaServer.closeMediaSource(zlmAppFor(device),
                zlmStreamFor(device, channel, mediaType));
        if (stale > 0) {
            log.info("建立新会话前清理了 {} 个残留媒体源: key={}", stale, sessionKey);
        }

        ZLMApi api = ZlmMediaServer.api();
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
                    DEFAULT_VHOST, device, zlmStreamFor(device, channel, mediaType));
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
                zlmStreamFor(device, channel, mediaType));

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

    /**
     * 关闭会话前通知设备停止推流（发 BYE），尽力而为。
     *
     * <p>只在会话确实「活着」时才通知：已 CLOSED 的会话其 SIP 对话框
     * 早已结束，再发 BYE 是多余的（设备会回 481 Call/Transaction Does Not Exist）。
     *
     * <p>异常一律吞掉：BYE 失败不应阻断本地端口/媒体源释放，否则会留下
     * 「停不掉的流」——比多发一个包严重得多。
     */
    private void notifyTeardown(RtpSession session) {
        if (session == null || RtpSession.CLOSED.equals(session.getState())) {
            return;
        }
        SessionTeardownHook hook = teardownHook;
        if (hook == null) {
            return;
        }
        try {
            hook.beforeClose(session);
        } catch (Throwable t) {
            log.warn("会话关闭前通知设备失败（不阻断释放）: {} {}", session, t.toString());
        }
    }

    /**
     * 会话关闭后通知（归档录像）。
     *
     * <p>调用点必须在 {@link #closeMediaSource(RtpSession)} <b>之后</b>：
     * 单端口模式下 {@code handle == null}，{@code mk_rtp_server_release} 被跳过，
     * 录像停止实际由注销媒体源触发（源消失 → 流下线 → 停录 → 文件改名）。
     * 跑在它之前时 ZLM 索引 {@code endTime} 仍为 0、磁盘文件仍是 {@code .} 前缀，
     * 归档拿不到任何东西。
     *
     * <p>与 {@link #notifyTeardown} 不同，这里<b>不检查</b> CLOSED 状态：
     * 归档本就要在会话关闭之后做（此时录像才是终态）。
     *
     * <p>异常一律吞掉：归档失败不应阻断释放，否则会留下「关不掉的流」。
     */
    private void notifySourceClosed(RtpSession session) {
        if (session == null) {
            return;
        }
        java.util.function.Consumer<RtpSession> l = preSourceCloseListener;
        if (l == null) {
            return;
        }
        try {
            l.accept(session);
        } catch (Throwable t) {
            log.warn("媒体源注销前归档失败（不阻断释放）: {} {}", session, t.toString());
        }
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
            // 统一在此处通知设备：closeByKey 是所有主动关闭路径的汇聚点
            // （stop / 无人观看 / open 顶替旧会话），补一次 BYE 才能让设备
            // 真正停止推流，而不是继续往已释放的端口上推。
            notifyTeardown(session);
            unbindCallId(session);
            unindexSession(session);
            release(session);
        }
    }

    /**
     * 回滚一个「已开好但业务没走通」的会话（点播 INVITE 失败等）。
     *
     * <p><b>为什么必须回滚</b>：会话是先 {@link #open} 后发 INVITE 的，
     * INVITE 抛异常（设备未注册、SIP 超时、对端拒绝）时若把会话留在索引里，
     * 它就是个永不 ONLINE 的僵尸。后续所有「按通道找会话」的调用都会命中它，
     * 而它的 {@code app/stream} 是按<b>当时那次</b>调用的参数拼的 ——
     * 一旦调用方传错了设备编码，僵尸就带着错误流名长期占位，
     * 导致该通道的云端录像永远找不到媒体源（{@code mk_recorder_start} 返回 0），
     * 且只能靠重启清空内存索引才能恢复。实测正是如此。
     *
     * <p>与 {@link #closeByKey} 的差别：<b>不发 BYE</b>。INVITE 都没成功，
     * 设备侧不存在对话，发 BYE 只会多一个无意义的 SIP 事务；
     * 而且此时 Call-ID 尚未回填。这里只拆本地资源与索引。
     */
    public void abort(String sessionKey) {
        RtpSession session = byChannel.get(sessionKey);
        if (session != null && !byChannel.remove(sessionKey, session)) {
            return; // 期间已被新会话替换，保留新的
        }
        if (session == null) {
            return;
        }
        log.info("回滚未走通的会话（不通知设备）: {}", session);
        unbindCallId(session);
        unindexSession(session);
        release(session);
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
        // 同步墓碑：设备不认 BYE 时会话虽拆，SSRC 仍被沿用，靠它重新认领
        rememberSsrc(key, session);
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
     * 会话关闭后仍被设备沿用的 SSRC 的「墓碑」。
     *
     * <p><b>为什么需要</b>：部分下级平台（实测 LiveGBS）收到 BYE 会回 {@code 200 OK}
     * 却<b>不停止推流</b>。平台这边会话已拆、{@link #bySsrc} 已清，设备推来的 RTP
     * 就再也认不出属于哪个通道 —— {@code on_mk_media_publish} 拿到未登记的 SSRC，
     * 只能保留十六进制流名放行，于是「级联还在推流、平台却看不到」，
     * 且这些孤儿流会一直占着收流端口与带宽直到设备自己超时。
     *
     * <p>这里记住「SSRC → 业务会话键」的映射（不持有会话对象，避免阻止 GC），
     * 当同一个 SSRC 再次出现时就能反查出通道，重新接纳这路流。
     *
     * <p><b>为何用带过期的映射而不是直接不清 bySsrc</b>：SSRC 会回绕复用，
     * 永久保留会让新会话被旧映射污染；设备侧停推后该映射也无意义，
     * 故设 TTL（见 {@link #SSRC_TOMBSTONE_TTL_MS}）。
     */
    private final Map<Long, String> ssrcTombstone = new ConcurrentHashMap<>();

    /** 墓碑存活时长：覆盖「设备停推→重新点播」的常见间隔。 */
    private static final long SSRC_TOMBSTONE_TTL_MS = 10 * 60 * 1000L;

    /**
     * 启动时从数据库恢复的映射，不参与 TTL 过期。
     *
     * <p>理由：这些映射代表「设备可能仍在推流」的长期状态，而设备什么时候停推
     * 我方无从预知（它连 BYE 都不理会）。用 10 分钟 TTL 会把它们过早清掉，
     * 又回到「认不出归属」的老问题。真正的失效判据是设备停推后
     * {@code on_mk_media_changed(0)} 或重新点播，而不是时间。
     */
    private final java.util.Set<Long> restoredSsrc = ConcurrentHashMap.newKeySet();

    /** 墓碑的写入时间，用于惰性过期。 */
    private final Map<Long, Long> ssrcTombstoneAt = new ConcurrentHashMap<>();

    /**
     * 空闲关流的冷却时间：这段时间内不再自动重新认领同一通道。
     *
     * <p><b>为什么需要</b>：设备不认 BYE 时会形成
     * 「孤儿流巡检认领 → 空闲巡检关流（发 BYE）→ 下一轮又认领」的循环。
     * 关流本身是对的（要通知设备停推，这是唯一手段），但每 20 秒一轮
     * 既刷日志又反复建销会话。冷却让同一通道在一段时间内只被关一次，
     * 给设备留出响应 BYE 的时间。
     *
     * <p>冷却期内用户仍可正常点播：那条路径走 {@code open()}，
     * 不经过孤儿流认领，不受冷却影响。
     */
    private static final long REAP_COOLDOWN_MS = 5 * 60 * 1000L;

    /** 会话键 → 上次被空闲关流的时间，用于冷却判定。 */
    private final Map<String, Long> reapedAt = new ConcurrentHashMap<>();

    /**
     * 记下「该通道刚因空闲被关流」，冷却期内不再自动重新认领。
     *
     * <p><b>为什么按会话键而不是 SSRC 记</b>：会话的 {@link RtpSession#getSsrc()}
     * 是 SDP 里通告给设备的值（{@code GbId.buildSsrc} 生成，形如 {@code 0200000003}），
     * 而设备实际推流用的 RTP SSRC 可能<b>不是</b>这个值 —— 实测 LiveGBS 用
     * {@code 0200000001}。重新认领时能拿到的只有后者（ZLM 流名是它的十六进制串），
     * 两者对不上。按 SSRC 记冷却就会失效：实测关流 11 秒后又被认领回来，
     * 形成「认领 → 关流 → 认领」的循环。
     *
     * <p>按会话键（设备/通道）记则天然对齐：认领时从墓碑里拿到的正是会话键。
     *
     * @param sessionKey 业务会话键 {@code <设备编码>/<通道编码>}
     */
    public void markReaped(String sessionKey) {
        if (StringUtils.isNotBlank(sessionKey)) {
            reapedAt.put(sessionKey, System.currentTimeMillis());
        }
    }

    /** 该会话是否处于空闲关流冷却期（过期项顺手清掉）。 */
    private boolean recentlyReaped(String sessionKey) {
        if (StringUtils.isBlank(sessionKey)) {
            return false;
        }
        Long t = reapedAt.get(sessionKey);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t > REAP_COOLDOWN_MS) {
            reapedAt.remove(sessionKey, t);
            return false;
        }
        return true;
    }

    /**
     * 记下「该 SSRC 曾属于哪个会话」，供设备不认 BYE 时重新认领孤儿流。
     */
    private void rememberSsrc(Long ssrc, RtpSession session) {
        if (ssrc == null || session == null) {
            return;
        }
        purgeExpiredTombstones();
        ssrcTombstone.put(ssrc, session.getSessionKey());
        ssrcTombstoneAt.put(ssrc, System.currentTimeMillis());
    }

    /**
     * 平台启动时恢复「SSRC → 会话键」映射（纯记账，不做 native 调用）。
     *
     * <p><b>为什么必须恢复</b>：设备（下级平台）不会因我方重启而停止推流 ——
     * 它仍在往收流端口推同一个 SSRC。而 {@link #bySsrc} 是内存索引，重启即空，
     * 于是这些流在推流回调里认不出归属，被当成未登记 SSRC 丢弃，
     * 表现为「级联那边还在推流、平台这边一路都看不到」，且会一直持续到
     * 设备自己超时或人工重新点播为止。实测重启后立刻出现三路这样的孤儿流。
     *
     * <p>调用方（starter）从 {@code gb_stream} 读出历史点播记录后调用本方法。
     * 恢复的映射在设备重新推流时会触发 {@link #reclaimOrphanBySsrc} 重建会话，
     * 让这些流重新可见。恢复项不参与 TTL 过期（见 {@link #restoredSsrc}）。
     *
     * @param ssrc        SSRC（十进制串，与 SDP {@code y=} 同口径）
     * @param sessionKey  业务会话键 {@code <设备编码>/<通道编码>}
     * @return 是否成功登记（入参非法时返回 false）
     */
    public boolean restoreSsrcMapping(String ssrc, String sessionKey) {
        Long key = parseSsrc(ssrc);
        if (key == null || StringUtils.isBlank(sessionKey)) {
            return false;
        }
        ssrcTombstone.put(key, sessionKey);
        ssrcTombstoneAt.put(key, System.currentTimeMillis());
        restoredSsrc.add(key);
        return true;
    }

    /** 惰性清理过期墓碑（调用频率低，无需定时任务）。 */
    private void purgeExpiredTombstones() {
        if (ssrcTombstone.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        ssrcTombstone.entrySet().removeIf(e -> {
            if (restoredSsrc.contains(e.getKey())) {
                return false; // 启动恢复项不过期
            }
            Long at = ssrcTombstoneAt.get(e.getKey());
            if (at == null || now - at > SSRC_TOMBSTONE_TTL_MS) {
                ssrcTombstoneAt.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 设备不认 BYE 时的补救：为仍被沿用的 SSRC 重新登记会话（纯记账，不做 native 调用）。
     *
     * <p>调用点在 ZLM 的 {@code on_mk_media_publish} 回调里。那里<b>绝不能</b>调用
     * {@link #open} 之类会落到 native 的方法：{@code open} 内部有
     * {@code mk_rtp_server_create2} / {@code closeMediaSource}，在 native 回调中
     * 重入原生层会引发访问违例（历史上就是这样 SIGABRT 的）；而且单端口下
     * {@code open} 的「防御性清理残留媒体源」会把我们正要认领的这路流一起关掉。
     *
     * <p><b>会话的 {@code zlmStream} 必须记成 ZLM 里的「真实流名」</b>，
     * 也就是认领时那个 SSRC 十六进制串（{@code ssrcHex}），而不是可读名。
     * 原因：{@code stream_replace} 只在流<b>注册那一刻</b>生效，认领时流早已注册完，
     * ZLM 里它就叫 {@code 0BEBC205}。若会话记可读名，之后所有按 app/stream 的操作
     * 全部失配 —— 表现为「无人观看关不掉流」（{@code resolve} 查不到会话）和
     * 「录像启动失败」（按错误名找不到媒体源）。实测两者同时出现过。
     *
     * <p><b>认领是「可能认错」的高风险操作</b>：SSRC 会回绕复用，同一 SSRC 在库里
     * 可能对应多个通道的历史记录。实测就出现过把流认领给一个已离线设备
     * （{@code 34020000001310000001}），把它置成在线并长期挂着。
     * 因此这里要求调用方先做在线校验（见 {@link #setOrphanAdopter}）。
     *
     * @param ssrcHex ZLM 回调给出的 SSRC 十六进制串（同时就是 ZLM 里的真实流名）
     * @return 重新认领的会话；无法认领或被拒返回 {@code null}
     */
    public RtpSession reclaimOrphanBySsrc(String ssrcHex) {
        Long ssrc = hexSsrcToValue(ssrcHex);
        if (ssrc == null) {
            return null;
        }
        String key = ssrcTombstone.get(ssrc);
        if (key == null) {
            return null;
        }
        int slash = key.lastIndexOf('/');
        if (slash <= 0 || slash == key.length() - 1) {
            return null;
        }
        String device = key.substring(0, slash);
        String channel = key.substring(slash + 1);
        // 冷却：刚因空闲被关流的通道不立刻认领回来，否则会形成
        // 「认领 → 关流 → 认领」的无限循环（见 REAP_COOLDOWN_MS）。
        // 按会话键判定，不能按 SSRC —— 会话的 ssrc 是 SDP 通告值，
        // 与设备实际推流用的 RTP SSRC 往往不同（见 markReaped 注释）。
        if (recentlyReaped(key)) {
            log.debug("放弃认领孤儿流（刚因空闲被关流，冷却中）: ssrc={} 通道={}", ssrcHex, key);
            return null;
        }
        // 在线校验：设备离线时它不可能在推流，认领必然是 SSRC 回绕导致的错配。
        // 认了会把一个离线通道置成在线并永久挂着（实测踩过），必须挡住。
        java.util.function.Predicate<String> adopter = orphanAdopter;
        if (adopter != null && !adopter.test(device)) {
            log.debug("放弃认领孤儿流（设备不在线，疑似 SSRC 回绕错配）: ssrc={} device={}", ssrcHex, device);
            return null;
        }
        RtpSession existing = byChannel.get(key);
        if (existing != null && !RtpSession.CLOSED.equals(existing.getState())) {
            // 会话还在（并发重建等），只需补登记 SSRC，别动状态。
            // 直接写 bySsrc 而不用 registerSsrc：后者按十进制解析，传入十六进制串会被拒。
            bySsrc.put(ssrc, existing);
            rememberSsrc(ssrc, existing);
            return existing;
        }
        MediaType type = typeOfKey(key);
        // zlmStream 用 ssrcHex（ZLM 里的真实流名），见方法注释 —— 用可读名会导致
        // 无人观看关不掉、录像找不到媒体源。
        // ssrc 传十进制串（与 SDP y= 同口径）：落库的 gb_stream.ssrc 要能反查回数值。
        RtpSession session = new RtpSession(channel, device, String.valueOf(ssrc), null,
                0, null, null,
                mediaServer.getSinglePort(), type, null,
                SINGLE_PORT_APP, ssrcHex);
        session.setState(RtpSession.ONLINE);
        byChannel.put(key, session);
        byZlmKey.put(session.getZlmApp() + "/" + session.getStream(), session);
        // 按【数值】登记 SSRC 索引：resolveBySsrcName 走的是 hexSsrcToValue，
        // 这里若直接传十六进制串，parseSsrc 会因「非十进制」拒绝登记，
        // 导致 findByStream 的 SSRC 兜底失效 —— 表现为无人观看关不掉流、
        // 录像找不到媒体源（实测两者同时出现）。
        bySsrc.put(ssrc, session);
        // 同步墓碑：下次重启后仍能靠它重新认领
        rememberSsrc(ssrc, session);
        log.info("设备不认 BYE，已重新认领仍在推流的孤儿流: ssrc={} 通道={} zlmStream={}",
                ssrcHex, key, session.getStream());
        SessionListener l = listener;
        if (l != null) {
            try {
                l.onSessionOnline(session);
            } catch (Throwable t) {
                log.warn("认领孤儿流后通知监听者失败: {}", t.toString());
            }
        }
        return session;
    }

    /**
     * 孤儿流认领的设备校验器：给定设备编码，返回「该设备当前是否在线」。
     *
     * <p>由上层业务（starter，能查数据库）注入。未注入时不校验 —— 但那是危险的：
     * SSRC 回绕会让流被认领给已离线的通道，把它错误地置为在线。
     */
    public void setOrphanAdopter(java.util.function.Predicate<String> adopter) {
        this.orphanAdopter = adopter;
    }

    private volatile java.util.function.Predicate<String> orphanAdopter;

    /** 会话键后缀 → 媒体类型（墓碑重建时用）。 */
    private static MediaType typeOfKey(String sessionKey) {
        if (sessionKey.endsWith("_playback")) {
            return MediaType.PLAYBACK;
        }
        if (sessionKey.endsWith("_download")) {
            return MediaType.DOWNLOAD;
        }
        return MediaType.REALTIME;
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
     * 按「设备编码 + 通道编码 + 类型」取会话。
     *
     * <p>直播与回放/下载是三个独立会话（键带类型后缀），需要定位历史流时用本方法。
     * 传 {@code null} 或 {@link MediaType#REALTIME} 等价于
     * {@link #getByChannel(String, String)}。
     */
    public RtpSession getByChannel(String deviceId, String channelId, MediaType type) {
        return byChannel.get(sessionKey(deviceId, channelId, type));
    }

    /**
     * 按通道编码取会话。
     *
     * <p><b>仅在无法确定设备编码时使用</b>：通道编码跨设备可能重复，
     * 这里返回任意一个匹配的会话。
     * 能拿到设备编码时请用 {@link #getByChannel(String, String)}。
     *
     * <p><b>优先返回 ONLINE 的会话</b>：同一通道上可能同时存在多个会话 ——
     * 最典型的是一个早已死掉的 {@code NEGOTIATING} 会话（INVITE 失败/设备离线，
     * 没人清它）与一个正常推流的 {@code ONLINE} 会话。此时若按遍历顺序返回，
     * 很可能拿到那个僵尸，而它的 {@code app/stream} 是按当时参数拼的，
     * 于是调用方（如云端录像解析媒体源）会去找一个根本不存在的源，
     * 录制永远失败且只能靠重启恢复。实测正是如此。
     */
    public RtpSession getByChannel(String channelId) {
        if (StringUtils.isBlank(channelId)) {
            return null;
        }
        // 先按「设备编码=通道编码」直连场景（app == stream）尝试，最常见且无歧义
        RtpSession direct = byChannel.get(channelId + "/" + channelId);
        if (direct != null && RtpSession.ONLINE.equals(direct.getState())) {
            return direct;
        }
        RtpSession online = null;
        RtpSession negotiating = null;
        RtpSession closed = null;
        for (RtpSession s : byChannel.values()) {
            if (!channelId.equals(s.getChannelId())) {
                continue;
            }
            if (RtpSession.ONLINE.equals(s.getState())) {
                if (online == null) {
                    online = s;
                }
            } else if (RtpSession.CLOSED.equals(s.getState())) {
                if (closed == null) {
                    closed = s;
                }
            } else if (negotiating == null) {
                negotiating = s;
            }
        }
        // 在线的最可信；其次才是协商中；都没有才退到已关闭的（调用方据此重建）
        if (online != null) {
            return online;
        }
        if (negotiating != null) {
            return negotiating;
        }
        if (closed != null) {
            return closed;
        }
        return direct;
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
     * 从 ZLM 命名反推——单端口流名形如 {@code <设备编码>_<通道编码>}，
     * 历史流再带 {@code _playback} / {@code _download} 后缀，可无损还原。
     */
    private String channelKeyOf(String app, String stream) {
        RtpSession session = findByStream(app, stream);
        if (session != null) {
            return session.getSessionKey();
        }
        // 事件先于会话登记：按 ZLM 命名规则反推业务键
        if (SINGLE_PORT_APP.equals(app)) {
            String s = stripTypeSuffix(stream);
            int sep = s == null ? -1 : s.indexOf('_');
            if (sep > 0 && sep < s.length() - 1) {
                return s.substring(0, sep) + "/" + s.substring(sep + 1)
                        + typeSuffixOf(stream);
            }
            return null;
        }
        if (StringUtils.isBlank(app) || StringUtils.isBlank(stream)) {
            return null;
        }
        // 独占端口：app=设备编码、stream=通道编码（+ 历史流后缀）
        return app + "/" + stripTypeSuffix(stream) + typeSuffixOf(stream);
    }

    /** 去掉历史流后缀，还原成裸流名。 */
    private static String stripTypeSuffix(String stream) {
        if (stream == null) {
            return null;
        }
        if (stream.endsWith("_playback")) {
            return stream.substring(0, stream.length() - "_playback".length());
        }
        if (stream.endsWith("_download")) {
            return stream.substring(0, stream.length() - "_download".length());
        }
        return stream;
    }

    /** 取历史流后缀（无后缀返回空串）。 */
    private static String typeSuffixOf(String stream) {
        if (stream == null) {
            return "";
        }
        if (stream.endsWith("_playback")) {
            return "_playback";
        }
        if (stream.endsWith("_download")) {
            return "_download";
        }
        return "";
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
        // 归档必须在 closeMediaSource【之后】：单端口模式下录制停止正是由它触发
        // （handle == null，mk_rtp_server_release 被跳过）。跑在前面时
        // ZLM 索引 endTime 仍为 0、文件仍是 . 前缀，归档拿不到任何东西。
        closeMediaSource(session);
        notifySourceClosed(session);
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
            // 归档录像必须在注销媒体源【之后】，不能在此之前。
            //
            // 单端口模式下 handle == null，mk_rtp_server_release 被跳过，
            // 录制停止实际由 closeMediaSource 触发（源消失 → 流下线 → 停录）。
            // 钩子若跑在它之前，ZLM 索引里 endTime 仍为 0（还在录）、
            // 磁盘文件仍是 . 前缀（尚未改名），归档什么也拿不到 ——
            // 实测连续轮询 31 次 endTime 恒为 0，正是这个原因。
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
            // 此时录制已停、文件已改名，归档才能拿到终态数据
            notifySourceClosed(session);
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
