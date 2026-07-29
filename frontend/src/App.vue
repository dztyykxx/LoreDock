<template>
  <main class="shell">
    <p class="eyebrow">LoreDock</p>
    <h1>项目业务上下文知识平台</h1>
    <p class="status" :class="statusClass">{{ statusText }}</p>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchSystemStatus } from './systemStatus'

type Availability = 'checking' | 'available' | 'unavailable'

const availability = ref<Availability>('checking')

const statusText = computed(() => ({
  checking: '正在检查后端服务…',
  available: '后端服务可用',
  unavailable: '后端服务暂不可用',
})[availability.value])

const statusClass = computed(() => `status--${availability.value}`)

onMounted(async () => {
  try {
    const result = await fetchSystemStatus()
    availability.value = result.status === 'UP' ? 'available' : 'unavailable'
  } catch {
    // 状态页只展示安全的可用性提示，避免把网络错误或后端内部细节暴露给浏览器用户。
    availability.value = 'unavailable'
  }
})
</script>
