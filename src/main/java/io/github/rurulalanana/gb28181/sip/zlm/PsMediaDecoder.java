package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.callback.IMKPsDecoderFrameCallBack;
import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_PS_DECODER;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 PS 封装码流解成裸 H264/H265 ES，交给 {@link EsMediaPublisher} 建流。
 *
 * <h3>为什么需要它</h3>
 * 海康 ISUP 的设备把码流以 <b>PS</b>（Program Stream）推给平台，
 * 而 ZLM 的 {@code mk_media_input_*} 只接受 Annex-B 裸流。
 * ZLM 自带 PS 解码器（{@code mk_ps_decoder_*}），比平台自己写 PS demux 可靠得多 ——
 * PS 里的 PES 分包、时间戳、私有流处理都有大量边界情况。
 *
 * <h3>⚠️ 时间戳单位是 90 kHz，必须除以 90</h3>
 * PS 解码器直接抛出 demuxer 的原始时间戳，单位是 <b>90 kHz</b>（MPEG 系统时钟），
 * 而 {@code mk_media_input_*} 要<b>毫秒</b>。不转换会让时间戳放大 90 倍，
 * 播放器表现为「瞬间播完」或「一直缓冲」。ZLM 自己的 {@code DecoderImp} 里就是 {@code pts /= 90}。
 *
 * <h3>⚠️ 回调参数顺序与下游相反</h3>
 * C 侧签名是 {@code (user_data, stream, codecid, flags, pts, dts, data, bytes)} ——
 * <b>pts 在 dts 之前</b>；而 {@code mk_media_input_h264} 的入参是 <b>dts 在前</b>。
 * 两处顺序相反，写反了画面会卡顿/回跳。
 */
public final class PsMediaDecoder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PsMediaDecoder.class);

    /** PS demuxer 时间戳是 90 kHz，除以它得到毫秒。 */
    private static final int CLOCK_HZ = 90;

    /** 编码：H264（与 {@code mk_media_init_video} 的 codec_id 一致）。 */
    public static final int CODEC_H264 = 0;
    /** 编码：H265。 */
    public static final int CODEC_H265 = 1;

    /** 裸流帧的接收方。 */
    public interface FrameSink {
        /**
         * 收到一帧裸流。
         *
         * @param es      Annex-B 裸流（含起始码）
         * @param codec   编码：0-H264，1-H265；其它值表示音频或未知
         * @param isVideo {@code true} 视频帧，{@code false} 音频帧
         * @param dts     解码时间戳（毫秒）
         * @param pts     显示时间戳（毫秒）
         */
        void onFrame(byte[] es, int codec, boolean isVideo, long dts, long pts);
    }

    private final String key;

    private final ZLMApi api;

    private final FrameSink sink;

    private final MK_PS_DECODER decoder;

    /** 帧回调必须强引用住：JNA 只持弱引用，被 GC 掉后 ZLM 回调会踩空指针直接崩 JVM。 */
    private final IMKPsDecoderFrameCallBack frameCallback;

    private volatile boolean closed;

    /** 已解出的帧数（诊断用）。 */
    private volatile long frames;

    /** 已送入的字节数（诊断用）。 */
    private volatile long bytes;

    /** 首个时间戳，用于把绝对时间归零（播放器从 0 起算更友好）。 */
    private volatile long basePts = -1;

    public PsMediaDecoder(ZLMApi api, String app, String stream, FrameSink sink) {
        this.api = api;
        this.key = app + "/" + stream;
        this.sink = sink;
        this.frameCallback = this::handleFrame;
        // 流回调传 null：平台不需要「PS 流分段」通知，只要帧
        this.decoder = api.mk_ps_decoder_create(null, frameCallback, null);
        if (this.decoder == null) {
            throw new IllegalStateException("创建 ZLM PS 解码器失败: " + key);
        }
        log.info("PS 解码器已创建: {}", key);
    }

    public String key() {
        return key;
    }

    /** 已解出的帧数。 */
    public long frames() {
        return frames;
    }

    /** 已送入的字节数。 */
    public long bytes() {
        return bytes;
    }

    /**
     * 送入一段 PS 数据。
     *
     * <p>运行在 SDK 回调线程上：只做一次内存拷贝与一次原生调用。
     *
     * @param ps     PS 数据（可以是一段或多段）
     * @param length 有效长度
     */
    public void input(byte[] ps, int length) {
        if (closed || ps == null || length <= 0) {
            return;
        }
        try {
            Memory buf = new Memory(length);
            buf.write(0, ps, 0, length);
            api.mk_ps_decoder_input(decoder, buf, length);
            bytes += length;
        } catch (Throwable t) {
            // 解码器可能已被 ZLM 回收，不刷屏
            if (frames < 5) {
                log.warn("PS 解码输入失败: {} {}", key, t.getMessage());
            }
        }
    }

    /**
     * 帧回调（ZLM 内部线程）。
     *
     * <p>参数顺序严格对应 C 侧
     * {@code (user_data, stream, codecid, flags, pts, dts, data, bytes)}。
     */
    private void handleFrame(Pointer userData, int stream, int codec, int flags,
                             long ptsRaw, long dtsRaw, Pointer pData, long dataLen) {
        if (closed || pData == null || dataLen <= 0) {
            return;
        }
        try {
            // 90 kHz → 毫秒
            long pts = ptsRaw / CLOCK_HZ;
            long dts = dtsRaw / CLOCK_HZ;
            // 首帧时间戳归零：PS 里的绝对时间戳可能很大，播放器更适应从 0 起算
            long base = basePts;
            if (base < 0) {
                basePts = base = pts;
            }
            pts -= base;
            dts -= base;
            if (pts < 0) {
                pts = dts < 0 ? 0 : dts;
            }
            if (dts < 0) {
                dts = 0;
            }

            byte[] es = pData.getByteArray(0, (int) dataLen);
            boolean video = codec == CODEC_H264 || codec == CODEC_H265;
            frames++;
            if (frames == 1) {
                log.info("PS 解码首帧: {} {} 字节 codec={} video={} pts={} dts={}",
                        key, dataLen, codec, video, pts, dts);
            }
            sink.onFrame(es, codec, video, dts, pts);
        } catch (Throwable t) {
            log.debug("PS 帧回调异常: {} {}", key, t.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (decoder != null) {
                api.mk_ps_decoder_release(decoder);
            }
        } catch (Throwable t) {
            log.debug("释放 PS 解码器失败: {} {}", key, t.getMessage());
        }
        log.info("PS 解码器已释放: {}（共 {} 帧 / {} 字节）", key, frames, bytes);
    }
}
