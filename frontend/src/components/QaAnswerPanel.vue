<template>
  <article class="qa-answer" :aria-busy="snapshot.trustState === 'IN_PROGRESS' ? 'true' : 'false'">
    <header>
      <span class="qa-answer__avatar">L</span>
      <div><strong>LoreDock</strong><small>{{ phaseLabel }}</small></div>
      <QaTrustBadge :state="snapshot.trustState" />
    </header>

    <div v-if="connectionState === 'interrupted'" class="qa-stream-warning" role="status">
      <IconGlyph name="warning" />连接已中断，正在从最后已接收位置重连；最终结果以服务端快照为准。
    </div>

    <template v-if="snapshot.trustState === 'IN_PROGRESS'">
      <p class="qa-answer__progress">{{ partialText || '正在检索当前范围内的知识与代码来源…' }}</p>
    </template>
    <template v-else-if="snapshot.trustState === 'FAILED'">
      <h3>{{ failureTitle }}</h3>
      <p>{{ failureDescription }}</p>
      <div class="qa-answer__actions">
        <button data-testid="retry-answer" type="button" @click="$emit('retry')">使用新运行重试</button>
        <a data-testid="browse-knowledge" :href="`/projects/${encodeURIComponent(snapshot.scope.projectIdentifier)}`">浏览已发布知识</a>
      </div>
    </template>
    <template v-else>
      <div v-if="snapshot.refusalReason === 'CODE_SNAPSHOT_NOT_INDEXED'" class="qa-snapshot-warning">
        <strong>当前分支代码尚未索引</strong>
        <span>{{ snapshot.scope.branch }} 没有可用于确认实现的活动快照。</span>
      </div>
      <h3 v-if="snapshot.trustState === 'SOURCE_CONFLICT'">知识说明与当前代码快照不一致</h3>
      <h3 v-else-if="snapshot.trustState === 'INSUFFICIENT_EVIDENCE'">当前知识库没有足够依据</h3>
      <div class="qa-answer__body">{{ snapshot.resultText || refusalDescription }}</div>
      <div v-if="snapshot.answerBasis" class="qa-answer__basis">
        <span v-if="snapshot.answerBasis === 'BUSINESS_RULE' || snapshot.answerBasis === 'MIXED'">业务设计原因</span>
        <span v-if="snapshot.answerBasis === 'CURRENT_IMPLEMENTATION' || snapshot.answerBasis === 'MIXED'">当前快照实现</span>
      </div>
    </template>

    <footer v-if="snapshot.trustState !== 'IN_PROGRESS' && snapshot.trustState !== 'FAILED'">
      <button type="button" data-testid="open-citations" @click="openCitations">
        <IconGlyph name="file" />来源 {{ snapshot.citations.length }}
      </button>
      <button type="button" data-testid="open-feedback" @click="$emit('feedback')">记录知识缺口</button>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { QaConnectionState } from '../qa/useProjectQa'
import type { QaQuestion } from '../api/qa'
import IconGlyph from './IconGlyph.vue'
import QaTrustBadge from './QaTrustBadge.vue'

const props = defineProps<{ snapshot: QaQuestion; partialText: string; connectionState: QaConnectionState }>()
const emit = defineEmits<{ retry: []; openCitations: [trigger: HTMLElement]; feedback: [] }>()

function openCitations(event: MouseEvent): void {
  emit('openCitations', event.currentTarget as HTMLElement)
}

const phaseLabel = computed(() => ({
  ACCEPTED: '问题已受理',
  RUNNING: '正在核验当前范围',
  COMPLETED: '已根据来源完成',
  FAILED: '运行失败',
  TERMINATED: '运行已终止',
})[props.snapshot.status])

const failureTitle = computed(() => props.snapshot.errorCode === 'AGENT_MODEL_UNAVAILABLE' ? '模型暂时不可用' : '本次问答未完成')
const failureDescription = computed(() => (
  ({
    AGENT_MODEL_UNAVAILABLE: '问答模型当前无法连接，项目文档与现有知识仍可正常浏览。',
    AGENT_RUNTIME_BUSY: '问答队列当前繁忙，请稍后使用新运行重试。',
    AGENT_RUN_TIMEOUT: '本次运行超过时间限制，未完成内容不会作为可信回答。',
  } as Record<string, string>)[props.snapshot.errorCode ?? '']
  ?? `运行未形成可信结果（${props.snapshot.errorCode ?? 'UNKNOWN'}）。`
))

const refusalDescription = computed(() => (
  ({
    INSUFFICIENT_EVIDENCE: '当前范围内没有足以回答该问题的证据。',
    OUT_OF_SCOPE: '问题超出当前项目与分支范围。',
    SOURCE_CONFLICT: '知识说明与当前实现存在冲突，无法安全选择其中一方。',
    CODE_SNAPSHOT_NOT_INDEXED: '无法根据当前分支确认实现事实。',
  } as Record<string, string>)[props.snapshot.refusalReason ?? '']
  ?? '本次运行未形成可公开回答。'
))
</script>
