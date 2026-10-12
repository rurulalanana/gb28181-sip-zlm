package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebRTC 播放信令：浏览器 offer ↔ ZLMediaKit answer。
 *
 * <p><b>为什么需要它</b>：ZLMediaKit 的 {@code /index/api/*} 与
 * {@code /app/…live.flv} 等 HTTP 路由都由 <b>MediaServer 可执行文件</b>注册，
 * C API 嵌入方（zlm4j）里这些路由不存在——嵌入式模式下浏览器无法直接拉 FLV/HLS。
 * WebRTC 不依赖 HTTP 路由，只需交换一次 SDP 即可，因此是<b>嵌入式模式唯一能直接在
 * 浏览器里出画面的协议</b>。
 *
 * <p>用法：浏览器 {@code POST /webrtc/play?peerId=x} 带 offer SDP，拿到 answer 后
 * {@code setRemoteDescription} 并回传 answer。
 */
public class WebRtcService {

    private static final Logger log = LoggerFactory.getLogger(WebRtcService.class);

    /** ZLM 的 peer 类型：拉流播放。 */
    private static final String TYPE_PLAY = "play";

    private final ZlmMediaServer mediaServer;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public WebRtcService(ZlmMediaServer mediaServer) {
        this.mediaServer = mediaServer;
    }

    /**
     * 交换 SDP：返回 ZLM 生成的 answer。
     *
     * @param peerId   对端标识（浏览器自定义，同一浏览器页面保持一致即可）
     * @param app      ZLM 应用名
     * @param stream   流名（GB28181 场景为通道编码）
     * @param offerSdp 浏览器生成的 offer
     * @param timeoutSeconds 等待 answer 的超时
     */
    public String exchange(String peerId, String app, String stream, String offerSdp, int timeoutSeconds)
            throws Exception {
        ZLMApi api = ZlmMediaServer.api();
        CompletableFuture<String> answer = new CompletableFuture<>();
        // 回调为 JNA 原生线程调用，必须保证引用存活到回调结束
        Peer peer = new Peer(peerId, answer);
        peers.put(peerId, peer);

        String url = "webrtc://127.0.0.1:" + mediaServer.getRtcPort() + "/" + app + "/" + stream;
        // mk_webrtc_get_answer_sdp(user_data, callback, type, peer, offer)
        api.mk_webrtc_get_answer_sdp(null,
                (ptr, sdp, err) -> {
                    if (sdp != null && !sdp.isEmpty()) {
                        answer.complete(sdp);
                    } else {
                        answer.completeExceptionally(new IllegalStateException(
                                "ZLM 生成 answer 失败: " + (err == null ? "未知错误" : err)
                                        + "（请确认 WebRTC 端口已启动、流存在：" + url + "）"));
                    }
                },
                TYPE_PLAY, peerId, offerSdp);

        try {
            String result = answer.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("WebRTC SDP 交换完成: peer={} stream={}/{}", peerId, app, stream);
            return result;
        } finally {
            peers.remove(peerId);
        }
    }

    /** 应用层回调的强引用持有者（JNA 回调不允许被 GC）。 */
    private static final class Peer {
        final String peerId;
        final CompletableFuture<String> answer;

        Peer(String peerId, CompletableFuture<String> answer) {
            this.peerId = peerId;
            this.answer = answer;
        }
    }

    /** 生成一个建议的 peerId。 */
    public String nextPeerId() {
        return "peer-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
    }
}