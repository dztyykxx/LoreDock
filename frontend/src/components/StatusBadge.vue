<template>
  <span class="status-badge" :class="`status-badge--${status.toLowerCase()}`">
    <IconGlyph :name="status === 'ENABLED' ? 'circleCheck' : 'warning'" />
    {{ status === 'ENABLED' ? '启用' : '停用' }}
  </span>
</template>

<script setup lang="ts">
import type { ProjectStatus } from '../api/types'
import IconGlyph from './IconGlyph.vue'

defineProps<{ status: ProjectStatus }>()
</script>
