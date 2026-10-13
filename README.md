# gb28181-sip-zlm

GB28181 **流媒体服务**：基于 [`gb28181-sip`](https://central.sonatype.com/artifact/io.github.rurulalanana/gb28181-sip) 信令模块 +
[`zlm4j`](https://central.sonatype.com/artifact/com.aizuda/zlm4j)（ZLMediaKit C API 的 Java 封装）的收流集成。

- **信令**（REGISTER / INVITE / ACK / BYE / MANSCDP）由 `gb28181-sip` 处理
- **媒体**（RTP/PS 接收、解复用、多协议输出）由内嵌的 ZLMediaKit 处理——**无需部署独立的流媒体服务器**
- zlm4j jar 内自带 win64 / linux64 / linux_arm64 / mac64 / mac_arm64 全平台原生库，JNA 自动加载

## 依赖

```xml
<dependency>
  <groupId>io.github.rurulalanana</groupId>
  <artifactId>gb28181-sip-zlm</artifactId>
  <version>1.0.0</version>
</dependency>
```

> 依赖 `gb28181-sip` **1.0.2+**：
> - 1.0.0 的 `respondInvite` 在 jain-sip 1.2.344 下会静默丢弃应答（ServerTransaction 恒为 null 且 200 OK 缺 Contact 头）
> - 1.0.2 起补充了回放控制所需的 `client.info(deviceId, callId, sdp)`（INFO 会话内请求）

## 快速开始

```java
Gb28181MediaPlatform platform = new Gb28181MediaPlatform(
        MediaServerProperties.builder()
                .platformId("34020000002000000001")   // 本平台 20 位编码
                .sipPort(5060)                        // SIP 信令端口
                .mediaIp("192.168.1.100")             // 设备可达的媒体 IP（SDP c= 行）
                .httpPort(8080)                       // HTTP-FLV / HLS
                .rtmpPort(1935).rtspPort(8554).rtcPort(8000)
                .build());
platform.start();

// 设备注册后，平台主动点播（标准 GB28181 实时点播流程）
RtpSession session = platform.mediaService().play("34020000001320000001");
PlayUrls urls = platform.mediaService().playUrls("34020000001320000001");
// http://192.168.1.100:8080/rtp/34020000001320000001.live.flv
// webrtc://192.168.1.100:8000/rtp/34020000001320000001
// rtmp://192.168.1.100:1935/rtp/34020000001320000001 ...

platform.stop();  // 或 try-with-resources（实现 AutoCloseable）
```

## 媒体能力总览

| 能力 | 状态 | 入口 |
|---|---|---|
| 实时点播（平台主动 / 设备主动 INVITE） | ✅ | `play(deviceId)` / 自动应答 |
| 历史回放（标准 9.8） | ✅ | `queryRecordInfo()` → `playback(deviceId, item)` |
| 回放控制：播放/暂停/跳转/倍速（附录 D，INFO） | ✅ | `playbackControl(deviceId, callId, PLAYBACKCommand, ...)` |
| 文件下载（标准 9.9） | ✅ | `download(deviceId, filePath)` |
| 平台侧录像（实时流录 MP4）+ 索引检索 + 点播 | ✅ | `recordingService()` |
| 级联媒体转发（第 8 章媒体面） | ✅ | `cascadeForwarder()` |
| 语音广播上行 + 对讲下行 | ⚠️ 单向 | `broadcastService()`（ZLM C API 无 talk，无法真正双向） |
| 平台录像索引持久化（落库） | ⚠️ 内存 | 索引在内存，重启丢失，可自行落库 |

## 工作流程

### 历史回放

```java
// 1) 检索设备录像（标准 9.8）
mediaService.queryRecordInfo("34020000001320000001",
        "2026-10-12T00:00:00", "2026-10-13T00:00:00",
        items -> {
            if (items.isEmpty()) return;
            // 2) 发起回放（SDP 自动带 u=文件路径、t=时间段、历史位 SSRC）
            RtpSession session = mediaService.playback("34020000001320000001", items.get(0));
            // 3) 回放控制（INFO，标准附录 D）
            mediaService.playbackControl("34020000001320000001", callIdOf(session),
                    Gb28181MediaService.PlaybackCommand.PAUSE, null, 1.0f);
            mediaService.playbackControl("34020000001320000001", callIdOf(session),
                    Gb28181MediaService.PlaybackCommand.SEEK, new Date(), 1.0f);
            mediaService.playbackControl("34020000001320000001", callIdOf(session),
                    Gb28181MediaService.PlaybackCommand.SPEED, null, 2.0f);
        });
```

> 回放的收流与播放地址与实时完全一致（ZLM 自动转协议），只是 SDP 的 `s=Playback`、
> `u=` 与历史位 SSRC（首位 1）。

### 平台侧录像

```java
RecordingService rs = platform.recordingService();
rs.setAutoRecord(true);                       // 随流上下线自动录
rs.startRecording("34020000001320000001");     // 或手动
List<Recording> list = rs.search("34020000001320000001", fromMillis, toMillis);
PlayUrls urls = rs.playUrl(list.get(0));       // 拉入 ZLM 转协议点播
String fileUrl = list.get(0).getFileUrl();     // 或直接下载录像文件
```

录像落盘目录 `recordRoot/<通道编码>/<通道>_<时间戳>.mp4`，单文件时长由 `recordMaxSecond` 控制。

### 级联媒体转发

上级平台点播本级通道时自动触发：本地收下级设备的流 → ZLM 转 PS/RTP 发往上级 →
用本地端口回 200 OK。结束（BYE）自动停转发并释放收流端口。

### 语音广播 / 对讲

```java
BroadcastService bs = platform.broadcastService();
// 上行：设备发 INVITE(s=AudioBroadcast) 自动应答并收流
PlayUrls urls = bs.playUrls(deviceId);          // 前端收听
// 下行：把平台侧流以 PS 发给设备
bs.startDownlink("rtp", "local-stream", deviceIp, devicePort, ssrc);
```

> **限制**：ZLMediaKit 的 C API 未暴露 `startSendRtpTalk`，无法真正「边收边发」的双向对讲，
> 只能上行收流、下行发送分别进行。

### 实时点播（平台主动）

```
play(deviceId)
  → RtpSessionManager.open()        创建 ZLM RTP 服务器（独占端口，port=0 随机）
  → client().invitePlay(sdp)        信令模块向设备发 INVITE（SDP 携带收流地址）
  → 设备回 200 OK 并向该端口推 RTP/PS
  → ZLM 解复用 → 发布为多协议流（on_mk_media_publish 事件标记 ONLINE）
  → playUrls() 返回各协议播放地址
```

### 实时点播（设备主动 INVITE）

设备直接向平台发 INVITE 时同样自动处理：

```
onInvite(InviteEvent)
  → 解析 Subject 得通道编码（流名）
  → 创建 RTP 服务器 → respondInvite(200 OK + SDP: m=video <port> RTP/AVP 96 / a=rtpmap:96 PS/90000 / y=<平台SSRC>)
  → BYE → 释放 RTP 服务器
```

### 会话状态机

```
NEGOTIATING --(on_mk_media_publish)--> ONLINE --(on_mk_media_changed(0))--> NEGOTIATING
      |                                                              |
      +----------------------(BYE / stop / detach)-----------------> CLOSED
```

RTP 数据超时（默认 15s）只会让**流下线**，端口保留等待设备恢复推流；
`BYE` / 主动 `stop` 才关闭 RTP 服务器。两种关闭路径都保证
`mk_rtp_server_release` 最多调用一次（detach 回调只做账务清理）。

## 关键实现说明

| 项 | 说明 |
|---|---|
| 流名 | 通道编码（GB28181 DeviceID），播放路径 `/{app}/{channelId}.live.flv` |
| RTP 端口 | 默认 `0`（ZLM 随机分配，零配置）；`rtpPortRangeStart/End` 可固定范围便于防火墙放行 |
| TCP 模式 | 设备 SDP 声明 TCP 时走 `tcp_mode=1`（ZLM 监听，设备接入），应答带 `a=setup:passive` |
| SSRC 过滤 | 设备 INVITE 的 SDP `y=` 值传给 ZLM 做过滤；未声明则 `0` 不过滤 |
| 平台 SSRC | 按设备编码生成（附录 G：实时/历史位 + 域标识 + 序号），用于 SDP `y=` 行 |
| HTTP API | **不可用**：`/index/api/*` 是 MediaServer 可执行文件实现的路由，C API 嵌入方不注册；流状态一律走 `MK_EVENTS` 事件回调 |
| 回放/下载 | v1 仅支持实时点播；`s=Playback/Download` 按标准注 17 回 `488` |

## 配置项（MediaServerProperties）

| 参数 | 默认 | 说明 |
|---|---|---|
| `platformId` | `34020000002000000001` | 平台 20 位编码（SDP `o=` 行） |
| `sipHost` / `sipPort` | `0.0.0.0` / `5060` | SIP 信令监听 |
| `mediaIp` | 自动探测 | 媒体 IP，**必须是设备可达地址**，填错将收不到流 |
| `httpPort` | `8080` | HTTP-FLV / HLS / WebRTC 信令 |
| `rtmpPort` / `rtspPort` / `rtcPort` | `1935` / `8554` / `8000` | 各协议端口，`-1` 关闭 |
| `app` | `rtp` | ZLM 应用名（播放 URL 第一段） |
| `rtpPortRangeStart/End` | `0` / `0` | RTP 固定端口范围；0 = 随机分配 |
| `vhost` | `__defaultVhost__` | ZLM 虚拟主机 |
| `recordRoot` | `${user.dir}/record` | 平台录像根目录 |
| `recordMaxSecond` | `3600` | 单文件录像时长上限（秒） |
| `recordApp` | `record` | 录像 HTTP 直链应用名 |
| `autoRecord` | `false` | 随流自动录像 |

## 测试

```bash
mvn test
```

| 测试 | 覆盖 |
|---|---|
| `MediaServiceUnitTest` | Subject 解析、SDP 应答构造、SSRC 归一化、播放地址（纯逻辑，无原生依赖） |
| `RecordPlaybackUnitTest` | RecordInfo XML 解析、回放/下载 SDP（历史位 SSRC、u=、t=）、会话类型映射 |
| `ZlmMediaServerTest` | ZLM 启停、RTP 服务器创建/释放（UDP/TCP）、录制与转推句柄、端口语义 |
| `Gb28181MediaPlatformE2ETest` | 端到端：裸 SIP 设备模拟器 → REGISTER → INVITE(Play) → 200 OK（SDP 端口与 ZLM 会话一致）→ ACK → BYE → 会话释放；回放 INVITE 校验历史位 SSRC；下载 INVITE 校验 u= |

> 原生测试在无匹配平台原生库时自动跳过（JUnit `Assume`）。

## 排障

### `NoClassDefFoundError: com/aizuda/zlm4j/core/ZLMApi`

`zlm4j`（1.12.0，compile 作用域）是本模块的传递依赖，未进 classpath 通常是**本地 Maven 缓存了"构件不存在"的结论**——版本刚发布时国内镜像（阿里云等）有几分钟同步延迟，Maven 把 404 写进 `~/.m2/repository/**/*.lastUpdated` 后不再重试。

```bash
mvn dependency:tree | grep zlm4j    # 确认依赖树
mvn -U clean install                # 强制更新（最常见解法）
rm -rf ~/.m2/repository/com/aizuda/zlm4j   # 仍不行则清缓存重下
```

### 其它启动期现象

| 现象 | 原因 |
|---|---|
| `UnsatisfiedLinkError` / 加载 `mk_api` 失败 | zlm4j 1.12.0 内含 win/linux/mac（x86_64 与 arm64）原生库，检查是否被裁剪或平台不支持 |
| 媒体服务"启动失败"但无堆栈 | `mk_http_server_start` 等**返回 0 而不抛异常**，需看 ZLM 原生日志判断 |
| 端口明明空闲却报启动失败 | ZLM 返回 `uint16_t`，若经有符号 `short` 中转会变负数；本模块已按无符号解读 |

## 已知限制

- **未做真实 RTP/PS 媒体流验证**：E2E 验证到信令协商 + RTP 服务器生命周期 + 录制/转推句柄可用性；完整媒体链路（设备推流 → 画面出流 → 出录像文件）需要真实 GB28181 设备（或 EasyGBD 模拟）验证
- **对讲非双向**：ZLM C API 无 talk 能力，只能上行收 + 下行发
- **录像索引在内存**：重启丢失，多实例不共享，需要持久化请自行落库（`RecordingService` 可作为数据源）
- **级联转发为 UDP 单向**：`startSendRtp` 走 UDP，上级要求 TCP 主动连接需上层另行处理
- ZLMediaKit 的 HTTP RESTful API 在嵌入模式下不可用，业务查询请用事件回调或自行扩展

## License

Apache License 2.0（与 gb28181-sip 一致）。zlm4j 为 MIT，ZLMediaKit 为 MIT。
