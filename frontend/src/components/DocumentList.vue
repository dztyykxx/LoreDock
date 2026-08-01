<template>
  <div class="document-list">
    <article
      v-for="document in documents"
      :key="document.id"
      class="document-list__item"
      :class="{
        'document-list__item--selected': document.id === selectedId,
        'document-list__item--selectable': selectable,
      }"
    >
      <label v-if="selectable && document.status === 'DRAFT'" class="document-list__check" :aria-label="`选择 ${document.title}`">
        <input
          type="checkbox"
          :data-testid="`select-document-${document.id}`"
          :checked="selectedIds.includes(document.id)"
          @change="$emit('toggle', document.id)"
        >
      </label>
      <span v-else-if="selectable" class="document-list__check" aria-hidden="true" />
      <span class="document-list__icon"><IconGlyph name="file" /></span>
      <button type="button" class="document-list__open" :aria-pressed="document.id === selectedId" @click="$emit('select', document.id)">
        <span class="document-list__content">
          <strong>{{ document.title }}</strong>
          <small>{{ document.directory || '根目录' }} · 修订 {{ document.revision }} · {{ formatDate(document.updatedAt) }}</small>
          <span class="document-list__tags"><small v-for="tag in document.tags" :key="tag">{{ tag }}</small></span>
        </span>
      </button>
      <DocumentStatusBadge :status="document.status" :sync-status="document.syncStatus" />
      <button type="button" class="document-list__chevron" :aria-label="`查看 ${document.title}`" @click="$emit('select', document.id)"><IconGlyph name="chevronRight" /></button>
    </article>
  </div>
</template>

<script setup lang="ts">
import type { KnowledgeDocumentSummary } from '../api/knowledge'
import DocumentStatusBadge from './DocumentStatusBadge.vue'
import IconGlyph from './IconGlyph.vue'

withDefaults(defineProps<{
  documents: KnowledgeDocumentSummary[]
  selectedId: number | null
  selectable?: boolean
  selectedIds?: number[]
}>(), { selectable: false, selectedIds: () => [] })
defineEmits<{ select: [documentId: number]; toggle: [documentId: number] }>()

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
}
</script>
