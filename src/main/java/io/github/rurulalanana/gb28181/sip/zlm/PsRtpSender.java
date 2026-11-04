package io.github.rurulalanana.gb28181.sip.zlm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把国标 PS 码流打成 RTP 包，发给本机 ZLM 的收流端口。
 *
 * <h3>它解决什么问题</h3>
 * 平台现有的两路取流方式各有局限：
 * <ul>
 *   <li><b>国标设备</b> —— 设备主动把 PS 推给平台，天然穿透 NAT；</li>
 *   <li><b>大华 RTSP</b> —— 平台<b>反向</b>连设备 554。设备在 NAT/4G 后时不可达，
 *       表现为一直重试、永远出不了图（实测同一台设备 554/37777/80 全部连不上）。</li>
 * </ul>
 * 而大华 SDK 取流走的是<b>已建立的登录会话</b>（与主动注册同方向），
 * 且 {@code CLIENT_RealPlayByDataType + EM_REAL_DATA_TYPE_GBPS} 直接给标准 PS。
 *
 * <p>于是这里只做一件事：<b>把 SDK 拿到的 PS 原样打 RTP、发到 ZLM 的国标收流端口</b>。
 * 这样 ZLM 侧的解复用、轨道建立、SSRC 映射、会话生命周期
 * <b>全部与国标设备一致</b>，FLV/HLS/WebRTC/RTSP 输出链路一行都不用改，
 * 前端更不用动。
 *
 * <h3>为什么自己打 RTP 而不是让 ZLM 解 PS</h3>
 * zlm4j 提供 {@code mk_ps_decoder_create} + {@code mk_media_input_frame}，
 * 但那要自己建 MK_MEDIA、自己建轨道、自己算时间戳，等于把 ZLM 已经做好的
 * GB28181 收流链路重写一遍。走 RTP 则是<b>零新增媒体逻辑</b>。
 *
 * <h3>线程模型</h3>
 * {@link #send} 运行在 SDK 原生回调线程上，只做「切包 + UDP 发送」，
 * 不阻塞、不加锁、不写日志（除首次/异常）。一个实例对应一路流，可并发多个实例。
 */
public final class PsRtpSender implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PsRtpSender.class);

    /**
     * 单个 RTP 包的载荷上限。
     *
     * <p>取 1400 而不是 1500-12-8=1480：留出余量给 PPPoE/PPTP 之类的额外封装
     * （那些环境 MTU 只有 1492 甚至 1400）。超 MTU 会被 IP 分片，
     * 丢一片整帧就废了 —— 国标设备的 PS 帧动辄几十 KB，本来就跨很多包，
     * 再多分片只会更容易丢。
     */
    private static final int MAX_PAYLOAD = 1400;

    /** GB/T 28181 约定：RTP 载荷类型 96 固定为 PS。 */
    private static final int PT_PS = 96;

    private static final int RTP_VERSION = 2;

    private final String key;
    private final InetAddress target;
    private final int port;
    private final int ssrc;
    private final DatagramSocket socket;

    /** RTP 序列号，16 位回绕。 */
    private int sequence;
    /** RTP 时间戳，90kHz。 */
    private long timestamp;
    /** 首包计数，用于「收到第一包 PS」的日志。 */
    private final AtomicLong packets = new AtomicLong();
    private volatile boolean closed;

    /**
     * @param key    流标识（仅日志用，如 {@code 10001/10001-1}）
     * @param host   目标地址，通常是 {@code 127.0.0.1}
     * @param port   ZLM 收流端口（{@link RtpSession#getPort()}）
     * @param ssrc   10 位十进制 SSRC 字符串（{@link RtpSession#getSsrc()}），
     *               必须与 ZLM 登记的完全一致，否则流认不出来
     */
    public PsRtpSender(String key, String host, int port, String ssrc) throws IOException {
        this.key = key;
        this.target = InetAddress.getByName(host);
        this.port = port;
        this.ssrc = parseSsrc(ssrc);
        this.socket = new DatagramSocket();
        // 序列号随机起始：RFC 3550 的安全建议，避免可预测性
        this.sequence = (int) (System.nanoTime() & 0xFFFF);
        this.timestamp = System.nanoTime() / 1000L * 9 / 100;
        log.info("PS→RTP 发送器已就绪: {} → {}:{} ssrc={}", key, host, port, ssrc);
    }

    /**
     * 发送一段 PS 数据。
     *
     * <p><b>必须在 SDK 回调线程上快速返回</b>：本方法只做切包与 UDP send，
     * 不做任何阻塞操作。
     *
     * @param ps   标准 PS 字节（{@code 00 00 01 BA} 开头）
     * @param last 是否是本次回调的最后一段 → 决定 marker 位
     */
    public void send(byte[] ps, boolean last) {
        if (closed || ps == null || ps.length == 0) {
            return;
        }
        try {
            int offset = 0;
            while (offset < ps.length) {
                int len = Math.min(MAX_PAYLOAD, ps.length - offset);
                boolean lastPacket = offset + len >= ps.length;
                byte[] pkt = new byte[12 + len];
                writeHeader(pkt, lastPacket && last, len);
                System.arraycopy(ps, offset, pkt, 12, len);
                socket.send(new DatagramPacket(pkt, pkt.length, target, port));
                sequence = (sequence + 1) & 0xFFFF;
                offset += len;
            }
            // 时间戳按 90kHz 递增：一个回调块按「约等于实际经过时间」推不准确，
            // 用固定步进（每块 3600 = 40ms）更稳 —— 平台只做转发，
            // 真正的帧时间戳由 ZLM 从 PS 里的 PTS 解析，这里不影响画面
            timestamp += 3600;
            long n = packets.incrementAndGet();
            if (n == 1) {
                log.info("PS→RTP 收到首块码流: {} {} 字节", key, ps.length);
            }
        } catch (IOException e) {
            if (!closed) {
                // 发送失败通常是 socket 已关或网络抖动；不刷屏，只记首次
                if (packets.get() < 5) {
                    log.warn("PS→RTP 发送失败: {} {}", key, e.toString());
                }
            }
        }
    }

    /** 已发送的 PS 块数（诊断用）。 */
    public long blocks() {
        return packets.get();
    }

    private void writeHeader(byte[] pkt, boolean marker, int payloadLen) {
        pkt[0] = (byte) (RTP_VERSION << 6);          // V=2, P=0, X=0, CC=0
        pkt[1] = (byte) ((marker ? 0x80 : 0) | PT_PS);
        pkt[2] = (byte) (sequence >> 8);
        pkt[3] = (byte) sequence;
        pkt[4] = (byte) (timestamp >> 24);
        pkt[5] = (byte) (timestamp >> 16);
        pkt[6] = (byte) (timestamp >> 8);
        pkt[7] = (byte) timestamp;
        pkt[8] = (byte) (ssrc >> 24);
        pkt[9] = (byte) (ssrc >> 16);
        pkt[10] = (byte) (ssrc >> 8);
        pkt[11] = (byte) ssrc;
    }

    /** 10 位十进制 SSRC 字符串 → int。ZLM 按这个值反查会话，格式错一位就认不出来。 */
    private static int parseSsrc(String ssrc) {
        if (ssrc == null || !ssrc.matches("\\d{10}")) {
            throw new IllegalArgumentException("SSRC 必须是 10 位十进制数字: " + ssrc);
        }
        return Integer.parseUnsignedInt(ssrc);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (Throwable ignored) {
            // 关闭失败无需处理
        }
        log.info("PS→RTP 发送器已关闭: {} 累计 {} 块", key, packets.get());
    }
}
