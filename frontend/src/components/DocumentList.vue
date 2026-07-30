<template>
  <div class="document-list">
    <button
      v-for="document in documents"
      :key="document.id"
      type="button"
      :class="{ 'document-list__item--selected': document.id === selectedId }"
      :aria-pressed="document.id === selectedId"
      @click="$emit('select', document.id)"
    >
      <span class="document-list__icon"><IconGlyph name="file" /></span>
      <span class="document-list__content">
        <strong>{{ document.title }}</strong>
        <small>{{ document.directory || '根目录' }} · 修订 {{ document.revision }} · {{ formatDate(document.updatedAt) }}</small>
        <span class="document-list__tags"><small v-for="tag in document.tags" :key="tag">{{ tag }}</small></span>
      </span>
      <DocumentStatusBadge :status="document.status" :sync-status="document.syncStatus" />
      <IconGlyph name="chevronRight" />
    </button>
  </div>
</template>

<script setup lang="ts">
import type { KnowledgeDocumentSummary } from '../api/knowledge'
import DocumentStatusBadge from './DocumentStatusBadge.vue'
import IconGlyph from './IconGlyph.vue'

defineProps<{ documents: KnowledgeDocumentSummary[]; selectedId: string | null }>()
defineEmits<{ select: [documentId: string] }>()

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
}
</script>
