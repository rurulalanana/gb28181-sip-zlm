package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_MEDIA;
import com.sun.jna.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把编码器裸流（H264/H265 ES）直接喂给 ZLM 建流。
 *
 * <h3>它和国标收流的分工</h3>
 * <ul>
 *   <li><b>国标设备</b>：设备把 PS 推给平台 → ZLM 收 RTP → 解 PS 建轨道（{@link RtpSessionManager}）；</li>
 *   <li><b>本类</b>：SDK 拿到<b>裸 H264/H265</b> → 直接 {@code mk_media_input_h264/265} 建轨道。</li>
 * </ul>
 * 两条路最终都由 ZLM 输出 FLV/HLS/WebRTC/RTSP，所以前端与播放地址完全一致。
 *
 * <h3>为什么不用 PS（国标那条路）</h3>
 * 早期试过让 SDK 直接给国标 PS（{@code EM_REAL_DATA_TYPE_GBPS}）再打 RTP 灌进
 * 国标收流端口，看着更「统一」，但实测在<b>主动注册会话</b>上 SDK 只投递
 * 大华私有 DHAV 封装、拿不到 PS（直连会话可以），整条路直接死掉。
 * 改成要裸 H264 后，两个场景都正常 —— 这也是大华官方 demo
 * （{@code RealPlayModule}）的默认用法。
 *
 * <h3>三个必须注意的点（都踩过）</h3>
 * <ol>
 *   <li><b>编码要自己识别，不能信 dwDataType</b>：实测 H265 流也上报 1004
 *       （常量里 1004 名义上是 H264）。首帧从 NAL 单元判断，
 *       判定后缓存，避免 P 帧逐帧误判。</li>
 *   <li><b>时间戳要用本地单调时钟</b>：SDK 回调的 {@code param} 实测是常量，
 *       拿它当时间戳会让 ZLM 认为所有帧同一时刻到达，画面卡住/花屏。</li>
 *   <li><b>首帧到达才算就绪</b>：ZLM 在收到媒体数据前不会真正注册流，
 *       提前返回的播放地址打开是 404。</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 * {@link #push(byte[])} 运行在 SDK 原生回调线程上：只做编解码判定与一次
 * 原生调用，不阻塞、不打逐帧日志。一个实例对应一路流。
 */
public final class EsMediaPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EsMediaPublisher.class);

    /** 编码未定。 */
    private static final int CODEC_UNKNOWN = -1;
    /** H264。 */
    private static final int CODEC_H264 = 0;
    /** H265。 */
    private static final int CODEC_H265 = 1;

    /** 时间戳兜底步长：帧间隔异常时按 25fps 估算。 */
    private static final long FALLBACK_DELTA_MS = 40L;

    private final String key;
    private final String app;
    private final String stream;
    private final MK_MEDIA media;
    private final ZLMApi api;

    /** 编码判定与轨道初始化必须互斥：多线程回调时只能建一次轨道。 */
    private final Object trackLock = new Object();

    private volatile int codec = CODEC_UNKNOWN;
    private volatile boolean trackReady;
    private volatile boolean closed;

    private volatile long firstFrameAt;
    private volatile long lastFrameAt;
    private long lastFrameNano;
    private long outTs;
    private long frames;

    EsMediaPublisher(ZLMApi api, String vhost, String app, String stream,
                     boolean enableHls, boolean enableMp4) {
        this.api = api;
        this.app = app;
        this.stream = stream;
        this.key = app + "/" + stream;
        // duration=0 表示直播；后两个是 hls/mp4 开关
        this.media = api.mk_media_create(vhost, app, stream, 0f,
                enableHls ? 1 : 0, enableMp4 ? 1 : 0);
        if (this.media == null) {
            throw new IllegalStateException("创建 ZLM 媒体源失败: " + key);
        }
        log.info("ES 发布器已创建: {}（hls={} mp4={}）", key, enableHls, enableMp4);
    }

    public String key() {
        return key;
    }

    public String app() {
        return app;
    }

    public String stream() {
        return stream;
    }

    /** 首帧是否已成功送入 ZLM（= 播放地址真正可用）。 */
    public boolean hasFirstFrame() {
        return firstFrameAt > 0;
    }

    /** 最近一次推送帧的时间（毫秒），用于识别「设备断线但流还挂着」。 */
    public long lastFrameAt() {
        return lastFrameAt;
    }

    /** 已推送帧数（诊断用）。 */
    public long frames() {
        return frames;
    }

    public int codecName() {
        return codec;
    }

    /**
     * 推送一帧裸流。
     *
     * <p>运行在 SDK 原生回调线程上，必须快速返回。
     *
     * @param es 一帧或多帧的 Annex-B 裸流（含 {@code 00 00 00 01} / {@code 00 00 01} 起始码）
     */
    public void push(byte[] es) {
        if (closed || es == null || es.length < 5) {
            return;
        }
        try {
            if (!trackReady) {
                synchronized (trackLock) {
                    if (!trackReady) {
                        if (codec == CODEC_UNKNOWN) {
                            int guess = detectCodec(es);
                            if (guess != CODEC_UNKNOWN) {
                                codec = guess;
                            } else if (frames > 50) {
                                // 连续多块都判不出：按 H264 兜底，避免一直不出图
                                codec = CODEC_H264;
                                log.warn("编码无法自动识别，按 H264 兜底: {}", key);
                            } else {
                                // 首块可能是分片中间段，等下一块
                                frames++;
                                return;
                            }
                        }
                        // width/height/fps/bitrate 传 0 = 不转码，直接转发
                        if (api.mk_media_init_video(media, codec, 0, 0, 0f, 0) != 1) {
                            log.error("ZLM 视频轨道初始化失败: {} codec={}", key, codec);
                            return;
                        }
                        // 单轨流：显式告知，否则 ZLM 会多等 3 秒确认第二路轨道
                        api.mk_media_init_complete(media);
                        trackReady = true;
                        log.info("ZLM 视频轨道已就绪: {} codec={}", key,
                                codec == CODEC_H264 ? "H264" : "H265");
                    }
                }
            }
            int c = codec;
            long ts = nextTimestamp();
            Memory buf = new Memory(es.length);
            buf.write(0, es, 0, es.length);
            int ret = c == CODEC_H264
                    ? api.mk_media_input_h264(media, buf, es.length, ts, ts)
                    : api.mk_media_input_h265(media, buf, es.length, ts, ts);
            if (ret != 1) {
                // 失败通常是 ZLM 侧流已被回收；不刷屏，只记前几次
                if (frames < 5) {
                    log.warn("ZLM 输入视频帧失败: {} ret={}", key, ret);
                }
                return;
            }
            frames++;
            lastFrameAt = System.currentTimeMillis();
            if (firstFrameAt == 0) {
                firstFrameAt = lastFrameAt;
                log.info("ZLM 首帧已推送: {} {} 字节 codec={}", key, es.length,
                        c == CODEC_H264 ? "H264" : "H265");
            }
        } catch (Throwable t) {
            log.warn("推送帧异常: {} {}", key, t.toString());
        }
    }

    /**
     * 推算下一帧时间戳（毫秒，从 0 单调递增）。
     *
     * <p>用本地单调时钟算真实间隔，而不是 SDK 给的 {@code param} ——
     * 实测那个值是常量。间隔异常（首帧、卡顿超过 1 秒）按 40ms 估算，
     * 避免时间戳跳变导致播放器时间轴错乱。
     */
    private long nextTimestamp() {
        long now = System.nanoTime() / 1_000_000L;
        long last = lastFrameNano;
        lastFrameNano = now;
        if (last == 0) {
            return 0;
        }
        long delta = now - last;
        if (delta <= 0 || delta > 1000) {
            delta = FALLBACK_DELTA_MS;
        }
        outTs += delta;
        return outTs;
    }

    /** 从 NAL 单元判断编码：VPS/SPS/PPS 最具决定性。 */
    private int detectCodec(byte[] data) {
        int scan = Math.min(data.length, 4096);
        for (int i = 0; i + 5 < scan; i++) {
            int nalStart = nalStartAt(data, i);
            if (nalStart < 0) {
                continue;
            }
            int b = data[nalStart] & 0xFF;
            if ((b & 0x80) != 0) {
                // 首字节最高位为 1 说明不是 NAL 头（错位或私有数据），跳过
                continue;
            }
            int h265Type = (b >> 1) & 0x3F;
            if (h265Type == 32 || h265Type == 33 || h265Type == 34) {
                return CODEC_H265;   // VPS / SPS / PPS
            }
            int h264Type = b & 0x1F;
            if (h264Type == 7 || h264Type == 8) {
                return CODEC_H264;   // SPS / PPS
            }
        }
        return CODEC_UNKNOWN;
    }

    /** 若 {@code i} 处是 Annex-B 起始码，返回 NAL 头下标，否则 -1。 */
    private static int nalStartAt(byte[] d, int i) {
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
            return i + 4;
        }
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            return i + 3;
        }
        return -1;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            api.mk_media_release(media);
        } catch (Throwable t) {
            log.debug("释放 ZLM 媒体源异常: {} {}", key, t.toString());
        }
        log.info("ES 发布器已关闭: {} 累计 {} 帧", key, frames);
    }
}
