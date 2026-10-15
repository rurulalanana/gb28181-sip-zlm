package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.callback.IMKWebRtcGetAnwerSdpCallBack;
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
 * <p><b>为什么需要它</b>：ZLMediaKit 的 {@code /index/api/*} 管理 API 由
 * <b>MediaServer 可执行文件</b>注册，C API 嵌入方（zlm4j）里这些路由不存在。
 * 但 {@code ws://<host>:<httpPort>/<app>/<stream>.live.flv}（WebSocket-FLV）
 * 由 ZLM 内置 HTTP 服务直接提供，嵌入式模式下同样可用（实测握手成功）；
 * WebRTC 则提供更低延迟（&lt;500ms）的通路，代价是需浏览器与平台网络可直连
 * （无 ICE 服务时不能跨 NAT）。
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
     * @param app      ZLM 应用名（= 设备编码）
     * @param stream   流名（= 通道编码）
     * @param offerSdp 浏览器生成的 offer
     * @param timeoutSeconds 等待 answer 的秒数（<b>下限</b>）
     */
    public String exchange(String peerId, String app, String stream, String offerSdp, int timeoutSeconds)
            throws Exception {
        ZLMApi api = ZlmMediaServer.api();
        CompletableFuture<String> answer = new CompletableFuture<>();
        // 回调为 JNA 原生线程调用，必须保证引用存活到回调结束
        Peer peer = new Peer(peerId, answer);
        peers.put(peerId, peer);

        // mk_webrtc_get_answer_sdp(user_data, cb, type, offer, url)
        // 第 4 个参数是浏览器 offer SDP，第 5 个是 rtc://vhost/app/stream 逻辑地址。
        // 两者都是 String，位置传反不会编译报错，但原生侧会把 peerId 当 SDP 解析 →
        // java.lang.Error: Invalid memory access。
        //
        // url 必须与已注册媒体源完全一致（ZLM 内部 MediaInfo 解析后 findAsync 找 RTSP 源）。
        // 建流时已固定 app=设备编码、stream=通道编码，命名确定，直接拼即可。
        //
        // JNA 只以 WeakHashMap 弱引用登记回调，Java 侧不强引用则回调可能在原生
        // 线程回调前被 GC（原生 trampoline 一并回收 → 崩溃或 answer 永远不返回）。
        // 故由 Peer 持有强引用，随 peers.remove 一同释放（对应 zlm4j 开发规范：
        // 常驻内存的 native 资源需由 Java 侧持有并提供释放方法）。
        final String rtcUrl = mediaServer.resolveRtcUrl(app, stream);
        IMKWebRtcGetAnwerSdpCallBack onAnswer = (ptr, sdp, err) -> {
            if (sdp != null && !sdp.isEmpty()) {
                answer.complete(sdp);
            } else {
                answer.completeExceptionally(new IllegalStateException(
                        "ZLM 生成 answer 失败: " + (err == null ? "未知错误" : err)
                                + "（协商地址 " + rtcUrl + "，请确认该流已建立）"));
            }
        };
        peer.setCallback(onAnswer);
        // 与 ZlmMediaServer 事件回调同理：先固定线程再交给原生侧，
        // 否则 JNA 会在回调结束后尝试 detach 失败，往 stderr 打「could not detach thread」
        ZlmMediaServer.pinCallbackThread(onAnswer);
        api.mk_webrtc_get_answer_sdp(null, onAnswer, TYPE_PLAY, offerSdp, rtcUrl);

        // ZLM 的 play 插件内部走 MediaSource::findAsync：源不存在时不会立刻失败，而是
        // 挂到媒体注册事件上等流上线，最多等 general.maxStreamWaitMS 才回调
        // 「stream not found」。等待时间必须比它长，否则只能拿到 TimeoutException，
        // 看不到 ZLM 的真实原因。故这里取两者较大值，再加 3s 余量。
        int waitSeconds = Math.max(timeoutSeconds, mediaServer.getMaxStreamWaitMs() / 1000 + 3);
        try {
            String result = answer.get(waitSeconds, TimeUnit.SECONDS);
            log.info("WebRTC SDP 交换完成: peer={} stream={}/{}", peerId, app, stream);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("ZLM 在 " + waitSeconds + " 秒内未返回 answer（地址 " + rtcUrl
                    + "）。请确认该流已注册：设备是否已应答 INVITE 并发送 RTP。");
        } finally {
            Peer removed = peers.remove(peerId);
            if (removed != null) {
                // 交换已结束（成功/超时/异常），回调不会再触发，释放强引用
                removed.setCallback(null);
            }
        }
    }

    /** 应用层回调的强引用持有者（JNA 回调不允许被 GC）。 */
    private static final class Peer {
        final String peerId;
        final CompletableFuture<String> answer;
        /** 见 {@link #exchange}：JNA 不持有回调强引用，必须由本对象兜住。 */
        private volatile IMKWebRtcGetAnwerSdpCallBack callback;

        Peer(String peerId, CompletableFuture<String> answer) {
            this.peerId = peerId;
            this.answer = answer;
        }

        void setCallback(IMKWebRtcGetAnwerSdpCallBack callback) {
            this.callback = callback;
        }
    }

    /** 生成一个建议的 peerId。 */
    public String nextPeerId() {
        return "peer-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
    }
}