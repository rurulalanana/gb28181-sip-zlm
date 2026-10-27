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
     * SIP 信令监听的传输协议，逗号分隔（默认 {@code UDP,TCP}）。
     *
     * <p><b>为什么要同时监听两种</b>：GB/T 28181 规定设备可用 UDP 或 TCP 传输
     * 信令，由设备侧配置决定。只监听 UDP 时，配成 TCP 的设备<b>根本注册不上</b>——
     * 表现为设备侧显示「注册超时」、平台日志里一条记录都没有，
     * 排查时极易误判成网络不通或鉴权失败。
     *
     * <p>两个协议共用 {@link #sipPort}：SIP 的 UDP 与 TCP 是不同传输层，
     * 同端口互不冲突。
     *
     * <p>取值示例：{@code "UDP,TCP"}（默认）、{@code "UDP"}（只 UDP）、
     * {@code "TCP"}（只 TCP）。大小写不敏感，重复项自动去重。
     */
    @Builder.Default
    private final String sipTransports = "UDP,TCP";

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

    /**
     * 接入控制（黑白名单，扩展点）。
     *
     * <p>与 {@link #credentialProvider} 是两道<b>独立</b>的闸门：前者回答
     * 「密码对不对」，本项回答「这个来源许不许进」。在摘要认证<b>之前</b>生效，
     * 因此黑名单里的设备即使密码正确也进不来。
     *
     * <p>不设置（默认）则完全不限制来源，与加此功能之前的行为一致。
     */
    @Builder.Default
    private final io.github.rurulalanana.gb28181.sip.security.AccessControl accessControl = null;

    /**
     * 「这个对端是不是我的上级平台」的判定器（级联上行点播用）。
     *
     * <p><b>为什么必须由业务方注入</b>：上级平台的地址只存在于应用配置里
     * （{@code gb_cascade.parent_id}），媒体模块拿不到数据库。
     * 不注入时返回 {@code null}，行为与本功能不存在时完全一致。
     *
     * <p><b>为什么不能复用下行注册表判断</b>：那个注册表里装的是「谁注册到了我」——
     * 上级永远是<b>我们注册出去</b>的，永远不会出现在里面。用错注册表会让
     * 上行点播 INVITE 落到「设备 INVITE」分支：开一个<b>接收</b>会话、回 200 OK，
     * 然后等设备推流 —— 而实际需要的是我们<b>推</b>给上级，
     * 结果是上级等 20 秒后报「none rtp data」，我们这边日志一片正常。
     */
    @Builder.Default
    private final java.util.function.Predicate<String> uplinkPlatformChecker = null;

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

    /**
     * {@code [rtp_proxy] udp_recv_socket_buffer}：RTP 收流 socket 的接收缓冲（字节）。
     *
     * <p><b>为什么必须显式放大</b>：国标摄像机的编码器不会平滑发送 —— 收到 INVITE 后
     * 它会把攒着的码流<b>一次性倾出</b>（实测首秒包量约为稳态的 2.4 倍）。默认缓冲
     * 只有几百 KB，一波突发就溢出，内核直接丢包（{@code /proc/net/snmp} 里
     * {@code Udp: RcvbufErrors} 会持续增长）。
     *
     * <p><b>丢的往往是开头的包 —— 而那正是 SPS/PPS + 首个 I 帧</b>。ZLM 等齐 track 的
     * 时间一到（{@code All track ready use ≈11s}）会把没就绪的视频轨直接丢弃：
     * {@code Track not ready for a long time, ignored: H264}。这个决定是<b>一次性的</b>，
     * 媒体源从此只是纯音频，浏览器永远是黑屏 —— 用户看到的就是「第一次播放不出画面，
     * 关掉重播才行」。
     *
     * <p><b>光调这里不够</b>：Linux 会把 {@code SO_RCVBUF} 夹到
     * {@code net.core.rmem_max}。宿主机默认往往只有 208KB，所以必须同时调大：
     * <pre>sysctl -w net.core.rmem_max=16777216</pre>
     */
    @Builder.Default
    private final int udpRecvSocketBuffer = 8 * 1024 * 1024;

    /**
     * {@code [general] wait_track_ready_ms}：等各条轨就绪的最长时间（毫秒）。
     *
     * <p><b>这是决定「这段录像能不能播」的关键参数。</b>ZLM 收流后会等所有轨
     * （视频 + 音频）都就绪才建媒体源；时间一到，没就绪的轨会被
     * <b>永久丢弃</b>：
     * <pre>All track ready use 10064ms
     *Track not ready for a long time, ignored: H264
     *codec info: PCMA[8000/1/16]          ← 只剩音频</pre>
     * 此后媒体源就只有音频，浏览器永远是黑屏，重连一万次也没用。
     *
     * <p><b>为什么默认不能是 10 秒</b>：ZLM 默认 10000ms，而国标设备（尤其
     * 经下级平台代理的）从接受 INVITE 到真正推视频流差别极大 —— 实测同一通道
     * 有的录像段 6ms 就齐了，有的要 11 秒。10 秒的阈值恰好卡在后者之外，
     * 于是「有些录像能播、有些怎么都播不出来」，而日志里只有一行 warning。
     *
     * <p>代价只是「设备一直不推视频时多等一会儿」—— 这是后台收流，不影响
     * 用户交互；而把 30 秒内才起推的段救回来是实打实的收益。
     */
    @Builder.Default
    private final int waitTrackReadyMs = 30000;

    /**
     * {@code [general] unready_frame_cache}：轨未就绪期间缓存的帧数上限。
     *
     * <p>超过这个数量就会丢弃最早的缓存帧（日志
     * {@code Cached frame of unready track(H264) is too much, now cleared}）。
     * 默认 100 帧，对 25fps 只有 4 秒 —— 设备慢起推时开头的 SPS/PPS + I 帧
     * 很容易被清掉，于是即便等到了视频轨，首个关键帧也已经没了。
     *
     * <p>放大到 500 帧（约 20 秒 @25fps）配合 {@link #waitTrackReadyMs} 一起用。
     */
    @Builder.Default
    private final int unreadyFrameCache = 500;

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

    /**
     * {@code [rtmp] enhanced}：FLV 视频标签是否使用增强型 RTMP（E-RTMP）头。
     *
     * <p><b>默认为 false（传统 FLV）</b>。开启后 H.265 的 FLV 标签会带 8 字节
     * 增强头（{@code enhanced=1} + FourCC {@code hvc1}），这是 E-RTMP 规范，
     * 但绝大多数 JS 播放器（含 {@code xgplayer-transmuxer}）只认传统 FLV 的
     * {@code codecId=12}，遇到增强头会直接丢弃整个视频轨（表现为「只有声音没有画面」）。
     *
     * <p>关闭后 H.265 走传统扩展格式（{@code codecId=12} + AVCC 风格的 hvcC），
     * 兼容性最好。H.264 不受影响（两种格式都是传统头）。
     */
    @Builder.Default
    private final boolean rtmpEnhanced = false;

    /** {@code [protocol] enable_rtsp}：RTSP/WebRTC。 */
    @Builder.Default
    private final boolean enableRtsp = true;

    /** {@code [protocol] enable_audio}：转协议时是否携带音频。 */
    @Builder.Default
    private final boolean enableAudio = true;

    /**
     * HLS 切片与 HTTP 静态根目录。
     *
     * <p>对应 {@code [protocol] hls_save_path} 与 {@code [http] rootPath}。
     * 两者必须是同一目录，否则切片生成了浏览器也访问不到。
     *
     * <p><b>默认值用相对路径 {@code ./www} 而不是绝对路径</b>：ZLM 把相对
     * 路径解析为「<b>可执行文件所在目录</b>」下的子目录。宿主是 JVM（zlm4j），
     * “可执行文件”就是 {@code java} 本身，于是一个写成
     * {@code System.getProperty("user.dir") + "/www"} 的绝对路径反而更安全：
     * 它不依赖 java 二进制装在哪里。保留绝对路径。
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
