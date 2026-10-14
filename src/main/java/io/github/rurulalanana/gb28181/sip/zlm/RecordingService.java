package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_PROXY_PLAYER;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台侧录像服务：用 ZLMediaKit 把实时流录成 MP4，并维护录像索引。
 *
 * <p>与设备侧录像（{@link RecordItem} + 回放 INVITE）是两回事：
 * 本服务录的是「平台自己收到的实时流」，与设备存储无关。
 *
 * <p>典型用法：
 * <pre>{@code
 * recordingService.startRecording("34020000001320000001");   // 或设 autoRecord 由流上下线自动开关
 * List<Recording> list = recordingService.search("34020000001320000001", from, to);
 * String url = recordingService.playUrl(recording);            // 拉入 ZLM 后转协议播放
 * }</pre>
 */
public class RecordingService implements ZlmMediaServer.StreamListener {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    /** ZLM 录制类型：MP4/fMP4。 */
    private static final int RECORDER_TYPE_MP4 = 1;

    private final ZlmMediaServer mediaServer;
    private final MediaServerProperties props;
    private final Map<String, Recording> index = new ConcurrentHashMap<>();
    private final Map<String, String> activeByChannel = new ConcurrentHashMap<>();
    private final Map<String, MK_PROXY_PLAYER> players = new ConcurrentHashMap<>();
    private final SimpleDateFormat fileStamp = new SimpleDateFormat("yyyyMMdd_HHmmss");

    public RecordingService(ZlmMediaServer mediaServer, MediaServerProperties props) {
        this.mediaServer = mediaServer;
        this.props = props;
    }

    /** 是否随流上下线自动开关录像。 */
    private boolean autoRecord;

    public void setAutoRecord(boolean autoRecord) {
        this.autoRecord = autoRecord;
    }

    // ==================== 录像控制 ====================

    /**
     * 开始录制某通道（流不存在时 ZLM 会挂起任务，流上线后自动开始写文件）。
     *
     * @return 录像条目；已在录制时返回既有条目
     */
    public Recording startRecording(String channelId) {
        String existingId = activeByChannel.get(channelId);
        if (existingId != null) {
            Recording existing = index.get(existingId);
            if (existing != null) {
                return existing;
            }
        }
        File dir = new File(props.getRecordRoot(), channelId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("创建录像目录失败: " + dir);
        }
        String stamp = fileStamp.format(new Date());
        String recordId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String fileName = channelId + "_" + stamp + ".mp4";
        File file = new File(dir, fileName);

        ZLMApi api = ZlmMediaServer.api();
        int code = api.mk_recorder_start(RECORDER_TYPE_MP4, props.getVhost(), props.getApp(),
                channelId, file.getAbsolutePath(), props.getRecordMaxSecond());
        if (code != 0) {
            throw new IllegalStateException("ZLM 启动录制失败，返回码 " + code + "（通道 " + channelId + "）");
        }
        Recording recording = new Recording(recordId, channelId, props.getApp(), channelId,
                file.getAbsolutePath(), fileUrl(file.getName()), System.currentTimeMillis());
        index.put(recordId, recording);
        activeByChannel.put(channelId, recordId);
        log.info("开始录像: channel={} file={}", channelId, file.getAbsolutePath());
        return recording;
    }

    /** 停止录制某通道并归档索引。 */
    public Recording stopRecording(String channelId) {
        String recordId = activeByChannel.remove(channelId);
        if (recordId == null) {
            return null;
        }
        ZLMApi api = ZlmMediaServer.api();
        api.mk_recorder_stop(RECORDER_TYPE_MP4, props.getVhost(), props.getApp(), channelId);
        Recording recording = index.get(recordId);
        if (recording != null) {
            recording.close(System.currentTimeMillis());
            log.info("停止录像: channel={} 时长={}s", channelId, recording.durationSeconds());
        }
        return recording;
    }

    public boolean isRecording(String channelId) {
        return activeByChannel.containsKey(channelId);
    }

    /** 全部录像（按开始时间倒序）。 */
    public List<Recording> all() {
        List<Recording> list = new ArrayList<>(index.values());
        list.sort(Comparator.comparingLong(Recording::getStartTime).reversed());
        return list;
    }

    /** 某通道录像列表。 */
    public List<Recording> recordings(String channelId) {
        List<Recording> list = new ArrayList<>();
        for (Recording r : index.values()) {
            if (r.getChannelId().equals(channelId)) {
                list.add(r);
            }
        }
        list.sort(Comparator.comparingLong(Recording::getStartTime).reversed());
        return list;
    }

    /** 按时间段检索（闭区间，允许任一端为 {@code null} 表示不限）。 */
    public List<Recording> search(String channelId, Long fromTime, Long toTime) {
        List<Recording> result = new ArrayList<>();
        for (Recording r : index.values()) {
            if (channelId != null && !channelId.equals(r.getChannelId())) {
                continue;
            }
            long end = r.getEndTime() > 0 ? r.getEndTime() : System.currentTimeMillis();
            if (fromTime != null && end < fromTime) {
                continue;
            }
            if (toTime != null && r.getStartTime() > toTime) {
                continue;
            }
            result.add(r);
        }
        result.sort(Comparator.comparingLong(Recording::getStartTime).reversed());
        return result;
    }

    public Recording get(String recordId) {
        return index.get(recordId);
    }

    // ==================== 录像点播 ====================

    /**
     * 把录像文件拉入 ZLM 并转协议播放（HLS/FLV/WebRTC）。
     *
     * @return 点播流的播放地址；失败返回 {@code null}
     */
    public PlayUrls playUrl(Recording recording) {
        if (recording == null) {
            return null;
        }
        File file = new File(recording.getFilePath());
        if (!file.exists()) {
            log.warn("录像文件不存在，无法点播: {}", recording.getFilePath());
            return null;
        }
        String streamId = "record_" + recording.getRecordId();
        // (url, app, stream, rtpType, hls)：把本地 MP4 拉入 ZLM 并按需转协议
        MK_PROXY_PLAYER player = ZlmMediaServer.api().mk_proxy_player_create(
                file.getAbsolutePath(), props.getApp(), streamId, 0, 1);
        if (player == null) {
            log.warn("创建录像点播代理失败: {}", recording.getFilePath());
            return null;
        }
        ZlmMediaServer.api().mk_proxy_player_play(player, props.getApp() + "/" + streamId);
        players.put(streamId, player);
        log.info("录像点播已启动: {} -> {}/{}", recording.getFilePath(), props.getApp(), streamId);
        return mediaServer.playUrls(props.getApp(), streamId);
    }

    /** 停止录像点播。 */
    public void stopPlay(String recordId) {
        String streamId = "record_" + recordId;
        MK_PROXY_PLAYER player = players.remove(streamId);
        if (player != null) {
            ZlmMediaServer.api().mk_proxy_player_release(player);
        }
    }

    // ==================== 流事件（自动录像）====================

    @Override
    public void onStreamOnline(String app, String stream) {
        if (autoRecord && props.getApp().equals(app) && !isRecording(stream)) {
            try {
                startRecording(stream);
            } catch (Exception e) {
                log.warn("自动开始录像失败: {}", stream, e);
            }
        }
    }

    @Override
    public void onStreamOffline(String app, String stream) {
        if (isRecording(stream)) {
            stopRecording(stream);
        }
    }

    private String fileUrl(String fileName) {
        return "http://" + props.getMediaIp() + ":" + mediaServer.getHttpPort()
                + "/" + props.getRecordApp() + "/" + fileName;
    }
}