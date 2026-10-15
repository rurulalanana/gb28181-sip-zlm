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

    /**
     * SIP 注册鉴权密码（平台密码）。
     *
     * <p>设备 REGISTER 时平台用 Digest 挑战（401 + WWW-Authenticate），设备带上
     * 凭据再次 REGISTER，平台校验密码。
     *
     * <ul>
     *   <li>留空（默认）→ 不鉴权（{@code PERMISSIVE}），任何设备都能注册。
     *       适合内网联调；公网部署务必设置。</li>
     *   <li>非空 → 平台统一密码，所有设备共用。</li>
     * </ul>
     *
     * <p><b>注意</b>：鉴权只看密码，不校验用户名——SIP 规范里 Authorization 的
     * username 只是查密码用的键，设备实现差异很大（有的填 SIP 域，有的填设备编号），
     * 强制校验会导致大量设备注册失败。
     */
    @Builder.Default
    private final String sipPassword = null;

    /**
     * 自定义凭据提供者（扩展点）。
     *
     * <p>设置后优先级高于 {@link #sipPassword}。入参是 Authorization 头的
     * username（<b>不是</b>设备编号），需要按设备区分密码时在这里自行解析。
     */
    @Builder.Default
    private final io.github.rurulalanana.gb28181.sip.security.CredentialProvider credentialProvider = null;

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
     * WebRTC 对外通告 IP（ZLM 配置项 {@code rtc.externIP}）。
     *
     * <p>ZLM 默认把本机网卡地址作为 ICE host candidate 下发。服务器在 NAT 后时
     * （网卡是内网地址、对外是另一个公网 IP），浏览器拿到内网候选连不上，
     * 表现为 <b>SDP 协商成功但画面始终 0x0</b>、播放器提示「连接中断」。
     *
     * <p>留空时回退 {@link #mediaIp}（同机部署时二者相同）。
     * 仅在 WebRTC 走独立入口时才需要单独覆盖。
     */
    @Builder.Default
    private final String rtcExternIp = null;

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

    /**
     * 单端口收流模式（默认关闭，保持每通道独占端口）。
     *
     * <p>开启后所有设备推流到<b>同一个端口</b>，ZLM 按 RTP 头里的 SSRC 分流，
     * 端口数不再受 ZLM 内置端口池（{@code [30000,35000]}，仅 2500 对）限制，
     * 防火墙也只需放行一个端口。
     *
     * <p><b>代价</b>（ZLM 单端口分支不创建 {@code RtcpHelper} 所致）：
     * <ul>
     *   <li>RTCP 收发失效，{@code mk_media_source_get_track_loss} 之外的丢包统计不可用</li>
     *   <li>{@code mk_rtp_server_set_on_detach2} 不触发——RTP 超时后 ZLM 销毁的是
     *       单个 SSRC 的 {@code RtpProcess}，平台需改由流下线事件感知</li>
     *   <li>{@code mk_rtp_server_update_ssrc} 无效，无法做 SSRC 过滤</li>
     * </ul>
     *
     * <p>流名由 {@code stream_replace} 归一为 {@code <设备编码>_<通道编码>}
     * （见 {@code RtpSession#getStream()}），因此上游业务（播放地址、媒体源定位、
     * 录像索引）无需感知端口差异。
     */
    @Builder.Default
    private final boolean rtpSinglePort = false;

    /** 单端口模式下的收流端口（UDP 与 TCP 被动共用同一端口号）。 */
    @Builder.Default
    private final int rtpSinglePortNumber = 24560;

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
     * 是否把 ZLM 日志经 {@code on_mk_log} 回调转发到 SLF4J（默认<b>关</b>）。
     *
     * <p>关闭时 ZLM 日志直写控制台，好处是进程能正常退出；开启后可按级别映射到
     * SLF4J 并屏蔽已知无害噪音，但<b>嵌入 ZLM 的进程将无法正常退出</b>：
     * ZLM 静态析构时会往回调通道写最后一条日志，而此时 JVM 已在退出中，
     * 结果是死锁（{@code thread::join} 互等）或 SIGABRT（{@code recursive_mutex lock failed}）。
     * 详见 {@code ZlmMediaServer#LOG_MASK_CALLBACK}。
     */
    @Builder.Default
    private final boolean zlmLogToSlf4j = false;

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
