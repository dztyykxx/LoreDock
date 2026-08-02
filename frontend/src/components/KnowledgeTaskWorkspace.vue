<template>
  <section class="knowledge-task-workspace">
    <div data-testid="knowledge-task-conversation" class="knowledge-task-panel knowledge-task-conversation">
      <header><div><small>知识任务</small><h2>对话与过程</h2></div><span>{{ activeRun?.status ?? '未运行' }}</span></header>
      <p class="artifact-boundary">对话消息不等于草稿产物</p>
      <details open data-testid="selected-draft-inputs">
        <summary>固定输入草稿 · {{ task.selectedDrafts?.length ?? 0 }} 份</summary>
        <ol>
          <li v-for="draft in task.selectedDrafts ?? []" :key="draft.documentId">
            <strong>{{ draft.title }}</strong>
            <span>{{ draft.directory || '根目录' }}<template v-if="draft.originalFilename"> · {{ draft.originalFilename }}</template></span>
          </li>
        </ol>
      </details>
      <ol class="message-list">
        <li v-for="message in conversationMessages" :key="message.messageId">
          <strong>{{ messageLabel(message.role) }}</strong>
          <span v-if="message.subjectName"> · {{ message.subjectName }}</span>
          <p>{{ message.content }}</p>
        </li>
      </ol>
      <details v-if="findings.length" open data-testid="knowledge-task-findings">
        <summary>整理发现 · {{ findings.length }} 项</summary>
        <ol class="finding-list">
          <li v-for="finding in findings" :key="finding.messageId">
            <strong>{{ findingLabel(finding.type) }} · {{ finding.topic }}</strong>
            <p>{{ finding.summary }}</p>
            <small v-if="finding.recommendation">建议：{{ finding.recommendation }}</small>
            <small v-if="finding.humanQuestion">待人工确认：{{ finding.humanQuestion }}</small>
          </li>
        </ol>
      </details>
      <details data-testid="knowledge-task-process">
        <summary>运行过程 · {{ task.events?.length ?? activeRun?.stepCount ?? 0 }} 项</summary>
        <ol>
          <li v-for="event in task.events ?? []" :key="event.sequence">
            {{ event.payload?.purpose || event.payload?.name || event.type }}
            <span>{{ event.payload?.resultSummary || event.payload?.status }}</span>
          </li>
        </ol>
      </details>

      <div v-if="pauseRequested" class="task-notice">将在当前步骤完成后暂停</div>
      <button
        v-if="activeRun && ['ACCEPTED', 'RUNNING'].includes(activeRun.status)"
        data-testid="request-task-pause"
        type="button"
        @click="requestPause"
      >暂停任务</button>

      <div v-if="activeRun?.status === 'WAITING_FOR_USER'" class="guidance-box">
        <p>最近暂停点：{{ formatTime(activeRun.checkpointSavedAt) }}</p>
        <textarea v-model="resumeGuidance" data-testid="resume-task-guidance" aria-label="暂停后指导" />
        <button data-testid="resume-task" type="button" :disabled="!resumeGuidance.trim()" @click="resume">继续运行</button>
      </div>

      <div v-if="activeRun?.status === 'COMPLETED'" class="guidance-box">
        <textarea v-model="followUp" data-testid="continue-task-guidance" aria-label="继续调整" />
        <button data-testid="continue-task" type="button" :disabled="!followUp.trim()" @click="continueTask">继续调整</button>
      </div>
    </div>

    <div data-testid="knowledge-task-artifact" class="knowledge-task-panel knowledge-task-artifact">
      <header><div><small>待审核产物</small><h2>{{ artifactTitle }}</h2></div><strong>当前修订 {{ task.currentDraftRevision ?? 0 }}</strong></header>
      <nav data-testid="draft-revision-list" aria-label="草稿修订">
        <button v-for="revision in revisions" :key="revision.revision" type="button">
          修订 {{ revision.revision }}<small>{{ revision.changeSummary }}</small>
        </button>
      </nav>
      <div class="diff-summary">
        <span>+{{ diff?.additions ?? 0 }}</span><span>-{{ diff?.deletions ?? 0 }}</span>
        <strong>审核修订 {{ diff?.toRevision ?? task.currentDraftRevision ?? 0 }}</strong>
      </div>
      <div data-testid="draft-markdown-diff" class="draft-diff-content">
        <span class="diff-count">+{{ diff?.additions ?? 0 }} / -{{ diff?.deletions ?? 0 }}</span>
        <pre>{{ diff?.unifiedDiff || '尚无可审核变更' }}</pre>
      </div>
      <p v-if="diff?.truncated" class="task-notice">Diff 已截断，请读取完整修订后再确认</p>
      <p v-if="publicationConflict" class="task-error">草稿已产生新修订，请重新查看 Diff</p>
      <button
        data-testid="publish-reviewed-revision"
        type="button"
        :disabled="publicationConflict || !diff"
        @click="$emit('publish', diff?.toRevision)"
      >发布已审核修订</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

interface Run { runId: number; status: string; checkpointSavedAt?: string | null; stepCount?: number }
interface Message { messageId: number; role: string; subjectName?: string | null; content: string }
interface Finding {
  messageId: number
  type: string
  topic: string
  summary: string
  recommendation?: string
  humanQuestion?: string
}
interface TaskEvent { sequence: number; type: string; payload?: { purpose?: string; name?: string; resultSummary?: string; status?: string } }
interface Task {
  goal: string
  selectedDrafts?: Array<{ documentId: number; title: string; directory: string; originalFilename?: string | null }>
  currentDraftRevision?: number | null
  messages: Message[]
  runs: Run[]
  events?: TaskEvent[]
}
interface Revision { revision: number; changeSummary: string; createdAt: string }
interface Diff { toRevision: number; unifiedDiff: string; additions: number; deletions: number; truncated: boolean }

const props = withDefaults(defineProps<{
  task: Task
  revisions: Revision[]
  diff?: Diff | null
  publicationConflict?: boolean
  artifactTitle?: string
}>(), { diff: null, publicationConflict: false, artifactTitle: '知识整理草稿' })

const emit = defineEmits<{
  (event: 'request-pause', runId: number): void
  (event: 'resume', value: { runId: number; guidance: string }): void
  (event: 'continue-task', guidance: string): void
  (event: 'publish', revision?: number): void
}>()
const pauseRequested = ref(false)
const resumeGuidance = ref('')
const followUp = ref('')
const activeRun = computed(() => props.task.runs.at(-1))
const conversationMessages = computed(() => props.task.messages.filter(
  message => !message.subjectName?.startsWith('finding_record:'),
))
const findings = computed<Finding[]>(() => props.task.messages.flatMap(message => {
  if (!message.subjectName?.startsWith('finding_record:')) return []
  try {
    const value = JSON.parse(message.content) as Partial<Finding>
    if (!value.type || !value.topic || !value.summary) return []
    return [{
      messageId: message.messageId,
      type: value.type,
      topic: value.topic,
      summary: value.summary,
      recommendation: value.recommendation,
      humanQuestion: value.humanQuestion,
    }]
  } catch {
    return []
  }
}))

watch(() => activeRun.value?.status, status => {
  if (status !== 'PAUSE_REQUESTED') pauseRequested.value = false
})

function requestPause(): void {
  if (!activeRun.value) return
  pauseRequested.value = true
  emit('request-pause', activeRun.value.runId)
}

function resume(): void {
  if (!activeRun.value || !resumeGuidance.value.trim()) return
  emit('resume', { runId: activeRun.value.runId, guidance: resumeGuidance.value.trim() })
}

function continueTask(): void {
  if (!followUp.value.trim()) return
  emit('continue-task', followUp.value.trim())
}

function messageLabel(role: string): string {
  return ({ SYSTEM_TRIGGER: '系统触发', USER: '你', COORDINATOR_AGENT: '知识整理 Agent', TOOL: '工具结果' } as Record<string, string>)[role] ?? role
}

function findingLabel(type: string): string {
  return ({ DUPLICATE: '重复内容', CONFLICT: '规则冲突', STALE: '可能过期', GAP: '知识缺口' } as Record<string, string>)[type] ?? type
}

function formatTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '已保存'
}
</script>

<style scoped>
.knowledge-task-workspace{display:grid;grid-template-columns:minmax(320px,.92fr) minmax(420px,1.08fr);gap:20px;min-height:640px;color:#243247}.knowledge-task-panel{background:#fff;border:1px solid #dfe5ee;border-radius:16px;padding:22px;box-shadow:0 8px 28px rgba(30,45,70,.06)}header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;border-bottom:1px solid #edf0f5;padding-bottom:16px}h2{font-size:20px;margin:4px 0}small{color:#738096}.artifact-boundary,.task-notice{background:#fff7e8;color:#8a5a0a;border:1px solid #f1d8a5;border-radius:10px;padding:10px 12px}.message-list{list-style:none;padding:0;margin:18px 0}.message-list li{border-left:3px solid #6b7fd7;padding:3px 0 3px 14px;margin:16px 0}.message-list p,.finding-list p{white-space:pre-wrap;margin:6px 0}.finding-list{display:grid;gap:12px;padding-left:22px}.finding-list li{padding-left:5px}.finding-list small{display:block;margin-top:5px}details{border:1px solid #e2e7ef;border-radius:10px;padding:12px;margin:16px 0}button{border:0;border-radius:9px;background:#344fc4;color:#fff;padding:10px 14px;cursor:pointer}button:disabled{opacity:.45;cursor:not-allowed}.guidance-box{display:grid;gap:10px;margin-top:16px}.guidance-box textarea{min-height:82px;border:1px solid #ccd4e0;border-radius:9px;padding:10px}.knowledge-task-artifact nav{display:flex;gap:8px;overflow:auto;margin:18px 0}.knowledge-task-artifact nav button{display:grid;gap:3px;min-width:110px;background:#f1f3f8;color:#263553}.diff-summary{display:flex;gap:12px;align-items:center}.diff-summary span:first-child{color:#16794b}.diff-summary span:nth-child(2){color:#b13a44}.diff-summary strong{margin-left:auto}.diff-count{display:inline-block;margin:10px 0;color:#16794b;font-weight:700}pre{min-height:340px;max-height:520px;overflow:auto;background:#111827;color:#e6edf7;border-radius:12px;padding:18px;white-space:pre-wrap}.task-error{color:#b42318;background:#fff0ee;padding:10px;border-radius:9px}@media(max-width:900px){.knowledge-task-workspace{grid-template-columns:1fr}}
</style>
