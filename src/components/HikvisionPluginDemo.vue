<template>
  <section class="player-card">
    <header class="section-head">
      <div>
        <h2>本地插件式转流 Demo</h2>
        <p>
          调用链路仿照海康插件模式：页面 JS 只连接本机插件服务，由本地服务启动 ffmpeg
          把 RTSP 转成 HLS，再回放本机 `localhost` 输出的播放地址。
        </p>
      </div>
      <div class="status-pill" :data-status="status.type">{{ status.label }}</div>
    </header>

    <div class="notice-card">
      <strong>运行方式</strong>
      <p>1. 保留当前 HLS 页面不变；本页不调用 `backend` 的 `/api/streams/*` 接口。</p>
      <p>2. 可直接安装 `plugin-dist/RtspHlsLocalPlugin-Installer.exe`，或开发时手工启动本地插件服务。</p>
      <p>3. 本地插件服务会在用户机器上直接调用内置 ffmpeg 并暴露 HLS 文件。</p>
    </div>

    <div v-if="pluginAvailability === 'missing'" class="download-card">
      <div>
        <strong>未检测到本地插件</strong>
        <p>浏览器当前无法连接 `{{ pluginServiceBase }}`，请先下载并安装本地插件，再点击“重新检测插件”。</p>
      </div>
      <div class="download-actions">
        <a
          class="primary-btn download-link"
          :href="pluginDownloadUrl"
          download
          target="_blank"
          rel="noreferrer"
        >
          下载插件
        </a>
        <button class="ghost-btn" type="button" :disabled="loading" @click="checkPluginAvailability">
          重新检测插件
        </button>
      </div>
    </div>

    <form class="player-form plugin-form" @submit.prevent="handleStartPreview">
      <label class="field">
        <span>本地插件服务地址</span>
        <input
          v-model.trim="pluginServiceBase"
          type="text"
          placeholder="http://127.0.0.1:18080"
        />
      </label>

      <label class="field">
        <span>ffmpeg 路径</span>
        <input
          v-model.trim="ffmpegExecutable"
          type="text"
          placeholder="ffmpeg"
        />
      </label>

      <label class="field">
        <span>HLS 输出根目录</span>
        <input
          v-model.trim="outputRoot"
          type="text"
          placeholder="plugin-runtime/hls"
        />
      </label>

      <label class="field">
        <span>播放容器布局</span>
        <select v-model="layout">
          <option value="1x1">1x1</option>
          <option value="2x2">2x2</option>
        </select>
      </label>

      <label class="field field-full">
        <span>RTSP 地址</span>
        <input
          v-model.trim="rtspUrl"
          type="text"
          placeholder="rtsp://admin:password@192.168.1.10:554/stream1"
        />
      </label>

      <label class="field">
        <span>RTSP 传输方式</span>
        <select v-model="rtspTransport">
          <option value="tcp">tcp</option>
          <option value="udp">udp</option>
        </select>
      </label>

      <label class="field">
        <span>HLS 分片时长</span>
        <input
          v-model.number="hlsTimeSeconds"
          type="number"
          min="1"
          max="10"
          placeholder="2"
        />
      </label>

      <label class="field">
        <span>播放列表长度</span>
        <input
          v-model.number="hlsListSize"
          type="number"
          min="3"
          max="20"
          placeholder="6"
        />
      </label>

      <label class="field">
        <span>启动超时(秒)</span>
        <input
          v-model.number="startupTimeoutSeconds"
          type="number"
          min="3"
          max="60"
          placeholder="15"
        />
      </label>

      <div class="actions field-full">
        <button class="primary-btn" type="submit" :disabled="loading">
          {{ loading ? '正在初始化...' : '初始化并预览' }}
        </button>
        <button class="ghost-btn" type="button" :disabled="loading" @click="handleStopPreview">
          停止预览
        </button>
        <button class="ghost-btn" type="button" :disabled="loading" @click="handleReconnect">
          重建插件窗口
        </button>
      </div>
    </form>

    <div class="player-stage plugin-stage">
      <div :id="pluginContainerId" ref="pluginContainerRef" class="plugin-container"></div>
      <div v-if="!pluginReady" class="empty-mask">
        <p>等待本地插件服务</p>
        <span>{{ helperText }}</span>
      </div>
    </div>

    <dl class="meta-grid">
      <div>
        <dt>插件服务</dt>
        <dd>{{ pluginServiceBase }}</dd>
      </div>
      <div>
        <dt>插件窗口</dt>
        <dd>{{ pluginReady ? '已创建' : '未创建' }}</dd>
      </div>
      <div>
        <dt>当前 RTSP</dt>
        <dd>{{ currentRtspUrl || '未预览' }}</dd>
      </div>
      <div>
        <dt>本地播放地址</dt>
        <dd>{{ currentPlayUrl || '未生成' }}</dd>
      </div>
      <div>
        <dt>流 ID</dt>
        <dd>{{ currentStreamId || '无' }}</dd>
      </div>
      <div>
        <dt>提示信息</dt>
        <dd>{{ errorMessage || latestMessage || '无' }}</dd>
      </div>
    </dl>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RtspHlsWebControl } from '../services/rtspHlsWebControl'

const pluginServiceBase = ref('http://127.0.0.1:18080')
const ffmpegExecutable = ref('ffmpeg')
const outputRoot = ref('plugin-runtime/hls')
const layout = ref('1x1')
const rtspUrl = ref('')
const rtspTransport = ref('tcp')
const hlsTimeSeconds = ref(2)
const hlsListSize = ref(6)
const startupTimeoutSeconds = ref(15)

const loading = ref(false)
const pluginReady = ref(false)
const pluginAvailability = ref('checking')
const errorMessage = ref('')
const latestMessage = ref('')
const currentRtspUrl = ref('')
const currentPlayUrl = ref('')
const currentStreamId = ref('')
const pluginContainerRef = ref(null)
const pluginContainerId = 'hikvision-plugin-window'
const pluginDownloadUrl = `${import.meta.env.BASE_URL}downloads/RtspHlsLocalPlugin-Installer.exe`

let webControl = null
let resizeTimer = 0

const status = computed(() => {
  if (loading.value) {
    return { type: 'pending', label: '初始化中' }
  }

  if (pluginAvailability.value === 'checking') {
    return { type: 'pending', label: '检测插件中' }
  }

  if (pluginAvailability.value === 'missing') {
    return { type: 'error', label: '未安装插件' }
  }

  if (errorMessage.value) {
    return { type: 'error', label: '连接失败' }
  }

  if (pluginReady.value && currentPlayUrl.value) {
    return { type: 'success', label: '预览中' }
  }

  if (pluginReady.value) {
    return { type: 'success', label: '插件已就绪' }
  }

  return { type: 'idle', label: '未初始化' }
})

const helperText = computed(() => {
  if (errorMessage.value) {
    return errorMessage.value
  }

  if (pluginAvailability.value === 'checking') {
    return '正在检测本地插件服务，请稍候。'
  }

  if (pluginAvailability.value === 'missing') {
    return '未检测到本地插件服务，请先下载安装插件。'
  }

  return '插件已就绪，可以直接通过 localhost 发起转流和播放。'
})

function setMessage(message) {
  latestMessage.value = message
}

function getContainerRect() {
  const element = pluginContainerRef.value
  if (!element) {
    return { width: 960, height: 540 }
  }

  return {
    width: Math.max(320, Math.floor(element.clientWidth || 960)),
    height: Math.max(240, Math.floor(element.clientHeight || 540))
  }
}

function disconnectControl() {
  webControl?.JS_Disconnect?.()
  webControl = null
  pluginReady.value = false
}

function requestInterface(funcName, argument = {}) {
  if (!webControl) {
    throw new Error('插件实例尚未创建')
  }

  return webControl.JS_RequestInterface({
    funcName,
    argument: JSON.stringify(argument)
  })
}

async function checkPluginAvailability() {
  pluginAvailability.value = 'checking'
  errorMessage.value = ''

  try {
    const probeControl = new RtspHlsWebControl({
      serviceBase: pluginServiceBase.value
    })
    await probeControl.connect()
    pluginAvailability.value = 'available'
    setMessage('已检测到本地插件服务')
  } catch (error) {
    pluginAvailability.value = 'missing'
    errorMessage.value = '未检测到本地插件服务，请下载安装插件后重试'
  }
}

async function createPluginWindow() {
  await nextTick()

  if (webControl) {
    return
  }

  const { width, height } = getContainerRect()

  await new Promise((resolve, reject) => {
    webControl = new RtspHlsWebControl({
      szPluginContainer: pluginContainerId,
      serviceBase: pluginServiceBase.value,
      cbConnectSuccess: () => {
        pluginReady.value = true
        setMessage('本地插件服务连接成功')
        resolve()
      },
      cbConnectError: (error) => {
        reject(error instanceof Error ? error : new Error('连接本地插件服务失败'))
      },
      cbConnectClose: () => {
        pluginReady.value = false
        setMessage('本地插件服务连接已断开')
      }
    })

    webControl.connect().catch(reject)
  })

  await webControl.JS_StartService('window', {
    serviceBase: pluginServiceBase.value
  })
  await webControl.JS_CreateWnd(pluginContainerId, width, height)
}

async function initPlugin() {
  if (pluginAvailability.value !== 'available') {
    await checkPluginAvailability()
  }

  if (pluginAvailability.value !== 'available') {
    throw new Error('本地插件未安装或未启动，请先下载安装插件')
  }

  await createPluginWindow()

  const layoutParts = layout.value.split('x')
  const layoutValue = Number(layoutParts[0]) || 1

  await requestInterface('init', {
    serviceBase: pluginServiceBase.value,
    ffmpegExecutable: ffmpegExecutable.value,
    outputRoot: outputRoot.value,
    layout: layoutValue
  })

  setMessage('插件初始化完成')
}

async function resizePluginWindow() {
  if (!webControl || !pluginReady.value) {
    return
  }

  const { width, height } = getContainerRect()
  await webControl.JS_Resize(width, height)
}

async function handleStartPreview() {
  if (!rtspUrl.value) {
    errorMessage.value = '请先输入 RTSP 地址'
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    await initPlugin()
    const response = await requestInterface('startPreview', {
      rtspUrl: rtspUrl.value,
      rtspTransport: rtspTransport.value,
      hlsTimeSeconds: Number(hlsTimeSeconds.value),
      hlsListSize: Number(hlsListSize.value),
      startupTimeoutSeconds: Number(startupTimeoutSeconds.value),
      wndId: 1
    })

    currentRtspUrl.value = response.rtspUrl || rtspUrl.value
    currentPlayUrl.value = response.playUrl || ''
    currentStreamId.value = response.id || ''
    setMessage(response.message || '已发起本地插件预览')
  } catch (error) {
    currentPlayUrl.value = ''
    currentStreamId.value = ''
    errorMessage.value = error instanceof Error ? error.message : '插件预览失败'
  } finally {
    loading.value = false
  }
}

async function handleStopPreview() {
  errorMessage.value = ''

  try {
    if (webControl) {
      const response = await requestInterface('stopAllPreview')
      currentPlayUrl.value = ''
      currentStreamId.value = ''
      currentRtspUrl.value = ''
      setMessage(response.message || '已停止实时预览')
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '停止预览失败'
  }
}

async function handleReconnect() {
  loading.value = true
  errorMessage.value = ''

  try {
    disconnectControl()
    await initPlugin()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '重建插件窗口失败'
  } finally {
    loading.value = false
  }
}

function handleWindowResize() {
  window.clearTimeout(resizeTimer)
  resizeTimer = window.setTimeout(() => {
    resizePluginWindow().catch((error) => {
      errorMessage.value = error instanceof Error ? error.message : '调整插件窗口失败'
    })
  }, 120)
}

onMounted(() => {
  window.addEventListener('resize', handleWindowResize)
  checkPluginAvailability().catch(() => {})
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  window.clearTimeout(resizeTimer)
  disconnectControl()
})
</script>
