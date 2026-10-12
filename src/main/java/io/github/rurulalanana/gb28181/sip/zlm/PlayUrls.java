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
    public static PlayUrls of(String host, int httpPort, int rtmpPort, int rtspPort, int rtcPort,
                              String app, String stream) {
        String httpFlv = httpPort > 0 ? "http://" + host + ":" + httpPort + "/" + app + "/" + stream + ".live.flv" : null;
        String wsFlv = httpPort > 0 ? "ws://" + host + ":" + httpPort + "/" + app + "/" + stream + ".live.flv" : null;
        String hls = httpPort > 0 ? "http://" + host + ":" + httpPort + "/" + app + "/" + stream + "/hls.m3u8" : null;
        String webrtc = rtcPort > 0 ? "webrtc://" + host + ":" + rtcPort + "/" + app + "/" + stream : null;
        String rtsp = rtspPort > 0 ? "rtsp://" + host + ":" + rtspPort + "/" + app + "/" + stream : null;
        String rtmp = rtmpPort > 0 ? "rtmp://" + host + ":" + rtmpPort + "/" + app + "/" + stream : null;
        return new PlayUrls(httpFlv, wsFlv, hls, webrtc, rtsp, rtmp);
    }
}
