package io.github.rurulalanana.gb28181.sip.zlm;

import lombok.Getter;
import lombok.ToString;

/**
 * 一路流的各协议播放地址。
 *
 * <p>ZLMediaKit 收到 GB28181 设备的 RTP/PS 流后会自动转协议输出，
 * 前端按需选用（浏览器低延迟用 WebRTC / HTTP-FLV，兼容性用 HLS，
 * 第三方系统拉流用 RTSP / RTMP）。
 */
@Getter
@ToString
public class PlayUrls {

    /** HTTP-FLV：延迟低，需 flv.js 播放。 */
    private final String httpFlv;

    /** WebSocket-FLV。 */
    private final String wsFlv;

    /** HLS（m3u8）：兼容性最好，延迟数秒。 */
    private final String hls;

    /** WebRTC：毫秒级延迟，需 ZLM WebRTC 服务。 */
    private final String webrtc;

    /** RTSP。 */
    private final String rtsp;

    /** RTMP。 */
    private final String rtmp;

    public PlayUrls(String httpFlv, String wsFlv, String hls, String webrtc, String rtsp, String rtmp) {
        this.httpFlv = httpFlv;
        this.wsFlv = wsFlv;
        this.hls = hls;
        this.webrtc = webrtc;
        this.rtsp = rtsp;
        this.rtmp = rtmp;
    }

    /** 应用名 + 流名。 */
    /**
     * 生成播放地址。
     *
     * <p>ZLM 媒体源路径就是 {@code /<app>/<stream>}（与 {@code 媒体注册:} 日志里的
     * {@code schema://vhost/app/stream} 一致），故两个段都必须拼上。
     *
     * <p>GB28181 场景下 {@code app} = <b>设备编码</b>、{@code stream} = <b>通道编码</b>
     * （见 {@link RtpSession#getZlmApp()} / {@link RtpSession#getStream()}）：
     * 只拼通道编码会 404，且跨设备同号通道会互相覆盖。
     *
     * <p>正确形态：
     * <pre>
     *   http://host:8081/&lt;设备编码&gt;/&lt;通道编码&gt;.live.flv
     *   http://host:8081/&lt;设备编码&gt;/&lt;通道编码&gt;/hls.m3u8
     *   rtsp://host:18554/&lt;设备编码&gt;/&lt;通道编码&gt;
     * </pre>
     *
     * @param app    应用名（= 设备编码）
     * @param stream 流名（= 通道编码）
     */
    public static PlayUrls of(String host, int httpPort, int rtmpPort, int rtspPort, int rtcPort,
                              String app, String stream) {
        String path = (app == null || app.isBlank()) ? stream : app + "/" + stream;
        String httpFlv = httpPort > 0
                ? "http://" + host + ":" + httpPort + "/" + path + ".live.flv" : null;
        String wsFlv = httpPort > 0
                ? "ws://" + host + ":" + httpPort + "/" + path + ".live.flv" : null;
        String hls = httpPort > 0
                ? "http://" + host + ":" + httpPort + "/" + path + "/hls.m3u8" : null;
        String webrtc = rtcPort > 0
                ? "webrtc://" + host + ":" + rtcPort + "/" + path : null;
        String rtsp = rtspPort > 0
                ? "rtsp://" + host + ":" + rtspPort + "/" + path : null;
        String rtmp = rtmpPort > 0
                ? "rtmp://" + host + ":" + rtmpPort + "/" + path : null;
        return new PlayUrls(httpFlv, wsFlv, hls, webrtc, rtsp, rtmp);
    }
}
