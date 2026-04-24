# 后端接口文档

RTSP → HLS 转流服务的 HTTP API。

- **Base URL**：`http://<host>:9000`
- **前端通过 Vite 代理**：`http://localhost:5173/api/**` → `http://localhost:9000/api/**`；`/hls/**` 同理透传。
- **内容类型**：除特殊说明外，请求/响应均为 `application/json; charset=UTF-8`。
- **CORS**：服务端放开 `/**`，允许任意来源、`GET/POST/DELETE/OPTIONS`、任意请求头（见 [WebConfig.java](../backend/src/main/java/com/demo/rtsp2hls/config/WebConfig.java)）。

## 统一响应

成功响应返回 [`StreamResponse`](../backend/src/main/java/com/demo/rtsp2hls/model/StreamResponse.java) 或各接口自有 JSON。错误响应由 [`ApiExceptionHandler`](../backend/src/main/java/com/demo/rtsp2hls/exception/ApiExceptionHandler.java) 统一封装：

| HTTP 状态 | 触发条件 | 响应体 |
|-----------|----------|--------|
| 400 | 参数校验失败 / RTSP 地址非法 | `{"code":400,"message":"..."}` |
| 500 | ffmpeg 启动失败 / 等待 HLS 超时 | `{"code":500,"message":"..."}` |

### `StreamResponse` 结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 流 ID（UUID，无连字符） |
| `sourceKey` | string | 规范化后的源 RTSP URL，作为相同源复用依据 |
| `rtspUrl` | string | 原始 RTSP 地址 |
| `playUrl` | string | 相对路径的 HLS 播放地址，形如 `/hls/{id}/index.m3u8` |
| `status` | string | `RUNNING` / `IDLE` / `EXITED` / `STOPPED` / `NOT_FOUND` |
| `viewerCount` | int | 当前在观看的客户端数 |
| `createdAt` | ISO-8601 | 流创建时间 |
| `lastAccessAt` | ISO-8601 | 最近一次心跳/活跃时间 |
| `message` | string | 人类可读的状态说明 |

---

## 一、转流主接口（`/api/streams`）

### 1. 打开流

```
POST /api/streams/open
```

若相同 RTSP 地址已有运行中的流，直接复用并 `viewerCount+1`；否则新建 ffmpeg 子进程并等待首个 `.ts` 切片落盘（超时由 `startup-timeout-seconds` 控制，默认 15s）。

**请求体**

```json
{ "rtspUrl": "rtsp://user:pass@192.168.1.10:554/Streaming/Channels/101" }
```

- `rtspUrl`：必填，非空字符串，必须以 `rtsp://` 开头。

**响应 200**

```json
{
  "id": "ecd1c3547dba483cb39315a20070e425",
  "sourceKey": "rtsp://user:pass@192.168.1.10:554/Streaming/Channels/101",
  "rtspUrl": "rtsp://user:pass@192.168.1.10:554/Streaming/Channels/101",
  "playUrl": "/hls/ecd1c3547dba483cb39315a20070e425/index.m3u8",
  "status": "RUNNING",
  "viewerCount": 1,
  "createdAt": "2026-04-24T03:38:10.000Z",
  "lastAccessAt": "2026-04-24T03:38:13.512Z",
  "message": "转流已启动"
}
```

**错误**
- 400 `"当前仅支持 rtsp:// 开头的地址"` / `"RTSP 地址不能为空"`
- 500 `"启动 ffmpeg 失败，请确认已安装并配置 ffmpeg。原因：..."`
- 500 `"ffmpeg 已退出，未生成可播放 HLS。日志：..."`
- 500 `"等待 HLS 输出超时，请检查 RTSP 地址是否可访问"`

---

### 2. 心跳续租

```
POST /api/streams/{streamId}/heartbeat
```

客户端应每 10–15s 调一次，避免流被后台清理任务回收（空闲回收阈值由 `idle-timeout-seconds` 控制，默认 30s）。

**路径参数**
- `streamId`：`open` 返回的流 ID。

**响应 200**：`StreamResponse`，`message="心跳已续期"`。

**错误**
- 400 `"流不存在"`

---

### 3. 释放观看者

```
POST /api/streams/{streamId}/release
```

当前观看者 `-1`；若降为 0 则流进入 `IDLE`，等待空闲回收。**不会立刻杀 ffmpeg**，目的是在用户快速切换时避免重启代价。

**响应 200**

- 仍有观看者：`message="观看者已释放"`
- 最后一个释放：`message="观看者已释放，等待空闲回收"`
- 流不存在：`status="NOT_FOUND"`，`message="流不存在或已释放"`

---

### 4. 强制关闭

```
DELETE /api/streams/{streamId}
```

立刻 kill ffmpeg、删除 `.m3u8` / `.ts` / 日志文件。通常无需调用，空闲回收会自动处理。

**响应 200**：`StreamResponse`，`status="STOPPED"`，`message="流已关闭"`。

---

### 5. 查询单条流详情

```
GET /api/streams/{streamId}
```

**响应 200**：`StreamResponse`。

- `status`：进程已退出返回 `EXITED`，无观看者返回 `IDLE`，否则 `RUNNING`。
- 流不存在返回 `status="NOT_FOUND"`。

---

### 6. 列出全部流

```
GET /api/streams
```

**响应 200**

```json
[
  {
    "id": "...",
    "playUrl": "/hls/.../index.m3u8",
    "status": "RUNNING",
    "viewerCount": 1,
    "...": "..."
  }
]
```

---

## 二、本地插件 / 健康接口

供桌面插件（`plugin-dist/RtspHlsLocalPlugin`）与海康 WebControl Demo 使用，所有 `StreamResponse` 字段同上。

### 1. 健康探测

```
GET /health
```

```json
{
  "status": "UP",
  "service": "rtsp-hls-local-plugin",
  "ffmpegExecutable": "D:\\...\\ffmpeg.exe",
  "activeStreams": 2,
  "bundledFfmpeg": true
}
```

### 2. 插件初始化

```
POST /plugin/init
```

**请求体（全部可选）**

```json
{
  "ffmpegExecutable": "D:/custom/ffmpeg.exe",
  "outputRoot": "D:/custom/hls",
  "layout": 1
}
```

**响应 200**

```json
{
  "message": "插件初始化完成",
  "ffmpegExecutable": "...",
  "layout": 1,
  "bundledFfmpeg": true,
  "requestedFfmpegExecutable": null,
  "requestedOutputRoot": null
}
```

> 当前实现不会真正切换 ffmpeg 路径 / 输出目录，仅回显。启动时以 [application.yml](../backend/src/main/resources/application.yml) 的 `app.stream.*` 为准。

### 3. 开始预览（等同 `/api/streams/open`）

```
POST /plugin/preview/start
```

**请求体**：`{ "rtspUrl": "rtsp://..." }`
**响应**：`StreamResponse`。

### 4. 停止单路预览

```
POST /plugin/preview/stop
```

**请求体**：`{ "streamId": "..." }`（可省略）
**响应**

- 有 `streamId`：`{"message": "流已关闭", "streamId": "..."}`
- 空或省略：`{"message": "当前无活跃预览"}`

### 5. 停止全部预览

```
POST /plugin/preview/stop-all
```

响应：`{"message": "所有本地插件预览已停止"}`。

---

## 三、HLS 静态资源

```
GET /hls/{streamId}/index.m3u8
GET /hls/{streamId}/segment_{NNNNN}.ts
```

由 Spring 静态资源处理器提供，物理目录为 `app.stream.output-dir`（默认 `backend/runtime/hls`）。

- `.m3u8` → `application/vnd.apple.mpegurl`
- `.ts` → `video/mp2t`

> 浏览器原生播放 HLS 仅 Safari 支持；Chrome / Edge / Firefox 需要通过 [`hls.js`](https://github.com/video-dev/hls.js/) 挂载到 `<video>`。

---

## 四、ffmpeg 转码参数

服务端对每个流启动一个独立 ffmpeg 进程（见 [`StreamService.startFfmpeg`](../backend/src/main/java/com/demo/rtsp2hls/service/StreamService.java)），核心参数：

| 类别 | 参数 | 作用 |
|------|------|------|
| 输入 | `-rtsp_transport tcp` | 强制 TCP，避免 UDP 丢包 |
| 输入 | `-fflags +genpts+discardcorrupt` | 补 PTS、丢弃损坏帧 |
| 输入 | `-analyzeduration 1M -probesize 1M` | 加快探测以缩短首段产出时间 |
| 视频 | `-c:v libx264 -preset veryfast -tune zerolatency` | HEVC/H.264 → H.264 低延迟编码 |
| 视频 | `-profile:v main -level:v 4.1 -pix_fmt yuv420p` | 浏览器兼容的 baseline-friendly 输出 |
| 视频 | `-force_key_frames expr:gte(t,n_forced*hlsTime)` | 强制关键帧对齐切片边界 |
| 音频 | `-c:a aac -b:a 128k -ar 44100 -ac 2 -af aresample=async=1000` | AAC 48/44.1 重采样 + 时钟补偿 |
| HLS | `-hls_time N -hls_list_size M` | 切片时长与滑动窗口大小，来自 `app.stream.*` |
| HLS | `-hls_flags delete_segments+omit_endlist+independent_segments+program_date_time` | 直播式 m3u8，自动清理旧段 |

---

## 五、配置项（`app.stream.*`）

见 [application.yml](../backend/src/main/resources/application.yml) 与 [`StreamProperties`](../backend/src/main/java/com/demo/rtsp2hls/config/StreamProperties.java)：

| 键 | 默认 | 说明 |
|----|------|------|
| `ffmpeg-executable` | `ffmpeg`（可被环境变量 `FFMPEG_PATH` 覆盖） | 未命中时回落到内置版本 |
| `bundled-ffmpeg-root` | `ffmpeg` | 内置 ffmpeg 解压根目录 |
| `bundled-ffmpeg-output-dir` | `runtime/bin` | 内置 ffmpeg 落盘目录 |
| `output-dir` | `runtime/hls` | HLS 产物根目录 |
| `hls-time-seconds` | 2 | 单个切片时长 |
| `hls-list-size` | 6 | 滑动窗口内保留的切片数 |
| `startup-timeout-seconds` | 15 | `open` 等首段 `.ts` 的最长时间 |
| `heartbeat-interval-seconds` | 15 | 客户端建议心跳间隔（仅参考） |
| `idle-timeout-seconds` | 30 | 无观看者多久后回收 |
| `cleanup-interval-seconds` | 15 | 后台清理任务轮询间隔 |

---

## 六、调用示例（curl）

```bash
# 开流
curl -X POST http://localhost:9000/api/streams/open \
  -H 'Content-Type: application/json' \
  -d '{"rtspUrl":"rtsp://user:pass@192.168.1.10:554/stream1"}'

# 心跳
curl -X POST http://localhost:9000/api/streams/<id>/heartbeat

# 释放观看者
curl -X POST http://localhost:9000/api/streams/<id>/release

# 立即关闭
curl -X DELETE http://localhost:9000/api/streams/<id>

# 列表 / 详情
curl http://localhost:9000/api/streams
curl http://localhost:9000/api/streams/<id>
```
