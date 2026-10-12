package io.github.rurulalanana.gb28181.sip.zlm;

/**
 * 收流会话类型（对应 SDP {@code s=} 字段）。
 *
 * <p>三者共用同一套 RTP 收流机制（设备把 PS 流推到平台独占端口），
 * 差别在于 {@code s=} / {@code u=} / SSRC 的历史位标记。
 */
public enum MediaType {

    /** 实时点播（标准 9.2）。 */
    REALTIME("Play", false),

    /** 历史回放（标准 9.8）：{@code u=} 携带录像文件标识，SSRC 取历史位。 */
    PLAYBACK("Playback", true),

    /** 文件下载（标准 9.9）：{@code u=} 携带文件路径，收流后落盘。 */
    DOWNLOAD("Download", true);

    private final String sdpSessionName;
    /** 是否使用 SSRC 的历史位标记（GB/T 28181 附录 G）。 */
    private final boolean historical;

    MediaType(String sdpSessionName, boolean historical) {
        this.sdpSessionName = sdpSessionName;
        this.historical = historical;
    }

    public String sdpSessionName() {
        return sdpSessionName;
    }

    public boolean historical() {
        return historical;
    }

    /** 按 SDP {@code s=} 字段反查；未知返回 {@link #REALTIME}。 */
    public static MediaType ofSessionName(String s) {
        if (s == null) {
            return REALTIME;
        }
        for (MediaType type : values()) {
            if (type.sdpSessionName.equalsIgnoreCase(s.trim())) {
                return type;
            }
        }
        return REALTIME;
    }
}