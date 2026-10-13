package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_RTP_SERVER;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
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

    private final ZlmMediaServer mediaServer;
    private final MediaServerProperties props;

    private final Map<String, RtpSession> byChannel = new ConcurrentHashMap<>();
    private final Map<String, RtpSession> byCallId = new ConcurrentHashMap<>();
    /**
     * 「流上线事件先于会话登记到达」的流名暂存。
     *
     * <p>{@code mk_rtp_server_create2} 一返回，RTP 服务器就开始监听，设备可能立刻
     * 推流并触发 ZLM 的流注册事件——而此时 {@link #byChannel} 还没 put 进会话，
     * {@link #findByStream} 查不到，事件会被丢掉，会话就永远停在 NEGOTIATING。
     * 先记在这里，等会话登记时补一次上线。
     */
    private final Map<String, Boolean> pendingOnline = new ConcurrentHashMap<>();
    private final AtomicInteger ssrcSeq = new AtomicInteger(1);
    /** ZLM 流名序号（ZLM 注册为 <channelId>/<序号>）。 */
    private final AtomicInteger nextPort = new AtomicInteger(0);

    /** 会话事件监听者（业务侧）。 */
    public interface SessionListener {
        void onSessionOpened(RtpSession session);

        void onSessionOnline(RtpSession session);

        void onSessionOffline(RtpSession session);

        void onSessionClosed(RtpSession session);
    }

    private volatile SessionListener listener;

    public RtpSessionManager(ZlmMediaServer mediaServer, MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.props = props;
    }

    public void setListener(SessionListener listener) {
        this.listener = listener;
    }

    // ==================== 会话生命周期 ====================

    /**
     * 为通道打开收流会话（创建 ZLM RTP 服务器）。
     *
     * @param channelId  通道编码（同时是 ZLM 流名）
     * @param deviceId   设备编码（SIP 层）
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
        RtpSession old = byChannel.get(channelId);
        if (old != null) {
            log.info("通道 {} 已有会话，先关闭旧会话: {}", channelId, old);
            closeByChannel(channelId);
            // mk_rtp_server_release 会同步触发 native detach 回调，
            // 必须等它跑完再建新句柄，否则 ZLM 侧存在二次释放竞争（表现为 JVM 崩溃）
            awaitDetachQuietly(old, 20);
        }

        ZLMApi api = ZlmMediaServer.api();
        MediaType mediaType = type == null ? MediaType.REALTIME : type;
        // SSRC 首位：实时 0 / 历史 1（GB/T 28181 附录 G）；buildSsrc 第三个参数是 realtime
        String ssrc = GbId.buildSsrc(channelId, ssrcSeq.getAndIncrement(), !mediaType.historical());
        String ssrcFilter = normalizeSsrc(deviceSsrc);
        int port = allocatePort();

        MK_RTP_SERVER handle = api.mk_rtp_server_create2((short) port, tcpMode,
                props.getApp(), channelId, ssrcFilter);
        if (handle == null) {
            throw new IllegalStateException("创建 RTP 服务器失败: channel=" + channelId
                    + ", port=" + port + ", tcpMode=" + tcpMode);
        }
        int actualPort = api.mk_rtp_server_port(handle) & 0xFFFF;

        RtpSession session = new RtpSession(channelId, deviceId, ssrc, deviceSsrc, tcpMode,
                callId, handle, actualPort, mediaType, mediaUri);
        // detach 回调：ZLM 侧服务器被关闭/销毁时触发，只做账务清理，绝不重复 release
        final RtpSession[] holder = new RtpSession[]{session};
        Runnable detach = () -> onRtpDetached(holder[0]);
        api.mk_rtp_server_set_on_detach2(handle, ptr -> detach.run(), null, null);

        byChannel.put(channelId, session);
        if (callId != null) {
            byCallId.put(callId, session);
        }
        log.info("打开收流会话: {} rtpPort={} ssrc={} filterSsrc={} tcp={} mediaUri={}",
                session, actualPort, ssrc, ssrcFilter, tcpMode == TCP_MODE_PASSIVE, mediaUri);
        // 补一次「事件先于会话登记到达」的流上线（见 pendingOnline 注释）
        if (pendingOnline.remove(session.getChannelId()) != null) {
            log.info("流上线事件先于会话登记到达，现补记上线: {}", session.getChannelId());
            onStreamOnline(props.getApp(), session.getStream());
        }
        SessionListener l = listener;
        if (l != null) {
            l.onSessionOpened(session);
        }
        return session;
    }

    /** 按通道关闭会话（BYE 携带设备/通道编码时使用）。 */
    public void closeByChannel(String channelId) {
        // 用条件删除：仅当映射仍指向本会话时才摘除。
        // 直接 remove 会把「通道上已换成新会话」的场景一起清掉，
        // 那是重复点播崩溃的直接原因（native detach 回调竞争 → 访问违例）。
        RtpSession session = byChannel.get(channelId);
        if (session != null && !byChannel.remove(channelId, session)) {
            return; // 期间已被新会话替换，无需处理
        }
        if (session != null) {
            unbindCallId(session);
            release(session);
        }
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
            // 同 closeByChannel：必须条件删除。通道上可能已换成新会话，
            // 无条件 remove 会把新会话一并摘掉，后续 point 会报「设备未注册」
            byChannel.remove(session.getChannelId(), session);
            release(session);
        }
    }

    /** 关闭全部会话（服务停止时）。 */
    public void closeAll() {
        for (String channelId : new ArrayList<>(byChannel.keySet())) {
            closeByChannel(channelId);
        }
    }

    public RtpSession getByChannel(String channelId) {
        return byChannel.get(channelId);
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

    /** 流上线（on_mk_media_publish / changed(1)）。 */
    /**
     * 按 ZLM 回调的 (app, stream) 查找会话。
     *
     * <p>ZLM 上报的流标识与建流参数有关，存在两种布局，都要兼容：
     * <ul>
     *   <li>{@code app=rtp, stream=<channelId>/<序号>} —— ZLM 自身 rtp 模块的布局</li>
     *   <li>{@code app=<channelId>, stream=<序号>} ——
     *       {@code mk_rtp_server_create2} 的实际布局（日志里可见
     *       {@code rtp://rtp/3402.../0} 与 {@code app=3402..., stream=0} 两种写法）</li>
     * </ul>
     * 只认一种会导致事件全部落空、会话永远停在 NEGOTIATING。
     */
    private RtpSession findByStream(String app, String stream) {
        if (stream == null) {
            return null;
        }
        // 布局一：stream = <channelId>/<序号>
        int slash = stream.lastIndexOf('/');
        if (slash > 0) {
            RtpSession s = byChannel.get(stream.substring(0, slash));
            // 校验拿到的是否就是 ZLM 上报的这个流（重复点播后旧流下线时可能上报已关闭会话）
            if (s != null && stream.equals(s.getStream())
                    && !RtpSession.CLOSED.equals(s.getState())) {
                return s;
            }
        }
        // 布局二：app = <channelId>
        if (app != null && !props.getApp().equals(app)) {
            RtpSession s = byChannel.get(app);
            if (s != null && !RtpSession.CLOSED.equals(s.getState())) {
                return s;
            }
        }
        // 兜底：stream 本身就是 channelId
        return byChannel.get(stream);
    }

    /**
     * 事件里能确定的目标通道（用于 {@link #pendingOnline} 匹配）。
     *
     * <p>与 {@link #findByStream} 的两种布局对应，最终都归一成 channelId。
     */
    private String channelKeyOf(String app, String stream) {
        if (stream != null) {
            int slash = stream.lastIndexOf('/');
            if (slash > 0) {
                return stream.substring(0, slash);
            }
        }
        if (app != null && !app.isBlank() && !props.getApp().equals(app)) {
            return app;
        }
        return stream;
    }

    /**
     * 按 ZLM 回调的 (app, stream) 定位会话（公开版，供无人观看等外部回调使用）。
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
        log.info("流上线: app={} stream={} channel={}", app, stream, session.getChannelId());
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
        // 重复点播时通道上可能已挂着新会话，只有映射仍指向本会话才清理，
        // 否则会把刚建立的新会话一并摘掉，导致后续 point 报「设备未注册」
        byChannel.remove(session.getChannelId(), session);
        unbindCallId(session);
        pendingOnline.remove(session.getChannelId());
        session.setState(RtpSession.CLOSED);
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
            try {
                ZlmMediaServer.api().mk_rtp_server_release(session.getHandle());
                log.info("已释放 RTP 服务器: {}", session);
            } catch (Throwable t) {
                log.warn("释放 RTP 服务器异常: {}", session, t);
            }
        }
        pendingOnline.remove(session.getChannelId());
        session.setState(RtpSession.CLOSED);
        SessionListener l = listener;
        if (l != null) {
            l.onSessionClosed(session);
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
                if (s.getPort() == candidate) {
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
