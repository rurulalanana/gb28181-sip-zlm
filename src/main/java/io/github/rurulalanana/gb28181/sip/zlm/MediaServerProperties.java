package io.github.rurulalanana.gb28181.sip.zlm;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 流媒体服务配置。
 *
 * <p>媒体 IP（{@link #mediaIp}）是 SIP 应答 SDP 中 {@code c=} 行的地址，
 * 必须是<b>设备可达</b>的 ZLMediaKit 所在机器地址——填错会导致设备把 RTP
 * 发到别处从而收不到流。默认自动探测本机第一块非回环网卡的 IPv4。
 */
@Getter
@Builder
@ToString
public class MediaServerProperties {

    /**
     * 媒体服务 IP（SDP {@code c=} 行 / 播放地址主机名）。
     * 默认自动探测本机非回环 IPv4。
     */
    @Builder.Default
    private final String mediaIp = detectLocalIp();

    /** SIP 服务器 20 位编码（SDP {@code o=} 行 username）。 */
    @Builder.Default
    private final String platformId = "34020000002000000001";

    /** SIP 服务监听端口（信令）。 */
    @Builder.Default
    private final int sipPort = 5060;

    /** SIP 服务监听地址。 */
    @Builder.Default
    private final String sipHost = "0.0.0.0";

    /**
     * SIP 对外通告地址（写入 INVITE 的 From / Contact）。
     *
     * <p>留空时自动推断本机非回环 IPv4（与 {@link #mediaIp} 同源）。
     * NAT 后或多网卡场景应显式配置为设备可达的地址。
     */
    @Builder.Default
    private final String sipAdvertisedIp = null;

    /** HTTP 服务端口（HTTP-FLV / HLS / WebRTC 信令 / HTTP API）。0 = 自动选空闲端口。 */
    @Builder.Default
    private final int httpPort = 8080;

    /** RTMP 服务端口。0 = 自动选空闲端口；-1 = 不启动。 */
    @Builder.Default
    private final int rtmpPort = 1935;

    /** RTSP 服务端口。0 = 自动选空闲端口；-1 = 不启动。 */
    @Builder.Default
    private final int rtspPort = 8554;

    /** WebRTC 服务端口。0 = 自动选空闲端口；-1 = 不启动。 */
    @Builder.Default
    private final int rtcPort = 8000;

    /**
     * ZLM 流应用名（播放 URL 路径第一段）。国标生态惯例为 {@code rtp}。
     * <p>注意：嵌入式模式下 ZLM 未注册 HTTP 播放路由，{@code app/stream} 主要用于
     * WebRTC、RTSP、RTMP 与录像索引。
     */
    @Builder.Default
    private final String app = "rtp";

    /**
     * RTP 固定端口范围起点。0 = 由 ZLM 自动分配随机端口（默认，零配置）。
     * 需要防火墙放行固定端口时设为范围起点（如 20000），配合 {@link #rtpPortRangeEnd}。
     */
    @Builder.Default
    private final int rtpPortRangeStart = 0;

    /** RTP 固定端口范围终点（含）。 */
    @Builder.Default
    private final int rtpPortRangeEnd = 0;

    /** ZLM 虚拟主机名（默认虚拟主机）。 */
    @Builder.Default
    private final String vhost = "__defaultVhost__";

    // ==================== ZLM 转协议配置（库模式无 ini，须显式注入） ====================

    /** {@code [protocol] enable_hls}：关闭则 HLS 切片不生成。 */
    @Builder.Default
    private final boolean enableHls = true;

    /** {@code [protocol] enable_ts}：HTTP-TS。 */
    @Builder.Default
    private final boolean enableTs = true;

    /** {@code [protocol] enable_fmp4}：HTTP-fMP4。 */
    @Builder.Default
    private final boolean enableFmp4 = true;

    /** {@code [protocol] enable_rtmp}：RTMP/FLV。 */
    @Builder.Default
    private final boolean enableRtmp = true;

    /** {@code [protocol] enable_rtsp}：RTSP/WebRTC。 */
    @Builder.Default
    private final boolean enableRtsp = true;

    /** {@code [protocol] enable_audio}：转协议时是否携带音频。 */
    @Builder.Default
    private final boolean enableAudio = true;

    /**
     * HLS 切片与 HTTP 静态根目录。
     *
     * <p>对应 {@code [hls] hls_save_path} 与 {@code [http] rootPath}。
     * 两者必须是同一目录，否则切片生成了浏览器也访问不到。
     */
    @Builder.Default
    private final String hlsSavePath = System.getProperty("user.dir") + "/www";

    /** {@code [hls] segDur}：切片时长（秒）。 */
    @Builder.Default
    private final int hlsSegDur = 2;

    /** {@code [hls] segNum}：m3u8 中保留的切片数。 */
    @Builder.Default
    private final int hlsSegNum = 3;

    /** {@code [hls] segRetain}：切片移出 m3u8 后在磁盘保留的个数。 */
    @Builder.Default
    private final int hlsSegRetain = 5;

    /** {@code [general] maxStreamWaitMS}：播放器等流上线的最长时间（毫秒）。 */
    @Builder.Default
    private final int maxStreamWaitMs = 15000;

    /**
     * {@code [general] streamNoneReaderDelayMS}：无人观看判定时长（毫秒）。
     *
     * <p>超过此时长仍无播放者，触发 {@code on_mk_media_no_reader} 回调。
     * 取值过短会误杀刚开播的流，建议 ≥ 15000。
     */
    @Builder.Default
    private final int streamNoneReaderDelayMs = 20000;

    /** 平台录像根目录，按 {@code 根目录/通道编码/} 分目录存放。 */
    @Builder.Default
    private final String recordRoot = System.getProperty("user.dir") + "/record";

    /** 平台录像单文件最大时长（秒），到点自动分段。 */
    @Builder.Default
    private final int recordMaxSecond = 3600;

    /** ZLM 录像点播应用名（HTTP 直链播放用）。 */
    @Builder.Default
    private final String recordApp = "record";

    /** 是否随流上下线自动开始/停止平台录像。 */
    @Builder.Default
    private final boolean autoRecord = false;

    /**
     * ZLM 的 MPEG-PS 负载类型（{@code rtp.ps_pt}）。0 = 用 ZLM 默认（96，国标）。
     * 若要用 ffmpeg 推裸 H264 测试流，可把 PS 改到 98 并把 H264 设为 96。
     */
    @Builder.Default
    private final int psPayloadType = 0;

    /**
     * ZLM 的 H264 负载类型（{@code rtp.h264_pt}）。0 = 用 ZLM 默认。
     */
    @Builder.Default
    private final int h264PayloadType = 0;

    /** ZLM 的 H265 负载类型（{@code rtp.h265_pt}）。0 = 用 ZLM 默认。 */
    @Builder.Default
    private final int h265PayloadType = 0;

    // ==================== 内部工具 ====================

    /** 探测本机第一个非回环 IPv4 地址；失败时退回 127.0.0.1。 */
    static String detectLocalIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignore) {
            // 探测失败退回回环地址
        }
        return "127.0.0.1";
    }
}
