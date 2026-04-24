<template>
  <section class="player-card">
    <header class="section-head">
      <div>
        <h2>流播放器</h2>
        <p>默认调用后端转流接口 `POST {{ apiBase }}/streams/open`，返回 HLS 地址后立即播放。</p>
      </div>
      <div class="status-pill" :data-status="status.type">{{ status.label }}</div>
    </header>

    <form class="player-form" @submit.prevent="handlePlay">
      <label class="field">
        <span>RTSP 地址</span>
        <input
          v-model.trim="rtspUrl"
          type="text"
          placeholder="rtsp://admin:password@192.168.1.10:554/stream1"
        />
      </label>

      <label class="field">
        <span>转流服务地址</span>
        <input
          v-model.trim="apiBase"
          type="text"
          placeholder="/api"
        />
      </label>

      <div class="actions">
        <button class="primary-btn" type="submit" :disabled="loading">
          {{ loading ? '正在连接...' : '开始播放' }}
        </button>
        <button class="ghost-btn" type="button" :disabled="loading" @click="handleStop">
          停止播放
        </button>
      </div>
    </form>

    <div class="player-stage">
      <video
        ref="videoRef"
        class="video-panel"
        controls
        autoplay
        muted
        playsinline
      ></video>
      <div v-if="!currentPlayUrl" class="empty-mask">
        <p>等待视频流</p>
        <span>成功转流后，这里会自动开始播放</span>
      </div>
    </div>

    <dl class="meta-grid">
      <div>
        <dt>当前 RTSP</dt>
        <dd>{{ currentRtspUrl || '未连接' }}</dd>
      </div>
      <div>
        <dt>播放地址</dt>
        <dd>{{ currentPlayUrl || '未生成' }}</dd>
      </div>
      <div>
        <dt>流 ID</dt>
        <dd>{{ currentStreamId || '无' }}</dd>
      </div>
      <div>
        <dt>观看人数</dt>
        <dd>{{ currentViewerCount || 0 }}</dd>
      </div>
      <div>
        <dt>错误信息</dt>
        <dd>{{ errorMessage || '无' }}</dd>
      </div>
    </dl>
  </section>
</template>

<script setup>
import Hls from 'hls.js'
import { computed, onBeforeUnmount, ref } from 'vue'
import {
  heartbeatRtspStream,
  openRtspStream,
  releaseRtspStream,
  releaseRtspStreamInBackground
} from '../services/streamService'

const videoRef = ref(null)
const loading = ref(false)
const rtspUrl = ref('')
const apiBase = ref(import.meta.env.VITE_STREAM_API_BASE || '/api')
const currentPlayUrl = ref('')
const currentRtspUrl = ref('')
const currentStreamId = ref('')
const currentViewerCount = ref(0)
const errorMessage = ref('')
let hls = null
let heartbeatTimer = null

const status = computed(() => {
  if (loading.value) {
    return { type: 'pending', label: '连接中' }
  }

  if (errorMessage.value) {
    return { type: 'error', label: '播放失败' }
  }

  if (currentPlayUrl.value) {
    return { type: 'success', label: '播放中' }
  }

  return { type: 'idle', label: '未连接' }
})

function destroyPlayer() {
  if (hls) {
    hls.destroy()
    hls = null
  }

  const video = videoRef.value
  if (!video) {
    return
  }

  video.pause()
  video.removeAttribute('src')
  video.load()
}

function startHeartbeat() {
  stopHeartbeat()

  if (!currentStreamId.value) {
    return
  }

  heartbeatTimer = window.setInterval(async () => {
    try {
      const stream = await heartbeatRtspStream(apiBase.value, currentStreamId.value)
      currentViewerCount.value = stream.viewerCount || 0
    } catch (error) {
      console.warn('heartbeat failed', error)
    }
  }, 10000)
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    window.clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function getBackendOrigin(base) {
  const normalizedBase = (base || '').trim()

  if (!normalizedBase || normalizedBase.startsWith('/')) {
    return window.location.origin
  }

  try {
    return new URL(normalizedBase).origin
  } catch {
    return window.location.origin
  }
}

function resolvePlayUrl(playUrl) {
  if (!playUrl) {
    return ''
  }

  if (/^https?:\/\//i.test(playUrl)) {
    return playUrl
  }

  const backendOrigin = getBackendOrigin(apiBase.value)

  if (playUrl.startsWith('/')) {
    return `${backendOrigin}${playUrl}`
  }

  return `${backendOrigin}/${playUrl.replace(/^\/+/, '')}`
}

async function attachPlayUrl(playUrl) {
  const video = videoRef.value
  if (!video) {
    throw new Error('播放器实例未初始化')
  }

  destroyPlayer()

  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = playUrl
    await video.play()
    return
  }

  if (Hls.isSupported()) {
    hls = new Hls({
      lowLatencyMode: true
    })
    await new Promise((resolve, reject) => {
      const onManifestParsed = async () => {
        try {
          await video.play()
          resolve()
        } catch (error) {
          reject(error)
        }
      }

      const onError = (_, data) => {
        if (data?.fatal) {
          reject(new Error(`HLS 播放失败: ${data.type || 'UNKNOWN'}/${data.details || 'UNKNOWN'}`))
        }
      }

      hls.once(Hls.Events.MANIFEST_PARSED, onManifestParsed)
      hls.on(Hls.Events.ERROR, onError)
      hls.loadSource(playUrl)
      hls.attachMedia(video)
    })
    return
  }

  throw new Error('当前浏览器不支持 HLS 播放')
}

async function handlePlay() {
  if (!rtspUrl.value) {
    errorMessage.value = '请先输入 RTSP 地址'
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    if (currentStreamId.value) {
      await releaseRtspStream(apiBase.value, currentStreamId.value)
      currentStreamId.value = ''
      currentViewerCount.value = 0
    }

    if (rtspUrl.value.endsWith('.m3u8')) {
      currentRtspUrl.value = rtspUrl.value
      currentPlayUrl.value = rtspUrl.value
      await attachPlayUrl(rtspUrl.value)
      return
    }

    const stream = await openRtspStream(apiBase.value, rtspUrl.value)
    const rawPlayUrl = stream.playUrl || stream.hlsUrl || stream.streamUrl
      || (stream.id ? `/hls/${stream.id}/index.m3u8` : '')
    const playUrl = resolvePlayUrl(rawPlayUrl)

    if (!playUrl) {
      throw new Error('转流接口未返回可播放地址')
    }

    currentRtspUrl.value = rtspUrl.value
    currentPlayUrl.value = playUrl
    currentStreamId.value = stream.id || ''
    currentViewerCount.value = stream.viewerCount || 0
    await attachPlayUrl(playUrl)
    startHeartbeat()
  } catch (error) {
    stopHeartbeat()
    destroyPlayer()
    currentPlayUrl.value = ''
    errorMessage.value = error instanceof Error ? error.message : '播放失败'
  } finally {
    loading.value = false
  }
}

async function handleStop() {
  stopHeartbeat()
  destroyPlayer()
  errorMessage.value = ''
  currentPlayUrl.value = ''

  if (!currentStreamId.value) {
    return
  }

  try {
    await releaseRtspStream(apiBase.value, currentStreamId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '关闭流失败'
  } finally {
    currentStreamId.value = ''
    currentViewerCount.value = 0
  }
}

function handlePageHide() {
  stopHeartbeat()
  if (currentStreamId.value) {
    releaseRtspStreamInBackground(apiBase.value, currentStreamId.value)
    currentStreamId.value = ''
    currentViewerCount.value = 0
  }
}

window.addEventListener('pagehide', handlePageHide)

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', handlePageHide)
  handlePageHide()
  stopHeartbeat()
  destroyPlayer()
})
</script>
