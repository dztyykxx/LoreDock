<template>
  <span class="qa-trust-badge" :class="`qa-trust-badge--${state.toLowerCase()}`" role="status" :aria-label="`回答可信状态：${label}`">
    <IconGlyph :name="icon" />{{ label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { QaTrustState } from '../api/qa'
import IconGlyph from './IconGlyph.vue'

const props = defineProps<{ state: QaTrustState }>()
const label = computed(() => ({
  IN_PROGRESS: '正在核验来源',
  RELIABLE_ANSWER: '有可靠依据',
  SOURCE_CONFLICT: '来源存在冲突',
  INSUFFICIENT_EVIDENCE: '当前知识库没有足够依据',
  FAILED: '本次问答未完成',
})[props.state])
const icon = computed(() => props.state === 'RELIABLE_ANSWER' ? 'shield' : props.state === 'IN_PROGRESS' ? 'search' : 'warning')
</script>
