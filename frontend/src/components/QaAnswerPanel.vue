<template>
  <article class="qa-answer" :aria-busy="snapshot.trustState === 'IN_PROGRESS' ? 'true' : 'false'">
    <header>
      <span class="qa-answer__avatar">L</span>
      <div><strong>LoreDock</strong><small>{{ phaseLabel }}</small></div>
      <QaTrustBadge :state="snapshot.trustState" />
    </header>

    <section v-if="visibleProcessEvents.length" class="qa-process">
      <button
        data-testid="toggle-process"
        class="qa-process__toggle"
        type="button"
        :aria-expanded="processOpen ? 'true' : 'false'"
        @click="processOpen = !processOpen"
      >
        <span><IconGlyph name="sparkle" />处理过程</span>
        <small>{{ processOpen ? '收起' : `${visibleProcessEvents.length} 个步骤` }}</small>
      </button>
      <ol v-if="processOpen" data-testid="qa-process-timeline" class="qa-process__timeline">
        <li v-for="event in visibleProcessEvents" :key="event.sequence">
          <span class="qa-process__dot" :class="`qa-process__dot--${event.subjectType.toLowerCase()}`" />
          <div>
            <div class="qa-process__event-heading">
              <strong>{{ eventTitle(event) }}</strong>
              <time :datetime="event.occurredAt">{{ formatEventTime(event.occurredAt) }}</time>
            </div>
            <p v-if="event.payload.resultSummary">{{ event.payload.resultSummary }}</p>
            <p v-if="event.payload.summary">
              <span v-if="event.payload.modelGenerated" class="qa-process__public-label">公开决策摘要</span>
              {{ event.payload.summary }}
            </p>
            <div v-if="event.payload.sources.length" class="qa-process__sources">
              <span v-for="source in event.payload.sources" :key="`${event.sequence}-${source.documentId}`" :title="source.title ?? undefined">
                {{ source.title || '未命名来源' }}
              </span>
            </div>
            <small v-if="event.payload.durationMillis !== null">耗时 {{ event.payload.durationMillis }} ms</small>
          </div>
        </li>
      </ol>
    </section>

    <div v-if="connectionState === 'interrupted'" class="qa-stream-warning" role="status">
      <IconGlyph name="warning" />连接已中断，正在从最后已接收位置重连；最终结果以服务端快照为准。
    </div>

    <template v-if="snapshot.trustState === 'IN_PROGRESS'">
      <p class="qa-answer__progress">{{ partialText || '正在检索当前范围内的已发布知识…' }}</p>
    </template>
    <template v-else-if="snapshot.trustState === 'FAILED'">
      <h3>{{ failureTitle }}</h3>
      <p>{{ failureDescription }}</p>
      <p v-if="snapshot.errorCode" data-testid="failure-code" class="qa-answer__failure-code">
        诊断代码：{{ snapshot.errorCode }}
      </p>
      <div class="qa-answer__actions">
        <button data-testid="retry-answer" type="button" @click="$emit('retry')">使用新运行重试</button>
        <a data-testid="browse-knowledge" :href="`/projects/${encodeURIComponent(snapshot.scope.projectIdentifier)}`">浏览已发布知识</a>
      </div>
    </template>
    <template v-else>
      <h3 v-if="snapshot.trustState === 'SOURCE_CONFLICT'">已发布文档之间存在冲突</h3>
      <h3 v-else-if="snapshot.trustState === 'INSUFFICIENT_EVIDENCE'">当前知识库没有足够依据</h3>
      <div class="qa-answer__body">{{ snapshot.resultText || refusalDescription }}</div>
      <div v-if="snapshot.answerBasis" class="qa-answer__basis">
        <span>已发布文档依据</span>
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
import { computed, ref } from 'vue'
import type { QaConnectionState } from '../qa/useProjectQa'
import type { QaProcessEvent, QaQuestion } from '../api/qa'
import IconGlyph from './IconGlyph.vue'
import QaTrustBadge from './QaTrustBadge.vue'

const props = defineProps<{
  snapshot: QaQuestion
  partialText: string
  connectionState: QaConnectionState
  processEvents?: QaProcessEvent[]
}>()
const emit = defineEmits<{ retry: []; openCitations: [trigger: HTMLElement]; feedback: [] }>()
const processOpen = ref(false)
const visibleProcessEvents = computed(() => props.processEvents ?? props.snapshot.processEvents)

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
  props.snapshot.failureMessage
  ?? '本次运行未形成可信回答，请使用新运行重试。'
))

const refusalDescription = computed(() => (
  ({
    INSUFFICIENT_EVIDENCE: '当前范围内没有足以回答该问题的证据。',
    OUT_OF_SCOPE: '问题超出当前项目范围。',
    SOURCE_CONFLICT: '已发布文档之间存在冲突，无法安全选择其中一方。',
  } as Record<string, string>)[props.snapshot.refusalReason ?? '']
  ?? '本次运行未形成可公开回答。'
))

function eventTitle(event: QaProcessEvent): string {
  if (event.type === 'PUBLIC_DECISION_SUMMARY') return '模型整理公开结论'
  if (event.type === 'CITATION_VALIDATION') return `引用校验${event.payload.status === 'PASSED' ? '通过' : ''}`
  if (event.type === 'SOURCE_DISCOVERED' || event.type === 'SOURCE_FOUND') return `发现 ${event.payload.count ?? event.payload.sources.length} 个来源`
  if (event.type === 'TOOL_STARTED') return `${event.payload.name || '工具'} 开始`
  if (event.type === 'TOOL_COMPLETED') return event.payload.name || '工具完成'
  if (event.type === 'MODEL_STARTED' || event.type === 'MODEL_STAGE') return '模型正在整理答案'
  if (event.type === 'RUN_ACCEPTED') return '问题已受理'
  if (event.type === 'RUN_STARTED') return '开始核验项目知识'
  if (event.type === 'RUN_COMPLETED') return '处理完成'
  if (event.type === 'RUN_FAILED' || event.type === 'RUN_TERMINATED') return '处理未完成'
  return event.payload.phase || '执行步骤'
}

function formatEventTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    .format(new Date(value))
}
</script>
