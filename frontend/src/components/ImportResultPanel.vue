<template>
  <section class="import-result-panel" aria-live="polite">
    <header>
      <div><h2>导入结果</h2><p>{{ batch.originalFilename }}</p></div>
      <strong>{{ batch.status === 'PARTIAL' ? '部分成功' : batch.status === 'COMPLETED' ? '导入完成' : '导入失败' }}</strong>
    </header>
    <div class="import-result-panel__counts">
      <span>成功 {{ batch.succeededCount }}</span>
      <span>失败 {{ batch.failedCount }}</span>
      <span>忽略 {{ batch.ignoredCount }}</span>
    </div>
    <section v-for="group in groups" :key="group.status" :aria-labelledby="`import-${group.status}`">
      <h3 :id="`import-${group.status}`">{{ group.label }}</h3>
      <ul>
        <li v-for="item in group.items" :key="item.ordinal">
          <span><strong>{{ item.entryName }}</strong><small>{{ item.reason }} · {{ item.message }}</small></span>
          <button v-if="item.documentId" type="button" @click="$emit('openDocument', item.documentId)">查看文档</button>
        </li>
      </ul>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeImportBatch, KnowledgeImportItemStatus } from '../api/knowledge'

const props = defineProps<{ batch: KnowledgeImportBatch }>()
defineEmits<{ openDocument: [documentId: number] }>()

const labels: Record<KnowledgeImportItemStatus, string> = { SUCCEEDED: '成功条目', FAILED: '失败条目', IGNORED: '忽略条目' }
const groups = computed(() => (['SUCCEEDED', 'FAILED', 'IGNORED'] as const).map(status => ({
  status,
  label: labels[status],
  items: props.batch.items.filter(item => item.status === status),
})))
</script>
