import Hls from 'hls.js'

function normalizeBaseUrl(baseUrl) {
  return (baseUrl || '').replace(/\/$/, '')
}

async function parseJsonResponse(response) {
  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json')
    ? await response.json()
    : { message: await response.text() }

  if (!response.ok) {
    throw new Error(data.message || '本地插件服务请求失败')
  }

  return data
}

export class RtspHlsWebControl {
  constructor(options = {}) {
    this.options = options
    this.serviceBase = normalizeBaseUrl(options.serviceBase || 'http://127.0.0.1:18080')
    this.videoElement = null
    this.containerElement = null
    this.hls = null
    this.currentStream = null
    this.connected = false
  }

  async connect() {
    try {
      await this.request('/health')
      this.connected = true
      this.options.cbConnectSuccess?.()
    } catch (error) {
      this.options.cbConnectError?.(error)
      throw error
    }
  }

  async JS_StartService(_mode, options = {}) {
    this.serviceBase = normalizeBaseUrl(options.serviceBase || this.serviceBase)
    return { code: 0, message: 'service started' }
  }

  async JS_CreateWnd(containerId, width, height) {
    const container = document.getElementById(containerId)
    if (!container) {
      throw new Error(`未找到插件容器：${containerId}`)
    }

    this.containerElement = container
    container.innerHTML = ''

    const video = document.createElement('video')
    video.className = 'video-panel'
    video.controls = true
    video.autoplay = true
    video.muted = true
    video.playsInline = true
    video.style.width = `${width}px`
    video.style.height = `${height}px`
    container.appendChild(video)

    this.videoElement = video
    return { code: 0, message: 'window created' }
  }

  async JS_RequestInterface({ funcName, argument }) {
    const payload = typeof argument === 'string' ? JSON.parse(argument || '{}') : (argument || {})

    switch (funcName) {
      case 'init':
        return this.request('/plugin/init', {
          method: 'POST',
          body: JSON.stringify(payload)
        })
      case 'startPreview':
        return this.startPreview(payload)
      case 'stopPreview':
        return this.stopPreview(payload.streamId || this.currentStream?.id)
      case 'stopAllPreview':
        return this.stopAllPreview()
      default:
        throw new Error(`暂不支持的插件接口：${funcName}`)
    }
  }

  async JS_Resize(width, height) {
    if (!this.videoElement) {
      return
    }

    this.videoElement.style.width = `${width}px`
    this.videoElement.style.height = `${height}px`
  }

  JS_HideWnd() {
    if (this.containerElement) {
      this.containerElement.style.visibility = 'hidden'
    }
  }

  async JS_Disconnect() {
    await this.stopAllPreview({ silent: true })
    this.destroyPlayer()
    if (this.containerElement) {
      this.containerElement.innerHTML = ''
      this.containerElement.style.visibility = ''
    }
    this.connected = false
    this.options.cbConnectClose?.()
  }

  async startPreview(payload) {
    const response = await this.request('/plugin/preview/start', {
      method: 'POST',
      body: JSON.stringify(payload)
    })

    await this.attachPlayUrl(response.playUrl)
    this.currentStream = response
    return response
  }

  async stopPreview(streamId) {
    if (!streamId) {
      this.destroyPlayer()
      this.currentStream = null
      return { message: '当前无活跃预览' }
    }

    const response = await this.request('/plugin/preview/stop', {
      method: 'POST',
      body: JSON.stringify({ streamId })
    })
    this.destroyPlayer()
    this.currentStream = null
    return response
  }

  async stopAllPreview(options = {}) {
    if (!this.currentStream && options.silent) {
      return { message: '当前无活跃预览' }
    }

    const response = await this.request('/plugin/preview/stop-all', {
      method: 'POST',
      body: JSON.stringify({})
    })
    this.destroyPlayer()
    this.currentStream = null
    return response
  }

  async attachPlayUrl(playUrl) {
    if (!this.videoElement) {
      throw new Error('插件窗口尚未创建')
    }

    const absoluteUrl = playUrl.startsWith('http')
      ? playUrl
      : `${this.serviceBase}${playUrl.startsWith('/') ? '' : '/'}${playUrl}`

    this.destroyPlayer()

    if (this.videoElement.canPlayType('application/vnd.apple.mpegurl')) {
      this.videoElement.src = absoluteUrl
    } else if (Hls.isSupported()) {
      this.hls = new Hls({
        lowLatencyMode: true
      })
      this.hls.loadSource(absoluteUrl)
      this.hls.attachMedia(this.videoElement)
    } else {
      throw new Error('当前浏览器不支持 HLS 播放')
    }

    await this.videoElement.play()
  }

  destroyPlayer() {
    if (this.hls) {
      this.hls.destroy()
      this.hls = null
    }

    if (!this.videoElement) {
      return
    }

    this.videoElement.pause()
    this.videoElement.removeAttribute('src')
    this.videoElement.load()
  }

  async request(pathname, options = {}) {
    const response = await fetch(`${this.serviceBase}${pathname}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers || {})
      }
    })

    return parseJsonResponse(response)
  }
}
