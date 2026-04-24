# rtsp2hls-demo

把 RTSP（含 H.265/HEVC）摄像头流实时转为浏览器可播的 HLS（H.264 + AAC）Demo：
Spring Boot 后端调 ffmpeg 转码并提供 `.m3u8` / `.ts` 静态资源，Vue 3 前端用 `hls.js` 播放。

> 额外集成了一套"本地插件"形态（Windows `.exe` 安装包 + 本地 HTTP 服务），方便旧版海康 WebControl Demo 之类需要本机常驻进程的场景。

---

## 功能概览

- **RTSP → HLS** 实时转码，H.265 自动转 H.264，浏览器直接播
- **同源复用** + **心跳续租** + **空闲自动回收**，多个客户端看同一摄像头只跑一个 ffmpeg
- **内置 ffmpeg**：首次启动自动解压 `backend/ffmpeg/` 下的便携版到 `runtime/bin`，找不到系统 ffmpeg 也能跑
- **hls.js 播放器** + Safari 原生 HLS 自动回落
- **本地插件打包**：`scripts/build-local-plugin.ps1` 产出 `plugin-dist/RtspHlsLocalPlugin-Installer.exe`

---

## 架构

```
┌────────────┐   REST    ┌────────────────────┐   spawn    ┌────────┐
│ Vue 前端    │──/api──▶  │ Spring Boot (9000) │──────────▶ │ ffmpeg │
│  hls.js     │           │ StreamService      │            └────┬───┘
│  <video>    │           │                    │                 │
│             │◀─/hls/── │ 静态资源 /hls/**    │◀────写入 ts/m3u8 │
└────────────┘           └────────────────────┘                 │
                                   ▲                             │
                                   └─── runtime/hls/{streamId}/ ◀┘
```

目录：

```
rtsp2hls_demo/
├── backend/                     # Spring Boot 服务
│   ├── pom.xml
│   ├── runtime/                 # 运行时产物（hls/、bin/）
│   └── src/main/
│       ├── java/com/demo/rtsp2hls/
│       │   ├── controller/      # StreamController / LocalPluginController
│       │   ├── service/         # StreamService / FfmpegExecutableResolver
│       │   ├── model/           # OpenStreamRequest / StreamResponse
│       │   ├── config/          # StreamProperties / WebConfig
│       │   └── exception/       # ApiExceptionHandler
│       └── resources/application.yml
├── src/                         # Vue 3 前端
│   ├── App.vue
│   ├── components/
│   │   ├── RtspPlayer.vue       # 主播放器（调 /api/streams）
│   │   └── HikvisionPluginDemo.vue
│   └── services/
│       ├── streamService.js
│       └── rtspHlsWebControl.js
├── scripts/                     # 本地插件打包
│   ├── build-local-plugin.ps1
│   ├── rtsp-hls-plugin.mjs
│   └── installer-template/
├── plugin-dist/                 # 打包产物
├── docs/
│   └── API.md                   # 后端接口文档
├── vite.config.js
└── package.json
```

---

## 依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | Spring Boot 3.x 要求 |
| Maven | 3.8+ | 构建后端（项目未内置 mvnw） |
| Node.js | 18+ | 前端构建 |
| ffmpeg | 任意 | 项目已内置便携版，可不装 |

---

## 快速开始

### 1. 克隆并装前端依赖

```bash
git clone <repo>
cd rtsp2hls_demo
npm install
```

### 2. 启动后端（9000 端口）

Windows：
```cmd
cd backend
mvn spring-boot:run
```

macOS / Linux：
```bash
cd backend
mvn spring-boot:run
```

首次启动会把 `backend/ffmpeg/` 下的便携版 ffmpeg 解压到 `backend/runtime/bin/`；
想用系统 ffmpeg，设环境变量 `FFMPEG_PATH=/path/to/ffmpeg` 或改 [application.yml](backend/src/main/resources/application.yml) 的 `app.stream.ffmpeg-executable`。

### 3. 启动前端（5173 端口）

```bash
npm run dev
```

浏览器打开 <http://localhost:5173>，在表单里填你的摄像头 RTSP 地址（示例：`rtsp://user:pass@192.168.1.10:554/Streaming/Channels/101`），点「开始播放」。

Vite 已经把 `/api` 和 `/hls` 透传到后端 9000：

```js
// vite.config.js
proxy: {
  '/api': 'http://localhost:9000',
  '/hls': 'http://localhost:9000',
}
```

### 4. 生产构建

```bash
npm run build         # 产物到 dist/
cd backend && mvn package
```

把 `dist/` 的静态文件和后端 jar 部署到同一反代下（前端 `/` 反代到静态站点，`/api` + `/hls` 反代到 9000），前端的 `/api` 调用就会走到后端。

---

## 主要接口（速查）

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/streams/open` | 开启一路 RTSP→HLS 转流 |
| `POST` | `/api/streams/probe` | 直接探测 RTSP 源是否在线 |
| `POST` | `/api/streams/{id}/heartbeat` | 续租（建议 10–15s 一次） |
| `POST` | `/api/streams/{id}/release` | 观看者 -1，进入空闲 |
| `DELETE` | `/api/streams/{id}` | 立即关闭 |
| `GET` | `/api/streams/{id}` | 查询详情 |
| `GET` | `/api/streams` | 列出全部 |
| `GET` | `/hls/{id}/index.m3u8` | 播放入口 |
| `GET` | `/health` | 健康探测 |
| `POST` | `/plugin/preview/start` | 桌面插件兼容接口 |

完整字段、错误码、ffmpeg 参数解释见 **[docs/API.md](docs/API.md)**。

---

## 配置项

`backend/src/main/resources/application.yml`（可被环境变量覆盖）：

```yaml
server:
  port: 9000
app:
  stream:
    ffmpeg-executable: ${FFMPEG_PATH:ffmpeg}
    output-dir: runtime/hls
    hls-time-seconds: 2         # 单切片时长
    hls-list-size: 6            # 滑动窗口
    startup-timeout-seconds: 15 # 等首段 .ts 最长时间
    heartbeat-interval-seconds: 15
    idle-timeout-seconds: 30    # 无人观看多久后回收
    cleanup-interval-seconds: 15
```

---

## 本地插件形态

针对桌面集成场景（例如老版海康 WebControl）：

```bash
# 开发期直接跑 Node 脚本转发到后端
npm run dev:plugin

# 打包成 Windows 安装包
npm run build:plugin-installer
# 产物：plugin-dist/RtspHlsLocalPlugin-Installer.exe
```

前端有独立 Tab "本地插件 Demo" 演示 [`RtspHlsWebControl`](src/services/rtspHlsWebControl.js) 的用法。

---

## 常见问题

**Q：地址栏粘 `.m3u8` 打不开？**
A：Chrome/Edge/Firefox 不原生支持 HLS，只会下载 m3u8。必须通过页面里的播放器（内部用 hls.js）。Safari 可以直接播。

**Q：`ffmpeg 已退出`：`Unrecognized option 'stimeout'` / `Option rw_timeout not found`？**
A：新版 ffmpeg 7.x 不再支持这些选项。本仓库已改为不传超时参数，由 `startup-timeout-seconds`（默认 15s）在应用层兜底。

**Q：H.265 (HEVC) 摄像头不能播？**
A：服务端始终用 `libx264` 再编码输出 H.264，无需前端或浏览器支持 HEVC。

**Q：播放 1–2 分钟后卡住 / hls.js 报 `bufferStalledError`？**
A：通常是 RTSP 源帧率 / 关键帧间隔不稳。本仓库的 ffmpeg 命令已用 `-force_key_frames` 强制对齐切片边界；若仍出现，可把 `hls-time-seconds` 调到 4 并观察 `backend/runtime/hls/{streamId}/ffmpeg.log`。

**Q：多个用户看同一摄像头会开多个 ffmpeg 吗？**
A：不会。相同 `rtspUrl` 会复用已有流，`viewerCount` 累加；最后一个观看者 release 后进入 idle，到达 `idle-timeout-seconds` 才真正回收。

---

## 许可

仅用于内部技术演示，无显式开源许可。
