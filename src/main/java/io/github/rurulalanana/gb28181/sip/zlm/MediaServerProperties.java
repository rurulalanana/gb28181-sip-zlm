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

    /** HTTP 服务端口（HTTP-FLV / HLS / WebRTC 信令 / HTTP API）。0 = 随机端口。 */
    @Builder.Default
    private final int httpPort = 8080;

    /** RTMP 服务端口。0 = 随机端口；-1 = 不启动。 */
    @Builder.Default
    private final int rtmpPort = 1935;

    /** RTSP 服务端口。0 = 随机端口；-1 = 不启动。 */
    @Builder.Default
    private final int rtspPort = 8554;

    /** WebRTC 服务端口。0 = 随机端口；-1 = 不启动。 */
    @Builder.Default
    private final int rtcPort = 8000;

    /** ZLM 流应用名（播放 URL 路径第一段）。国标生态惯例为 {@code rtp}。 */
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
