package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_MEDIA;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 国标级联媒体转发（标准第 8 章的媒体面）。
 *
 * <p>场景：上级平台点播下级平台的通道。标准只定义了信令，媒体要下级自己转发：
 * <pre>
 * 上级 INVITE ──▶ 下级 onCascadeInvite
 *                    ├─ 1) 本地 RTP 服务器接收下级设备的实时流（复用 RtpSessionManager）
 *                    ├─ 2) ZLM 把该流转成 PS/RTP 发往上级给出的收流地址
 *                    └─ 3) 用本地收流端口回 200 OK 给上级
 * </pre>
 *
 * <p>转发用 ZLM 的 {@code mk_media_create + mk_media_start_send_rtp}：
 * 建立一个指向本地流（app/stream）的 Media，再用 PS 封装发往上级。
 *
 * <p><b>限制</b>：级联合并后的流按上级 SSRC 过滤发送；ZLMediaKit 的
 * {@code startSendRtp} 走 UDP 单向发送，上级若要求 TCP 主动连接需上层另行处理。
 */
public class CascadeForwarder {

    private static final Logger log = LoggerFactory.getLogger(CascadeForwarder.class);

    private final ZlmMediaServer mediaServer;
    private final RtpSessionManager sessionManager;
    private final MediaServerProperties props;

    /** 上级会话 Call-ID → 转发上下文。 */
    private final Map<String, ForwardContext> forwards = new ConcurrentHashMap<>();

    public CascadeForwarder(ZlmMediaServer mediaServer, RtpSessionManager sessionManager,
                            MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.sessionManager = sessionManager;
        this.props = props;
    }

    /** 一次级联转发的上下文。 */
    public static class ForwardContext {
        /** 转发给上级的 SSRC。 */
        public final String ssrc;
        /** 本地收流端口（用于回 200 OK）。 */
        public final int localPort;
        /** MK_MEDIA 句柄，结束时需 release。 */
        public final MK_MEDIA media;
        /** 收下级流的会话。 */
        public final RtpSession session;

        ForwardContext(String ssrc, int localPort, MK_MEDIA media, RtpSession session) {
            this.ssrc = ssrc;
            this.localPort = localPort;
            this.media = media;
            this.session = session;
        }
    }

    /**
     * 处理上级发来的级联点播。
     *
     * @param event  上级点播事件（模块已解析平台编码与 SSRC）
     * @param callId 级联 INVITE 的 Call-ID（用于会话与转发上下文关联）
     * @return 转发上下文；失败返回 {@code null}
     */
    public ForwardContext onCascadeInvite(CascadeEvents.CascadeInvite event, String callId) {
        String channelId = event.getPlatformId();
        RtpSession session = sessionManager.open(channelId, channelId, event.getSsrc(),
                0, callId, MediaType.REALTIME, null);

        // ZLM Media：指向本地实时流，稍后用于转推
        MK_MEDIA media = ZlmMediaServer.api().mk_media_create(
                props.getVhost(), props.getApp(), channelId, 1.0f, 1, 0);
        if (media == null) {
            log.error("创建级联转发 Media 失败: channel={}", channelId);
            sessionManager.closeByChannel(channelId);
            return null;
        }
        ForwardContext ctx = new ForwardContext(session.getSsrc(), session.getPort(), media, session);
        forwards.put(callId, ctx);
        log.info("级联点播已建立转发: channel={} 本地端口={} 转发SSRC={}",
                channelId, session.getPort(), session.getSsrc());
        return ctx;
    }

    /**
     * 开始向指定地址转发 PS/RTP（一般在本地流上线后调用）。
     *
     * @param callId  级联 INVITE 的 Call-ID
     * @param dstIp   上级平台的媒体收流 IP
     * @param dstPort 上级平台的媒体收流端口
     * @return 是否成功发起
     */
    public boolean startForward(String callId, String dstIp, int dstPort) {
        ForwardContext ctx = forwards.get(callId);
        if (ctx == null) {
            log.warn("无级联转发上下文: callId={}", callId);
            return false;
        }
        ZLMApi api = ZlmMediaServer.api();
        api.mk_media_start_send_rtp(ctx.media, dstIp, (short) dstPort, ctx.ssrc, 1, null, null);
        log.info("级联转发已启动: callId={} → {}:{} ssrc={}", callId, dstIp, dstPort, ctx.ssrc);
        return true;
    }

    /** 停止并清理一次级联转发（上级 BYE 时调用）。 */
    public void stopForward(String callId) {
        ForwardContext ctx = forwards.remove(callId);
        if (ctx == null) {
            return;
        }
        try {
            ZlmMediaServer.api().mk_media_stop_send_rtp(ctx.media, null);
        } catch (Throwable t) {
            log.debug("停止转发异常", t);
        }
        try {
            ZlmMediaServer.api().mk_media_release(ctx.media);
        } catch (Throwable t) {
            log.warn("释放 Media 失败", t);
        }
        sessionManager.closeByChannel(ctx.session.getChannelId());
        log.info("级联转发已停止: callId={}", callId);
    }

    public ForwardContext context(String callId) {
        return forwards.get(callId);
    }

    /** 给上级回 200 OK 的 SDP（内容为下级平台的收流地址与端口）。 */
    public SdpMessage buildUpstreamAnswer(String platformId, ForwardContext ctx) {
        return new SdpMessage()
                .origin(platformId, props.getMediaIp())
                .sessionName(SdpMessage.S_PLAY)
                .connection(props.getMediaIp())
                .realtime()
                .mediaPort(ctx.localPort)
                .payloadType("96")
                .rtpmap("96", "PS")
                .ssrc(ctx.ssrc);
    }
}