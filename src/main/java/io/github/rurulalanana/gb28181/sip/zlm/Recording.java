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

    /** 录像开始时间（epoch ms）。 */
    private final long startTime;

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
     * 绑定 ZLM 回调给出的真实录像文件。
     *
     * <p>只允许绑定一次：ZLM 超过 {@code max_second} 会自动分段，
     * 后续分片不应覆盖首个分片作为本次录像的代表文件。
     */
    void bindFile(String path, String fileName, long size) {
        if (this.filePath != null) {
            return;
        }
        this.filePath = path;
        this.fileSize = size;
        this.fileUrl = "record/" + fileName;
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