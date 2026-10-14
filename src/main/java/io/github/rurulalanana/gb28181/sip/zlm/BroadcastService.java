package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_MEDIA;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音广播与对讲（标准 A.2.3.8 AudioBroadcast）。
 *
 * <p><b>上行（设备 → 平台）</b>：平台广播设备前，设备按标准发 INVITE
 * （{@code s=AudioBroadcast}），本服务分配收流端口、回 200 OK，设备把 PS 流推上来，
 * ZLM 转成 FLV/WebRTC 供前端收听。
 *
 * <p><b>下行（平台 → 设备）</b>：用 ZLM 的 {@code mk_media_start_send_rtp} 把平台侧流
 * 以 PS 封装发给设备的收流地址。
 *
 * <p><b>限制</b>：当前 ZLMediaKit 的 C API 未暴露 talk（{@code startSendRtpTalk}）能力，
 * 因此严格意义上的<b>双向同时对讲</b>无法实现，只能「上行收 + 下行发」分别进行。
 */
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    private final ZlmMediaServer mediaServer;
    private final RtpSessionManager sessionManager;
    private final MediaServerProperties props;

    /** 广播会话 Call-ID → 媒体句柄（下行用）。 */
    private final Map<String, MK_MEDIA> senders = new ConcurrentHashMap<>();

    public BroadcastService(ZlmMediaServer mediaServer, RtpSessionManager sessionManager,
                            MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.sessionManager = sessionManager;
        this.props = props;
    }

    /**
     * 处理设备发来的语音广播 INVITE：分配收流端口。
     *
     * @return 收流会话，失败返回 {@code null}
     */
    public RtpSession onBroadcastInvite(String deviceId, String callId) {
        try {
            RtpSession session = sessionManager.open(deviceId, deviceId, null, 0, callId,
                    MediaType.REALTIME, null);
            session.setState(RtpSession.NEGOTIATING);
            log.info("语音广播会话建立: device={} port={}", deviceId, session.getPort());
            return session;
        } catch (Exception e) {
            log.error("语音广播会话建立失败: device={}", deviceId, e);
            return null;
        }
    }

    /** 回给设备的 200 OK SDP（语音广播的收流地址）。 */
    public SdpMessage buildAnswer(String deviceId, RtpSession session) {
        return new SdpMessage()
                .origin(props.getPlatformId(), props.getMediaIp())
                .sessionName("AudioBroadcast")
                .connection(props.getMediaIp())
                .realtime()
                .mediaPort(session.getPort())
                .payloadType("96")
                .rtpmap("96", "PS")
                .ssrc(session.getSsrc())
                .attr("sendonly");
    }

    /**
     * 下行：把平台侧一路流以 PS/RTP 发给设备的收流地址（对讲下行 / 广播下行）。
     *
     * @param srcApp/srcStream 平台侧源流（ZLM 中已存在）
     * @param dstIp/dstPort    设备的媒体收流 IP/端口（从对端 SDP 解析）
     * @param ssrc             发给设备的 SSRC
     * @return 是否成功发起
     */
    public boolean startDownlink(String srcApp, String srcStream, String dstIp, int dstPort, String ssrc) {
        ZLMApi api = ZlmMediaServer.api();
        MK_MEDIA media = api.mk_media_create(props.getVhost(), srcApp, srcStream, 1.0f, 1, 0);
        if (media == null) {
            log.warn("创建下行 Media 失败: {}/{}", srcApp, srcStream);
            return false;
        }
        api.mk_media_start_send_rtp(media, dstIp, (short) dstPort, ssrc, 1, null, null);
        log.info("广播/对讲下行已启动: {}/{} → {}:{} ssrc={}", srcApp, srcStream, dstIp, dstPort, ssrc);
        return true;
    }

    /** 停止某个会话的下行发送并释放收流端口。 */
    public void stop(String callId, String channelId) {
        if (callId != null) {
            MK_MEDIA media = senders.remove(callId);
            if (media != null) {
                ZlmMediaServer.api().mk_media_release(media);
            }
        }
        if (channelId != null) {
            sessionManager.closeByChannel(channelId);
        }
    }

    /**
     * 广播流的播放地址（上行收流后前端收听用）。
     *
     * @param deviceId  设备编码（= ZLM app）
     * @param channelId 通道编码（= ZLM stream）
     */
    public PlayUrls playUrls(String deviceId, String channelId) {
        return mediaServer.playUrls(deviceId, channelId);
    }
}