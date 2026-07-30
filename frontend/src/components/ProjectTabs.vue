<template>
  <nav class="project-tabs" aria-label="项目模块">
    <button
      v-for="tab in DESIGN_SAMPLES.tabs"
      :key="tab.id"
      type="button"
      :data-tab="tab.id"
      :class="{ 'project-tabs__item--active': tab.id === active }"
      :aria-current="tab.id === active ? 'page' : undefined"
      :disabled="tab.id !== 'settings'"
    >
      {{ tab.label }}
      <span v-if="'count' in tab" :class="{ 'tab-count--warning': 'tone' in tab && tab.tone === 'warning' }">{{ tab.count }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import { DESIGN_SAMPLES } from '../designSamples'

defineProps<{ active: string }>()
</script>
