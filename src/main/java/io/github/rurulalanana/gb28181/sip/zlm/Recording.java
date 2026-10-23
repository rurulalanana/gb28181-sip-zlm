package io.github.rurulalanana.gb28181.sip.zlm;

import lombok.Getter;
import lombok.ToString;

/**
 * 一段平台侧录像（ZLMediaKit 录制产物）。
 */
@Getter
@ToString
public class Recording {

    /** 录像 ID（同时作为 ZLM 点播流名的一部分）。 */
    private final String recordId;

    /** 通道编码。 */
    private final String channelId;

    /** ZLM 应用名（源流应用）。 */
    private final String app;

    /** ZLM 流名。 */
    private final String stream;

    /**
     * 录像文件绝对路径。
     *
     * <p>开始录制时为 {@code null}：ZLM 自行决定文件名与日期子目录，
     * 需等首个分片落盘、{@code on_mk_record_mp4} 回调到达后才能确定。
     */
    private volatile String filePath;

    /** 录像文件 URL（ZLM HTTP 服务直链，可直接下载/播放）。 */
    private volatile String fileUrl;

    /** 文件大小（字节）；未落盘为 0。 */
    private volatile long fileSize;

    /**
     * 录像开始时间（epoch ms）。
     *
     * <p><b>会随分片轮转而前移</b>：本条目代表「<b>最近完成的那一个分片</b>」，
     * 而不是整个录制会话。见 {@link #bindSegment}。
     */
    private volatile long startTime;

    /** 录像结束时间（epoch ms）；进行中为 0。 */
    private volatile long endTime;

    /** 状态：{@link #RECORDING} / {@link #CLOSED}。 */
    private volatile String status;

    public Recording(String recordId, String channelId, String app, String stream,
                     String filePath, String fileUrl, long startTime) {
        this.recordId = recordId;
        this.channelId = channelId;
        this.app = app;
        this.stream = stream;
        this.filePath = filePath;
        this.fileUrl = fileUrl;
        this.startTime = startTime;
        this.status = RECORDING;
    }

    /** 录像中。 */
    public static final String RECORDING = "RECORDING";
    /** 已结束（文件可回放）。 */
    public static final String CLOSED = "CLOSED";

    /**
     * 绑定一个<b>刚完成的分片</b>，并把本条目重新指向它。
     *
     * <p><b>为什么每次都要重绑而不是只绑首段</b>：{@code max_second} 让 ZLM
     * 持续分段（当前 1800 秒一片）。若只绑首段，本条目就会变成一条
     * 「幽灵记录」：{@code startTime} 还是整场会话的开始，所以时长一直涨
     * （实测 11:54:48 开始、显示 5791 秒），{@code filePath} 却指向首段
     * （170 MB / 30 分钟）——
     * 界面上显示成「5791 秒 · 170 MB · 录制中」，实际打开只能播前 30 分钟，
     * 而且与库里已登记的各分片记录重复。
     *
     * <p>现在语义统一为：<b>一条 {@link Recording} = 最近完成的一个分片</b>。
     * 已完成的历史分片由库里各自独立成条（归档/对账负责），
     * 本条目表达「当前会话最后一段落盘的是这段」。
     *
     * <p><b>结束时间用「分片开始 + 时长」算</b>，不用 {@code now}：
     * 分片是<b>已经写完</b>的，用 now 会把时长算成「从该分片开始到现在」，
     * 越滚越大（这正是 5791 秒那条记录的成因）。
     *
     * @param path           分片文件绝对路径
     * @param fileName       文件名（用于拼 fileUrl）
     * @param size           分片大小（字节）
     * @param segmentStartMs 该分片的<b>开始</b>时间（epoch ms），
     *                       由文件名解析而来，比用「上一段结束 + 时长」累加更准
     * @param timeLenSeconds 该分片的实际时长（秒），来自 ZLM 回调
     */
    void bindSegment(String path, String fileName, long size,
                     long segmentStartMs, float timeLenSeconds) {
        this.filePath = path;
        this.fileSize = size;
        this.fileUrl = fileName == null ? null : "record/" + fileName;
        if (segmentStartMs > 0) {
            this.startTime = segmentStartMs;
        }
        // 分片已写完：结束时间 = 开始 + 时长（时长缺失时退回「现在」）
        long len = timeLenSeconds > 0 ? (long) (timeLenSeconds * 1000) : 0L;
        this.endTime = len > 0 ? this.startTime + len : System.currentTimeMillis();
        this.status = CLOSED;
    }

    /**
     * 停止录制前调用：清掉「上一段」的绑定，等 ZLM 关闭正在写的文件后
     * 由回调带上<b>最后这一个分片</b>的路径。
     *
     * <p>不清空的话，{@code stopRecording} 的等待循环看到 {@code filePath}
     * 已非空就立刻返回，归档下来的会是<b>上一次</b>的分片而不是刚停的这段。
     */
    void awaitFinalSegment() {
        this.filePath = null;
        this.fileUrl = null;
        this.fileSize = 0L;
    }

    /** 用磁盘上的实际文件大小刷新（录制结束后文件已定稿）。 */
    void refreshSize(long size) {
        if (size > 0) {
            this.fileSize = size;
        }
    }

    void close(long end) {
        this.endTime = end <= 0 ? System.currentTimeMillis() : end;
        this.status = CLOSED;
    }

    /** 录像时长（秒）。 */
    public long durationSeconds() {
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        return Math.max(0, (end - startTime) / 1000);
    }
}