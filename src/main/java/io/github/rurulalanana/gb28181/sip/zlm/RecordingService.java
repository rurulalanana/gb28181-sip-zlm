package io.github.rurulalanana.gb28181.sip.zlm;

import com.aizuda.zlm4j.core.ZLMApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
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
public class RecordingService implements ZlmMediaServer.StreamListener,
        ZlmMediaServer.RecordListener {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    /** ZLM 录制类型：MP4/fMP4。 */
    private static final int RECORDER_TYPE_MP4 = 1;

    /**
     * 停止录制后等待最后分片落盘的超时（毫秒）。
     *
     * <p>ZLM 的 MP4 关闭是异步的：要 flush 缓冲、把临时文件改名为正式文件，
     * 再广播 {@code on_mk_record_mp4}。大文件（数百 MB）耗时可达数秒。
     */
    private static final long FINALIZE_TIMEOUT_MS = 10_000;

    /** 等待最后分片落盘的轮询间隔（毫秒）。 */
    private static final long FINALIZE_POLL_MS = 100;

    /**
     * 停机时每路录像的收尾等待上限（毫秒）。
     *
     * <p><b>为什么比 {@link #FINALIZE_TIMEOUT_MS} 短得多</b>：停机时
     * SIP 已经先停了，设备随即停止推流，ZLM 会<b>抢先自动停录</b>并把文件落盘 ——
     * 等我们再调 {@code mk_recorder_stop} 时，recorder 早就没了，
     * {@code on_mk_record_mp4} 回调永远不会来。
     *
     * <p>实测停机时等满 10 秒仍然 {@code file=null size=0B}（文件其实已在磁盘上、
     * 大小正确、可正常播放）。这 10 秒纯粹是白等，而它直接拖慢每次重启。
     *
     * <p>现在只等 3 秒：正常情况（流还活着）够 ZLM 收尾；
     * 拿不到路径也没关系 —— 归档已改为「索引条目没有 filePath 就按磁盘文件补登记」
     * （见 {@code RecordService#archiveRecording}），下次启动的对账也会兜底。
     */
    private static final long SHUTDOWN_FINALIZE_MS = 3_000;

    private final ZlmMediaServer mediaServer;
    private final MediaServerProperties props;
    private final RtpSessionManager sessionManager;
    private final Map<String, Recording> index = new ConcurrentHashMap<>();
    private final Map<String, String> activeByChannel = new ConcurrentHashMap<>();

    /** 正在点播的录像流名（{@code record_<id>}），用于停止点播时关流。 */
    private final java.util.Set<String> loadedStreams = ConcurrentHashMap.newKeySet();

    /**
     * 正在录制的条目，按 ZLM 坐标 {@code app/stream} 索引。
     *
     * <p>录制完成回调只带 {@code app/stream}，不带业务侧的通道编码，
     * 需经此表反查，才能把真实文件路径回填到对应的 {@link Recording}。
     */
    private final Map<String, Recording> activeBySource = new ConcurrentHashMap<>();

    public RecordingService(ZlmMediaServer mediaServer, MediaServerProperties props) {
        this(mediaServer, props, null);
    }

    /**
     * 完整构造。
     *
     * @param sessionManager 会话管理器；用于把「通道编码」解析成 ZLM 实际
     *                       {@code app/stream}（单端口模式下二者不同，见
     *                       {@link #resolveZlmCoordinates}）。
     *                       传 {@code null} 时退化为旧行为（仅适用于独占端口模式）
     */
    public RecordingService(ZlmMediaServer mediaServer, MediaServerProperties props,
                            RtpSessionManager sessionManager) {
        this.mediaServer = mediaServer;
        this.props = props;
        this.sessionManager = sessionManager;
    }

    /**
     * 把「通道编码」解析为 ZLM 媒体源的 {@code app/stream}。
     *
     * <p><b>为什么必须解析</b>：{@code mk_recorder_start} 要求传入与已注册媒体源
     * 完全一致的 {@code app/stream}，否则回调报
     * {@code 未找到相关的MediaSource,startRecord失败}。
     * 而本平台两种收流模式下的命名规则不同：
     *
     * <ul>
     *   <li><b>独占端口</b>：{@code app = 设备编码}、{@code stream = 通道编码}；</li>
     *   <li><b>单端口</b>：{@code app = rtp}（固定）、{@code stream = <设备编码>_<通道编码>}。</li>
     * </ul>
     *
     * <p>调用方通常只有通道编码，单端口下无法自行拼出流名，因此必须反查会话。
     * 拿不到会话时（流未建立）退化为「独占端口」的命名，此时录制本也会失败，
     * 但至少错误信息与真实情况一致。
     *
     * @param channelId 通道编码
     * @return {@code [app, stream]}；无法解析时返回 {@code null}（调用方应报错）
     */
    private String[] resolveZlmCoordinates(String channelId) {
        if (sessionManager != null) {
            RtpSession session = sessionManager.getByChannel(channelId);
            if (session != null) {
                return new String[]{session.getZlmApp(), session.getStream()};
            }
        }
        // 退化：独占端口模式下 app=设备编码、stream=通道编码；
        // 单端口模式下这里拼不出正确流名，但会话缺失本身就意味着流不存在
        if (mediaServer != null && mediaServer.isSinglePortMode()) {
            log.warn("单端口模式但未找到会话，无法解析 ZLM 流名: channel={}", channelId);
            return null;
        }
        return new String[]{props.getApp(), channelId};
    }

    /** 是否随流上下线自动开关录像。 */
    private boolean autoRecord;

    /**
     * 运行时覆盖的单片时长（秒）；{@code <=0} 表示用 {@code props} 里的值。
     *
     * <p>用于支持「界面上改分片时长立即生效」。它<b>不影响正在录制的分片</b>：
     * ZLM 在 {@code mk_recorder_start} 时就把 max_second 定下了，
     * 因此必须在下一次开始录像时才读取本值。
     */
    private volatile int recordMaxSecondOverride;

    /**
     * 设置单片刻录时长（秒，{@code <=0} 表示恢复用配置值）。
     *
     * <p>线程安全：{@code volatile} 写，读取发生在
     * {@link #startRecording} 里，不要求与写入原子。
     */
    public void setRecordMaxSecond(int seconds) {
        this.recordMaxSecondOverride = seconds;
    }

    /** 当前生效的单片时长（秒）：运行时覆盖优先。 */
    private int recordMaxSecond() {
        int override = recordMaxSecondOverride;
        return override > 0 ? override : props.getRecordMaxSecond();
    }

    public void setAutoRecord(boolean autoRecord) {
        this.autoRecord = autoRecord;
    }

    // ==================== 录像控制 ====================

    /**
     * 开始录制某通道。
     *
     * <p><b>要求流已建立</b>：ZLM 的 {@code mk_recorder_start} 按
     * {@code app/stream} 查找媒体源，源不存在时不会挂起等待，而是直接失败
     * （{@code 未找到相关的MediaSource}）。因此调用方应先点播、等流上线再录制。
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
        String[] coords = resolveZlmCoordinates(channelId);
        if (coords == null) {
            throw new IllegalStateException("流未建立，无法开始录像（通道 " + channelId + "）"
                    + "：请先点播该通道，待流上线后再录制");
        }
        String app = coords[0];
        String stream = coords[1];
        File dir = new File(props.getRecordRoot(), channelId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("创建录像目录失败: " + dir);
        }
        String recordId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 第 5 个参数是<b>目录</b>而非文件路径（zlm4j 注释「录像文件保存自定义目录」）。
        // 传文件名会被 ZLM 当成目录建，真实文件埋在
        // <我们传的路径>/<recordApp>/<app>/<stream>/<date>/<date>-<HH-MM-SS>-<idx>.mp4，
        // 于是索引里的路径永远不存在（fileSize 恒为 0、点播/下载全失败）。
        // 正确做法：只给目录，文件名与子目录交给 ZLM，产物路径由
        // on_mk_record_mp4 回调回填（见 {@link #onRecordMp4}）。
        ZLMApi api = ZlmMediaServer.api();
        int maxSecond = recordMaxSecond();
        int code = api.mk_recorder_start(RECORDER_TYPE_MP4, props.getVhost(), app,
                stream, dir.getAbsolutePath(), maxSecond);
        // zlm4j 的 mk_recorder_start 返回「1=成功，0=失败」（与多数 C API 相反）。
        // 曾按 0=成功 判断，导致真录制成功时被当成失败抛异常、而媒体源不存在
        // 时反而被当成成功。
        if (code != 1) {
            throw new IllegalStateException("ZLM 启动录制失败，返回码 " + code
                    + "（通道 " + channelId + "，媒体源 " + app + "/" + stream + "）");
        }
        // 文件路径暂时留空，等回调回填（首段通常几秒内落盘）
        Recording recording = new Recording(recordId, channelId, app, stream,
                null, null, System.currentTimeMillis());
        index.put(recordId, recording);
        activeByChannel.put(channelId, recordId);
        activeBySource.put(sourceKey(app, stream), recording);
        log.info("开始录像: channel={} source={}/{} dir={}", channelId, app, stream,
                dir.getAbsolutePath());
        return recording;
    }

    /**
     * 停止录制某通道并归档索引。
     *
     * <p><b>会等待最后一个分片落盘</b>：ZLM 关闭 MP4 文件是异步的
     * （{@code MP4Recorder::asyncClose} 把 flush/rename 丢到工作线程），
     * 完成后才广播 {@code on_mk_record_mp4}。若不等就直接摘除条目，
     * 回调到达时已查不到对应录像，真实路径与大小永远回填不上
     * （表现为 filePath=null、fileSize=0、点播与下载全失败）。
     */
    public Recording stopRecording(String channelId) {
        return stopRecording(channelId, FINALIZE_TIMEOUT_MS);
    }

    /** 停止录制，可选择是否等待最后一段落盘（{@code false} = 立即返回）。 */
    public Recording stopRecording(String channelId, boolean waitFinalize) {
        return stopRecording(channelId, waitFinalize ? FINALIZE_TIMEOUT_MS : 0L);
    }

    /**
     * 停止录制，显式指定等待最后一段落盘的上限（毫秒）。
     *
     * <p>{@code 0} 表示完全不等待。见 {@link #SHUTDOWN_FINALIZE_MS} 的说明：
     * 停机场景用 3 秒而不是 10 秒。
     *
     * <p><b>为什么需要这个开关</b>：{@link #onStreamOffline} 由 ZLM 的流下线事件
     * 驱动，跑在 {@code zlm-event-worker} 单线程执行器上。若在那里等满
     * {@link #FINALIZE_TIMEOUT_MS}（10 秒），同一条队列上其它通道的上下线事件
     * 会被一起拖住 —— 4 路设备同时掉线就是 40 秒的状态停滞，
     * 期间保活/归档都做不了。
     *
     * <p>不等也不会丢数据：ZLM 的 {@code on_mk_record_mp4} 回调（跑在它自己的
     * poller 线程上，不会被本方法阻塞）随后会把真实路径回填到同一条
     * {@link Recording} 上；即使这次没回填成功，磁盘上的正式文件也会被
     * {@code RecordService#reconcileAutoRecordings} 周期性补登记。
     */
    public Recording stopRecording(String channelId, long finalizeTimeoutMs) {
        String recordId = activeByChannel.remove(channelId);
        if (recordId == null) {
            return null;
        }
        Recording recording = index.get(recordId);
        // 用录像条目里记下的 app/stream 停止：不能再解析一次，
        // 此时流可能已下线、会话已摘除，重新解析会拿到不同的值而停不掉
        String app = recording != null ? recording.getApp() : props.getApp();
        String stream = recording != null ? recording.getStream() : channelId;
        ZLMApi api = ZlmMediaServer.api();
        api.mk_recorder_stop(RECORDER_TYPE_MP4, props.getVhost(), app, stream);

        if (recording == null) {
            activeBySource.remove(sourceKey(app, stream));
            return null;
        }

        // 等回调把最后一段的文件路径回填上来（录制过短、从未收到帧时不会回调，走超时）
        if (finalizeTimeoutMs > 0) {
            long deadline = System.currentTimeMillis() + finalizeTimeoutMs;
            while (recording.getFilePath() == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(FINALIZE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 回调已到达（或超时）：此时再摘除索引，避免与回调竞争
        activeBySource.remove(sourceKey(app, stream));

        if (recording.getFilePath() == null && finalizeTimeoutMs > 0) {
            log.warn("停止录像但未产生文件（录制期间未收到媒体帧？）: channel={} source={}/{}",
                    channelId, app, stream);
        } else {
            // 停止后文件已定稿：用磁盘实际大小刷新（回调报的是分段落盘时的大小）
            File f = new File(recording.getFilePath());
            if (f.exists()) {
                recording.refreshSize(f.length());
            }
        }
        recording.close(System.currentTimeMillis());
        log.info("停止录像: channel={} source={}/{} 时长={}s file={} size={}B",
                channelId, app, stream, recording.durationSeconds(),
                recording.getFilePath(), recording.getFileSize());
        return recording;
    }

    /**
     * MP4 分片落盘回调：把 ZLM 生成的真实路径与大小回填到录像条目。
     *
     * <p>ZLM 按 {@code max_second} 自动分段，一段录像可能产生多个文件。
     * 本平台对外只暴露「一次录制 = 一个文件」的语义，因此只在首段落盘时
     * 绑定路径（即用户点开始到停止这段时长通常不超过 {@code recordMaxSecond}）。
     * 若确因超长而分段，后续分片会以独立文件留在同目录，可从磁盘目录取回。
     */
    @Override
    public void onRecordMp4(String app, String stream, String filePath,
                            String fileName, long fileSize, float timeLen) {
        Recording recording = activeBySource.get(sourceKey(app, stream));
        if (recording == null) {
            log.debug("录像分片落盘但无对应录制条目（已停止？）: {}/{} file={}",
                    app, stream, filePath);
            return;
        }
        if (recording.getFilePath() == null) {
            recording.bindFile(filePath, fileName, fileSize);
            log.info("录像分片已落盘: channel={} file={} size={}B 时长={}s",
                    recording.getChannelId(), filePath, fileSize, timeLen);
        } else {
            // 已绑定首段：后续分片只记录，不覆盖主路径
            log.info("录像分段落盘（附加分片）: channel={} file={} size={}B",
                    recording.getChannelId(), filePath, fileSize);
        }
    }

    /** ZLM 坐标索引键。 */
    private static String sourceKey(String app, String stream) {
        return app + "/" + stream;
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

    /**
     * 从内存索引移除一条录像（删除录像时调用）。
     *
     * <p><b>必须调用</b>：{@code RecordService.cloudRecords} 会把 ZLM 内存索引
     * 里的条目合并进列表（用于显示「录制中」的实时状态）。若删除时只删库与文件，
     * 索引里的陈旧条目会让录像在列表里「删不掉」——刷新后依然出现，
     * 且点播会因文件已删而失败。
     *
     * @return 是否移除了条目
     */
    public boolean forget(String recordId) {
        if (recordId == null) {
            return false;
        }
        Recording removed = index.remove(recordId);
        if (removed != null) {
            activeByChannel.remove(removed.getChannelId(), recordId);
            activeBySource.remove(sourceKey(removed.getApp(), removed.getStream()));
            log.info("已从内存索引移除录像: id={}", recordId);
            return true;
        }
        return false;
    }

    /**
     * 按外部元数据重新登记一段历史录像（用于进程重启后恢复点播能力）。
     *
     * <p>内存索引重启即空，但录像文件与数据库记录仍在。上层（如 Spring 平台）
     * 可从库中读出 {@code filePath/startTime} 后调用本方法把条目装回索引，
     * 之后 {@link #playUrl(Recording)} 即可正常点播。
     *
     * @param recordId  录像 ID（与库中一致）
     * @param channelId 通道编码
     * @param filePath  录像文件绝对路径
     * @param startTime 录像开始时间（epoch ms）
     * @return 登记后的录像条目；文件不存在时返回 {@code null}
     */
    public Recording register(String recordId, String channelId, String filePath, long startTime) {
        if (recordId == null || recordId.isEmpty() || filePath == null || filePath.isEmpty()) {
            return null;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("登记历史录像失败，文件不存在: {}", filePath);
            return null;
        }
        Recording existing = index.get(recordId);
        if (existing != null) {
            return existing;
        }
        Recording recording = new Recording(recordId, channelId, props.getApp(), channelId,
                file.getAbsolutePath(), fileUrl(file.getName()), startTime);
        recording.refreshSize(file.length());
        recording.close(file.lastModified());
        index.put(recordId, recording);
        log.info("已登记历史录像: id={} file={} size={}B", recordId, filePath, file.length());
        return recording;
    }

    // ==================== 录像点播 ====================

    /**
     * 把录像文件拉入 ZLM 并转协议播放（HLS/FLV/WebRTC）。
     *
     * <p><b>用 {@code mk_load_mp4_file} 而非 proxy player</b>：
     * 前者是 ZLM 专门为「本地 MP4 文件 → 媒体源」提供的接口，
     * 传 {@code (vhost, app, stream, file_path, file_repeat)} 即完成解复用并注册源。
     * proxy player 则是「拉外部流」用的，它的第 1 个参数是 vhost 不是文件路径，
     * 且 {@code mk_proxy_player_play} 要求带协议的完整 URL
     * （传 {@code app/stream} 会报 {@code not supported play schema}）。
     *
     * @return 点播流的播放地址；失败返回 {@code null}
     */
    public PlayUrls playUrl(Recording recording) {
        if (recording == null || recording.getFilePath() == null) {
            log.warn("录像尚未产生文件，无法点播: {}",
                    recording == null ? "null" : recording.getRecordId());
            return null;
        }
        File file = new File(recording.getFilePath());
        if (!file.exists()) {
            log.warn("录像文件不存在，无法点播: {}", recording.getFilePath());
            return null;
        }
        String streamId = "record_" + recording.getRecordId();
        // file_repeat=0：播完即停，不循环。点播结束后源由 stopPlay 主动关闭。
        ZlmMediaServer.api().mk_load_mp4_file(props.getVhost(), props.getApp(), streamId,
                file.getAbsolutePath(), 0);
        loadedStreams.add(streamId);
        log.info("录像点播已启动: {} -> {}/{}", recording.getFilePath(), props.getApp(), streamId);
        return mediaServer.playUrls(props.getApp(), streamId);
    }

    /** 停止录像点播。 */
    public void stopPlay(String recordId) {
        String streamId = "record_" + recordId;
        loadedStreams.remove(streamId);
        // 必须显式关流：MP4 源在无人观看时会自动关闭，但点播弹窗关闭后
        // 可能仍有残余实例占着，不关会让下一次同 recordId 点播报源已存在。
        mediaServer.closeMediaSource(props.getApp(), streamId);
    }

    // ==================== 流事件（自动录像）====================

    /**
     * 流上线：开启自动录像时顺带开录。
     *
     * <p><b>参数是 ZLM 坐标</b>：{@code stream} 在单端口模式下形如
     * {@code <设备编码>_<通道编码>}，与业务侧的「通道编码」不同。
     * 本类内部一律以通道编码为键（{@code activeByChannel} / {@code index}），
     * 因此这里要反查会话拿到通道编码，不能直接用 {@code stream}。
     */
    @Override
    public void onStreamOnline(String app, String stream) {
        if (!autoRecord) {
            return;
        }
        RtpSession session = sessionManager == null ? null : sessionManager.resolve(app, stream);
        if (session == null) {
            // 非本平台发起的流（如级联转发、代理拉流），不自动录像
            log.debug("流上线但未匹配到会话，跳过自动录像: {}/{}", app, stream);
            return;
        }
        String channelId = session.getChannelId();
        if (isRecording(channelId)) {
            return;
        }
        try {
            startRecording(channelId);
        } catch (Exception e) {
            log.warn("自动开始录像失败: channel={} source={}/{} err={}",
                    channelId, app, stream, e.getMessage());
        }
    }

    /**
     * 流下线：停止该通道的录制。
     *
     * <p>反查会话可能已失败（会话先被摘除），故先按会话找，找不到再退回
     * 「哪个在录的通道对应这个流名」兜底，避免录像一直挂着不结束。
     */
    @Override
    public void onStreamOffline(String app, String stream) {
        RtpSession session = sessionManager == null ? null : sessionManager.resolve(app, stream);
        String channelId = session == null ? null : session.getChannelId();
        if (channelId == null) {
            channelId = findRecordingChannelByStream(app, stream);
        }
        if (channelId != null && isRecording(channelId)) {
            // 不等最后一段落盘：本方法跑在 zlm-event-worker 单线程队列上，
            // 等满 10 秒会把其它通道的上下线事件一起堵住。
            // 真实路径由 on_mk_record_mp4 回填，兜底交给录像对账。
            stopRecording(channelId, false);
        }
    }

    /**
     * 停止所有正在进行的录制，返回实际停掉的通道数。
     *
     * <p><b>专供停机收尾</b>：{@link ZlmMediaServer#stop()} 里的
     * {@code mk_stop_all_server()} 会直接销毁媒体源，MP4 recorder 的异步收尾
     * （flush 缓冲 → 写 moov → 临时名改正式名）来不及做完，
     * 磁盘上就留下一个 {@code .} 前缀、<b>没有 moov</b> 的文件 ——
     * 大小正常但任何播放器都打不开（实测丢过 55 分钟 / 319 MB）。
     *
     * <p>因此必须在停媒体服务<b>之前</b>逐个 {@link #stopRecording}：
     * 此时流还活着，ZLM 能正常收尾，文件可播。
     *
     * <p>单个通道失败不影响其余通道：停机阶段一个异常不该让剩下的录像
     * 全都丢掉。
     *
     * @return 成功停止并完成落盘的通道数
     */
    public int stopAll() {
        List<String> channels = new ArrayList<>(activeByChannel.keySet());
        if (channels.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String channelId : channels) {
            try {
                // 用停机专用的短等待：见 SHUTDOWN_FINALIZE_MS 的说明
                if (stopRecording(channelId, SHUTDOWN_FINALIZE_MS) != null) {
                    n++;
                }
            } catch (Throwable t) {
                log.warn("停机停止录像失败（继续停其余通道）: channel={} {}", channelId, t.getMessage());
            }
        }
        return n;
    }

    /** 兜底：按录像条目里记录的 app/stream 反查正在录制的通道。 */
    private String findRecordingChannelByStream(String app, String stream) {
        for (Map.Entry<String, String> e : activeByChannel.entrySet()) {
            Recording r = index.get(e.getValue());
            if (r != null && app.equals(r.getApp()) && stream.equals(r.getStream())) {
                return e.getKey();
            }
        }
        return null;
    }

    private String fileUrl(String fileName) {
        return "http://" + props.getMediaIp() + ":" + mediaServer.getHttpPort()
                + "/" + props.getRecordApp() + "/" + fileName;
    }
}