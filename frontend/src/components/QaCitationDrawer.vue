<template>
  <aside class="qa-citation-drawer" role="dialog" aria-modal="false" aria-labelledby="qa-citation-title" @keydown.esc="close">
    <header>
      <div>
        <p>本次回答依据</p>
        <h2 id="qa-citation-title">来源 {{ snapshot.citations.length }}</h2>
      </div>
      <button data-testid="close-citations" type="button" aria-label="关闭来源" @click="close">×</button>
    </header>

    <section class="qa-citation-scope" aria-label="固定查询范围">
      <strong>查询范围已锁定</strong>
      <span>{{ snapshot.scope.projectIdentifier }} / {{ snapshot.scope.branch }}</span>
      <code v-if="snapshot.scope.commit">{{ snapshot.scope.commit }}</code>
      <span v-else>当前分支无代码快照</span>
    </section>

    <ol class="qa-citation-list">
      <li v-for="citation in snapshot.citations" :key="`${citation.order}-${citation.sourceType}`" class="qa-citation-card">
        <span class="qa-citation-card__number">{{ citation.order }}</span>
        <div>
          <p class="qa-citation-card__kind">{{ citation.sourceType === 'KNOWLEDGE' ? '业务知识' : '当前代码快照' }}</p>
          <strong>{{ citationTitle(citation) }}</strong>
          <template v-if="citation.sourceType === 'KNOWLEDGE'">
            <span>适用范围：{{ scopeLabel(citation.scopeType) }}</span>
            <span>来源：{{ citation.knowledgeSourceType || '人工整理' }}</span>
            <a v-if="safeWikiUrl(citation.wikiUrl)" :href="safeWikiUrl(citation.wikiUrl) ?? undefined" target="_blank" rel="noopener noreferrer">打开公开 Wiki 来源</a>
            <span v-else-if="citation.wikiUrl">{{ citation.wikiUrl }}</span>
            <span v-if="citation.originalFilename">原文件：{{ citation.originalFilename }}</span>
          </template>
          <template v-else>
            <code>{{ citation.repositoryPath || '未提供相对路径' }}</code>
            <span>分支：{{ citation.branch || snapshot.scope.branch }}</span>
            <span>Commit：<code>{{ citation.commit || snapshot.scope.commit || '无活动快照' }}</code></span>
          </template>
          <time v-if="citation.sourceUpdatedAt" :datetime="citation.sourceUpdatedAt">版本时间：{{ formatTime(citation.sourceUpdatedAt) }}</time>
        </div>
      </li>
    </ol>
  </aside>
</template>

<script setup lang="ts">
import type { QaCitation, QaQuestion } from '../api/qa'

const props = defineProps<{ snapshot: QaQuestion; returnFocusTo?: HTMLElement | null }>()
const emit = defineEmits<{ close: [] }>()

function close(): void {
  emit('close')
  props.returnFocusTo?.focus()
}

function safeWikiUrl(value: string | null): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : null
  } catch {
    return null
  }
}

function citationTitle(citation: QaCitation): string {
  return citation.title || citation.repositoryPath || '未命名来源'
}

function scopeLabel(scope: string | null): string {
  return ({ GLOBAL: '通用', PROJECT: '当前项目', BRANCH: '当前分支' } as Record<string, string>)[scope ?? ''] ?? '运行时范围'
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>
