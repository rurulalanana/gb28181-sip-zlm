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
     * <p><b>嵌入式模式（Native.load）下所有协议都不带 app 前缀</b>。
     * 实测 ZLM 1.12：HTTP 根目录直接是 {@code /<channelId>/<序号>/}，
     * 没有 {@code /rtp/} 这一层——app 只作为协议内部命名空间存在，
     * 不体现为 URL 路径段。带前缀的地址会 404。
     *
     * <p>正确形态：
     * <pre>
     *   http://host:8081/&lt;stream&gt;.live.flv
     *   http://host:8081/&lt;stream&gt;/hls.m3u8
     *   rtsp://host:18554/&lt;stream&gt;
     * </pre>
     * 其中 {@code stream} 形如 {@code 34020000001310000001/0}
     * （ZLM 注册的流名带会话序号，见 {@link RtpSession#getStream()}）。
     *
     * @param app 仅用于日志与协议内部，<b>不参与 URL 拼接</b>
     */
    public static PlayUrls of(String host, int httpPort, int rtmpPort, int rtspPort, int rtcPort,
                              String app, String stream) {
        String httpFlv = httpPort > 0
                ? "http://" + host + ":" + httpPort + "/" + stream + ".live.flv" : null;
        String wsFlv = httpPort > 0
                ? "ws://" + host + ":" + httpPort + "/" + stream + ".live.flv" : null;
        String hls = httpPort > 0
                ? "http://" + host + ":" + httpPort + "/" + stream + "/hls.m3u8" : null;
        String webrtc = rtcPort > 0
                ? "webrtc://" + host + ":" + rtcPort + "/" + stream : null;
        String rtsp = rtspPort > 0
                ? "rtsp://" + host + ":" + rtspPort + "/" + stream : null;
        String rtmp = rtmpPort > 0
                ? "rtmp://" + host + ":" + rtmpPort + "/" + stream : null;
        return new PlayUrls(httpFlv, wsFlv, hls, webrtc, rtsp, rtmp);
    }
}
