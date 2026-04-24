import http from 'node:http'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const workspaceRoot = path.resolve(__dirname, '..')

const pluginState = {
  config: {
    ffmpegExecutable: process.env.FFMPEG_PATH || 'ffmpeg',
    outputRoot: path.resolve(workspaceRoot, 'plugin-runtime', 'hls'),
    layout: 1
  },
  streams: new Map()
}

const host = process.env.PLUGIN_HOST || '127.0.0.1'
const port = Number(process.env.PLUGIN_PORT || 18080)

function sendJson(res, statusCode, data) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  })
  res.end(JSON.stringify(data))
}

function sendText(res, statusCode, text, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(statusCode, {
    'Content-Type': contentType,
    'Access-Control-Allow-Origin': '*'
  })
  res.end(text)
}

async function readBody(req) {
  const chunks = []
  for await (const chunk of req) {
    chunks.push(chunk)
  }

  if (chunks.length === 0) {
    return {}
  }

  const content = Buffer.concat(chunks).toString('utf8')
  return content ? JSON.parse(content) : {}
}

function normalizeRtspUrl(rtspUrl) {
  const normalized = (rtspUrl || '').trim()
  if (!normalized) {
    throw new Error('RTSP 地址不能为空')
  }
  if (!normalized.startsWith('rtsp://')) {
    throw new Error('当前仅支持 rtsp:// 开头的地址')
  }
  return normalized
}

async function ensureDir(target) {
  await fsp.mkdir(target, { recursive: true })
}

async function pathExists(target) {
  try {
    await fsp.access(target, fs.constants.F_OK)
    return true
  } catch {
    return false
  }
}

async function readLogTail(logFile) {
  if (!(await pathExists(logFile))) {
    return '未找到 ffmpeg 日志'
  }

  const content = await fsp.readFile(logFile, 'utf8')
  const lines = content.trim().split(/\r?\n/).filter(Boolean)
  return lines.slice(-8).join(' | ') || '暂无日志'
}

async function isPlaylistReady(playlist) {
  if (!(await pathExists(playlist))) {
    return false
  }

  const content = await fsp.readFile(playlist, 'utf8')
  return content.includes('#EXTM3U') && content.includes('#EXTINF')
}

async function waitUntilReady(stream, timeoutSeconds) {
  const deadline = Date.now() + timeoutSeconds * 1000

  while (Date.now() < deadline) {
    if (await isPlaylistReady(stream.playlistPath)) {
      return
    }

    if (stream.process.exitCode !== null) {
      const logTail = await readLogTail(stream.logPath)
      throw new Error(`ffmpeg 已退出，未生成可播放 HLS。日志：${logTail}`)
    }

    await new Promise((resolve) => setTimeout(resolve, 500))
  }

  throw new Error('等待 HLS 输出超时，请检查 RTSP 地址是否可访问')
}

function buildPlayUrl(streamId) {
  return `/hls/${streamId}/index.m3u8`
}

function createStreamResponse(stream, message, status = 'RUNNING') {
  return {
    id: stream.id,
    rtspUrl: stream.rtspUrl,
    playUrl: buildPlayUrl(stream.id),
    status,
    message
  }
}

async function removeDir(target) {
  await fsp.rm(target, { recursive: true, force: true })
}

async function stopStream(stream) {
  if (!stream) {
    return
  }

  if (stream.process.exitCode === null) {
    stream.process.kill()
    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        stream.process.kill('SIGKILL')
        resolve()
      }, 3000)
      stream.process.once('exit', () => {
        clearTimeout(timer)
        resolve()
      })
    })
  }

  pluginState.streams.delete(stream.id)
  await removeDir(stream.streamDir)
}

async function startPreview(payload) {
  const rtspUrl = normalizeRtspUrl(payload.rtspUrl)
  const streamId = randomUUID().replace(/-/g, '')
  const streamDir = path.resolve(pluginState.config.outputRoot, streamId)
  const playlistPath = path.join(streamDir, 'index.m3u8')
  const segmentPattern = path.join(streamDir, 'segment_%05d.ts')
  const logPath = path.join(streamDir, 'ffmpeg.log')
  const hlsTimeSeconds = Number(payload.hlsTimeSeconds || 2)
  const hlsListSize = Number(payload.hlsListSize || 6)
  const startupTimeoutSeconds = Number(payload.startupTimeoutSeconds || 15)
  const rtspTransport = payload.rtspTransport || 'tcp'

  await ensureDir(streamDir)

  const outStream = fs.createWriteStream(logPath, { flags: 'a' })
  const command = [
    '-hide_banner',
    '-loglevel', 'warning',
    '-rtsp_transport', rtspTransport,
    '-i', rtspUrl,
    '-map', '0:v:0',
    '-map', '0:a:0?',
    '-c:v', 'libx264',
    '-preset', 'veryfast',
    '-tune', 'zerolatency',
    '-pix_fmt', 'yuv420p',
    '-c:a', 'aac',
    '-b:a', '128k',
    '-f', 'hls',
    '-hls_time', String(hlsTimeSeconds),
    '-hls_list_size', String(hlsListSize),
    '-hls_flags', 'delete_segments+append_list+omit_endlist+program_date_time+independent_segments',
    '-hls_segment_filename', segmentPattern,
    playlistPath
  ]

  const processRef = spawn(pluginState.config.ffmpegExecutable, command, {
    cwd: workspaceRoot,
    stdio: ['ignore', 'pipe', 'pipe']
  })

  processRef.stdout.pipe(outStream)
  processRef.stderr.pipe(outStream)

  const stream = {
    id: streamId,
    rtspUrl,
    streamDir,
    playlistPath,
    logPath,
    process: processRef
  }

  pluginState.streams.set(streamId, stream)

  try {
    await waitUntilReady(stream, startupTimeoutSeconds)
    return createStreamResponse(stream, '本地插件转流已启动')
  } catch (error) {
    await stopStream(stream)
    throw error
  }
}

async function stopAllStreams() {
  const streams = [...pluginState.streams.values()]
  for (const stream of streams) {
    await stopStream(stream)
  }
}

async function handleApi(req, res, pathname) {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type'
    })
    res.end()
    return
  }

  if (req.method === 'GET' && pathname === '/health') {
    sendJson(res, 200, {
      status: 'UP',
      service: 'rtsp-hls-local-plugin',
      ffmpegExecutable: pluginState.config.ffmpegExecutable,
      outputRoot: pluginState.config.outputRoot,
      activeStreams: pluginState.streams.size
    })
    return
  }

  if (req.method === 'POST' && pathname === '/plugin/init') {
    const body = await readBody(req)
    pluginState.config.ffmpegExecutable = body.ffmpegExecutable || pluginState.config.ffmpegExecutable
    pluginState.config.outputRoot = path.resolve(workspaceRoot, body.outputRoot || pluginState.config.outputRoot)
    pluginState.config.layout = Number(body.layout || pluginState.config.layout || 1)
    await ensureDir(pluginState.config.outputRoot)
    sendJson(res, 200, {
      message: '插件初始化完成',
      ffmpegExecutable: pluginState.config.ffmpegExecutable,
      outputRoot: pluginState.config.outputRoot,
      layout: pluginState.config.layout
    })
    return
  }

  if (req.method === 'POST' && pathname === '/plugin/preview/start') {
    const body = await readBody(req)
    const response = await startPreview(body)
    sendJson(res, 200, response)
    return
  }

  if (req.method === 'POST' && pathname === '/plugin/preview/stop') {
    const body = await readBody(req)
    const stream = pluginState.streams.get(body.streamId)
    if (!stream) {
      sendJson(res, 200, { message: '流不存在或已停止' })
      return
    }

    await stopStream(stream)
    sendJson(res, 200, { message: '本地插件预览已停止', streamId: body.streamId })
    return
  }

  if (req.method === 'POST' && pathname === '/plugin/preview/stop-all') {
    await stopAllStreams()
    sendJson(res, 200, { message: '所有本地插件预览已停止' })
    return
  }

  throw new Error(`未支持的接口：${req.method} ${pathname}`)
}

async function serveHls(res, pathname) {
  const targetPath = path.resolve(pluginState.config.outputRoot, `.${pathname.replace('/hls', '')}`)
  if (!targetPath.startsWith(pluginState.config.outputRoot)) {
    sendText(res, 403, 'Forbidden')
    return
  }

  if (!(await pathExists(targetPath))) {
    sendText(res, 404, 'Not Found')
    return
  }

  const ext = path.extname(targetPath).toLowerCase()
  const contentType = ext === '.m3u8'
    ? 'application/vnd.apple.mpegurl'
    : ext === '.ts'
      ? 'video/mp2t'
      : 'application/octet-stream'

  res.writeHead(200, {
    'Content-Type': contentType,
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store'
  })
  fs.createReadStream(targetPath).pipe(res)
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host}`)

  try {
    if (url.pathname.startsWith('/hls/')) {
      await serveHls(res, url.pathname)
      return
    }

    await handleApi(req, res, url.pathname)
  } catch (error) {
    sendJson(res, 500, {
      message: error instanceof Error ? error.message : '本地插件服务异常'
    })
  }
})

server.listen(port, host, () => {
  console.log(`RTSP HLS local plugin listening at http://${host}:${port}`)
})

async function shutdown() {
  await stopAllStreams()
  server.close(() => {
    process.exit(0)
  })
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
