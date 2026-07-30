<template>
  <span class="document-status-badge" :class="`document-status-badge--${status.toLowerCase()}`">
    {{ statusLabel }}
    <small v-if="syncLabel" :class="`document-sync--${syncStatus?.toLowerCase()}`">{{ syncLabel }}</small>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DocumentStatus, KnowledgeIndexSyncStatus } from '../api/knowledge'

const props = defineProps<{ status: DocumentStatus; syncStatus?: KnowledgeIndexSyncStatus }>()

const statusLabel = computed(() => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' })[props.status])
const syncLabel = computed(() => props.syncStatus ? ({
  NOT_APPLICABLE: '',
  NEVER_INDEXED: '尚未索引',
  PENDING: '索引待同步',
  STALE: '索引待同步',
  SYNCED: '索引已同步',
})[props.syncStatus] : '')
</script>
