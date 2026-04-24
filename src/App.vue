<template>
  <main class="page-shell">
    <section class="hero-card">
      <div class="hero-copy">
        <p class="eyebrow">Vue 3 Demo</p>
        <h1>监控视频播放演示</h1>
        <p class="hero-text">
          保留现有“RTSP 转 HLS”播放方式，同时新增一套“海康官方插件式”示例入口，
          方便对比两种浏览器侧播放链路。
        </p>
      </div>
      <div class="hero-tag">
        <span>RTSP 转 HLS</span>
        <span>海康 WebControl 插件</span>
        <span>同页切换演示</span>
      </div>
    </section>

    <section class="switcher-card">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-btn"
        :class="{ 'is-active': activeTab === tab.key }"
        type="button"
        @click="activeTab = tab.key"
      >
        <span>{{ tab.label }}</span>
        <small>{{ tab.description }}</small>
      </button>
    </section>

    <RtspPlayer v-if="activeTab === 'hls'" />
    <HikvisionPluginDemo v-else />
  </main>
</template>

<script setup>
import { ref } from 'vue'
import HikvisionPluginDemo from './components/HikvisionPluginDemo.vue'
import RtspPlayer from './components/RtspPlayer.vue'

const tabs = [
  {
    key: 'hls',
    label: 'HLS 播放',
    description: '复用当前 RTSP 转 HLS 页面'
  },
  {
    key: 'hikvision',
    label: '插件式 Demo',
    description: '仿海康调用模型，本地插件转 RTSP 为 HLS'
  }
]

const activeTab = ref('hls')
</script>
