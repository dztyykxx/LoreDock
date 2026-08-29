<template>
  <section class="knowledge-task-workspace">
    <header class="task-summary">
      <div>
        <div class="task-summary__title">
          <h1>知识整理任务 #{{ task.conversationId }}</h1>
          <span class="status-pill" :class="`status-pill--${task.status.toLowerCase()}`">{{ taskStatusLabel(task.status) }}</span>
        </div>
        <p>{{ triggerLabel }} · {{ task.targetSkill || 'knowledge-curator' }} · {{ task.runs.length }} 轮运行</p>
      </div>
      <div class="task-summary__metrics">
        <strong>模型 {{ totalModelCalls }} 次 · 工具 {{ task.toolInvocations.length }} 次<template v-if="totalTokensKnown"> · 输入 {{ totalTokenLabel(totalInputTokens) }} / 输出 {{ totalTokenLabel(totalOutputTokens) }}</template></strong>
        <span>最近运行 {{ runStatusLabel(activeRun?.status) }}</span>
      </div>
    </header>

    <section data-testid="workspace-review-bar" class="review-bar">
      <div class="review-bar__count">
        <span class="review-bar__icon"><IconGlyph name="files" /></span>
        <span><strong>{{ changedDocuments.length }} 份待审核文档</strong><small>累计工作区 · +{{ totalAdditions }} / −{{ totalDeletions }} 行</small></span>
      </div>
      <div class="review-bar__documents">
        <button v-for="document in changedDocuments.slice(0, 3)" :key="document.draftId" type="button" @click="review(document.draftId)">
          <span>{{ document.operation === 'ADD' ? '新增' : '修改' }}</span>{{ document.title }} · v{{ document.currentRevision }}
        </button>
        <span v-if="changedDocuments.length > 3">另 {{ changedDocuments.length - 3 }} 份</span>
      </div>
      <div class="review-bar__actions">
        <button v-if="canCloseNoChange" class="button button--ghost" type="button" @click="$emit('close-no-change')">确认无变更</button>
        <button data-testid="review-workspace" class="button" type="button" :disabled="changedDocuments.length === 0" @click="review(changedDocuments[0]?.draftId)">审核全部变更</button>
        <button data-testid="publish-workspace" class="button button--primary" type="button" :disabled="!canPublish" @click="$emit('publish')">原子发布</button>
      </div>
    </section>

    <main data-testid="knowledge-task-conversation" class="conversation-shell">
      <header class="conversation-header">
        <div><IconGlyph name="message" /><h2>任务对话</h2></div>
        <span>只展示公开决策、工具事实和文档变更，不展示内部思维</span>
      </header>

      <div ref="timelineElement" class="timeline" @scroll="trackScroll">
        <details data-testid="selected-draft-inputs" class="input-card">
          <summary>固定输入 · {{ task.selectedDrafts.length }} 份草稿 <span>展开查看</span></summary>
          <ol>
            <li v-for="draft in task.selectedDrafts" :key="draft.documentId">
              <span>{{ draft.documentId }}</span><div><strong>{{ draft.title }}</strong><small>{{ draft.directory || '根目录' }}<template v-if="draft.originalFilename"> · {{ draft.originalFilename }}</template></small></div>
            </li>
          </ol>
        </details>

        <article v-for="message in unassignedMessages" :key="message.messageId" :class="messageClass(message.role)">
          <header><strong>{{ messageLabel(message.role) }}</strong><time>{{ formatTime(message.createdAt) }}</time></header>
          <p>{{ message.content }}</p>
        </article>

        <section v-for="(run, index) in task.runs" :key="run.runId" class="run-section">
          <div class="run-divider"><span>第 {{ index + 1 }} 轮</span><small>{{ runStatusLabel(run.status) }} · {{ formatTime(run.startedAt || run.acceptedAt) }}</small></div>

          <article v-for="message in userMessagesForRun(run.runId)" :key="message.messageId" :class="messageClass(message.role)">
            <header><strong>{{ messageLabel(message.role) }}</strong><time>{{ formatTime(message.createdAt) }}</time></header>
            <p>{{ message.content }}</p>
          </article>

          <details v-if="agentBlocksForRun(run).length" data-testid="run-process-group" class="run-process" :open="runIsInProgress(run)">
            <summary>
              <span><IconGlyph name="settings" /><strong>执行过程</strong><small>工具调用 {{ toolsForRun(run.runId).length }} 次</small></span>
              <small>{{ runIsInProgress(run) ? '运行中' : '已收起' }} · 展开查看</small>
            </summary>
            <div class="run-process__content">
              <template v-for="agent in agentBlocksForRun(run)" :key="agent.key">
                <details data-testid="agent-block" class="agent-block" :class="`agent-block--${agent.status.toLowerCase()}`" :open="agent.status === 'RUNNING' || agent.tools.length === 0">
                  <summary>
                    <span class="agent-block__head"><IconGlyph name="settings" /><strong>{{ agent.agentLabel }}</strong><em>{{ agent.phaseLabel }}</em></span>
                    <small class="agent-block__status">{{ agent.statusLabel }}<template v-if="agent.tools.length"> · 工具 {{ agent.tools.length }} 次</template><template v-if="agent.promptTokens != null"> · 输入 {{ agent.promptTokens }} / 输出 {{ agent.completionTokens }}</template></small>
                  </summary>
                  <div class="agent-block__body">
                    <p v-if="agent.summary" class="agent-block__summary">{{ agent.summary }}</p>
                    <div v-if="agent.tools.length" data-testid="tool-invocation-group" class="tool-list">
                      <details v-for="tool in agent.tools" :key="tool.invocationId" data-testid="tool-invocation" data-density="compact" class="tool-card">
                        <summary>
                          <span class="tool-card__icon"><IconGlyph name="settings" /></span>
                          <span class="tool-card__meta"><strong>{{ tool.purpose }}</strong><small>{{ tool.toolName }} · {{ toolStatusLabel(tool.status) }}</small></span>
                          <time>{{ duration(tool.durationMillis) }}</time>
                        </summary>
                        <div class="tool-card__details">
                          <label>调用参数 <em v-if="tool.argumentsTruncated">已截断</em></label><pre>{{ tool.arguments || '无公开参数' }}</pre>
                          <label>执行结果 <em v-if="tool.resultTruncated">已截断</em></label><pre>{{ tool.result || tool.resultSummary || '执行中' }}</pre>
                        </div>
                      </details>
                    </div>
                  </div>
                </details>
              </template>
              <template v-for="message in leftoverMessagesForRun(run)" :key="message.messageId">
                <article v-if="finding(message)" class="finding-card">
                  <span>{{ findingLabel(finding(message)!.type) }}</span>
                  <div><strong>{{ finding(message)!.topic }}</strong><p>{{ finding(message)!.summary }}</p><small v-if="finding(message)!.recommendation">建议：{{ finding(message)!.recommendation }}</small><small v-if="finding(message)!.humanQuestion">待确认：{{ finding(message)!.humanQuestion }}</small></div>
                </article>
              </template>
            </div>
          </details>

          <article v-if="finalMessageForRun(run)" data-testid="run-final-answer" :class="[messageClass(finalMessageForRun(run)!.role), 'message-card--final']">
            <header><strong>{{ messageLabel(finalMessageForRun(run)!.role) }}</strong><time>{{ formatTime(finalMessageForRun(run)!.createdAt) }}</time></header>
            <SafeMarkdown :source="finalMessageForRun(run)!.content" />
            <small>{{ finalMessageForRun(run)!.subjectName }}</small>
          </article>

          <article v-if="patchSet(run.runId)?.documents.length" data-testid="run-patch-set" class="patch-card">
            <header><div><IconGlyph name="files" /><strong>本轮文档变更</strong></div><span>+{{ patchSet(run.runId)?.additions }} / −{{ patchSet(run.runId)?.deletions }} 行</span></header>
            <button v-for="document in patchSet(run.runId)?.documents" :key="document.draftId" type="button" @click="review(document.draftId)">
              <span class="operation-badge">{{ document.operation === 'ADD' ? '新增' : '修改' }}</span>
              <span><strong>{{ document.title }}</strong><small>v{{ document.fromRevision }} → v{{ document.toRevision }}</small></span>
              <span class="line-count">+{{ document.additions }} / −{{ document.deletions }}</span>
              <IconGlyph name="chevronRight" />
            </button>
          </article>

          <article v-if="run.status === 'FAILED'" data-testid="knowledge-task-failure" class="failure-card">
            <IconGlyph name="warning" /><div><strong>{{ failureTitle(run.errorCode) }}</strong><p>{{ failureHint(run.errorCode) }}</p><small>模型 {{ run.modelCallCount }} 次 · 工具 {{ run.toolCallCount }} 次 · {{ run.errorCode }}</small></div>
          </article>
          <article v-else-if="['CANCELLED', 'TERMINATED'].includes(run.status) && patchSet(run.runId)?.documents.length" class="failure-card">
            <IconGlyph name="warning" /><div><strong>本轮未完成，部分修改已保留</strong><p>请开启新一轮核对并处理这些文档后再发布。</p></div>
          </article>
        </section>
      </div>

      <button v-if="hasUnseen" class="new-message" type="button" @click="scrollToLatest">有新消息 · 回到底部</button>

      <footer class="composer">
        <template v-if="task.status !== 'PROCESSING'">
          <div class="readonly-notice"><IconGlyph name="lock" /><span><strong>任务已结束，只读保留</strong><small>{{ taskStatusLabel(task.status) }}</small></span></div>
        </template>
        <template v-else>
          <textarea v-model="guidance" data-testid="continue-task-guidance" :disabled="runIsActive" :placeholder="runIsActive ? 'Agent 运行中，结束后可继续补充意见' : '继续说明要核对、保留或调整的内容…'" @keydown.meta.enter="continueTask" @keydown.ctrl.enter="continueTask" />
          <div class="composer__footer">
            <span>{{ runIsActive ? '运行中不接收新消息；停止后可继续' : 'Ctrl / ⌘ + Enter 发送并开启新一轮' }}</span>
            <button v-if="runIsActive" data-testid="stop-task-run" class="button button--danger" type="button" @click="$emit('stop', activeRun!.runId)">停止本轮</button>
            <button v-else data-testid="continue-task" class="button button--primary" type="button" :disabled="!guidance.trim()" @click="continueTask">发送并继续</button>
          </div>
        </template>
      </footer>
    </main>

    <div v-if="selectedDocument" class="drawer-layer" @click.self="$emit('close-diff')">
      <aside data-testid="diff-drawer" class="diff-drawer">
        <header>
          <div><span class="operation-badge">{{ selectedDocument.operation === 'ADD' ? '新增' : '修改' }}</span><h2>{{ selectedDocument.title }}</h2><small>{{ selectedDocument.directory || '根目录' }} · v{{ selectedDocument.currentRevision }}</small></div>
          <button aria-label="关闭 Diff" type="button" @click="$emit('close-diff')">×</button>
        </header>
        <div class="diff-drawer__summary"><span>+{{ selectedDiff?.additions ?? 0 }} 行</span><span>−{{ selectedDiff?.deletions ?? 0 }} 行</span><strong>{{ selectedDocument.operation === 'ADD' ? '空基线' : `正式知识 v${selectedDocument.baselineRevision}` }} → 工作区 v{{ selectedDocument.currentRevision }}</strong></div>
        <p v-if="diffLoading" class="drawer-state">正在生成服务端 Diff…</p>
        <div v-else-if="selectedDiff" class="diff-lines">
          <code v-for="(line, index) in diffLines" :key="index" :class="diffLineClass(line)">{{ line || ' ' }}</code>
        </div>
        <p v-else class="drawer-state">无法读取 Diff</p>
        <footer><span v-if="selectedDiff?.truncated">Diff 已截断，请收窄文档后重新审核</span><span v-else>服务端 Unified Diff · 只读</span></footer>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { DraftDiff, KnowledgeTask, KnowledgeTaskRun, ToolInvocation } from '../api/knowledgeTasks'
import IconGlyph from './IconGlyph.vue'
import SafeMarkdown from './SafeMarkdown'

const props = withDefaults(defineProps<{ task: KnowledgeTask; selectedDraftId?: number | null; selectedDiff?: DraftDiff | null; diffLoading?: boolean; publicationConflict?: boolean }>(), {
  selectedDraftId: null, selectedDiff: null, diffLoading: false, publicationConflict: false,
})
const emit = defineEmits<{
  (event: 'continue-task', guidance: string): void
  (event: 'stop', runId: number): void
  (event: 'review-document', draftId: number): void
  (event: 'close-diff'): void
  (event: 'publish'): void
  (event: 'close-no-change'): void
}>()

const guidance = ref('')
const timelineElement = ref<HTMLElement | null>(null)
const followsLatest = ref(true)
const hasUnseen = ref(false)
const activeRun = computed(() => props.task.runs.at(-1))
const runIsActive = computed(() => ['ACCEPTED', 'RUNNING', 'PAUSE_REQUESTED'].includes(activeRun.value?.status ?? ''))
const changedDocuments = computed(() => props.task.workspaceDocuments.filter(document => document.currentRevision > 0))
const selectedDocument = computed(() => props.task.workspaceDocuments.find(document => document.draftId === props.selectedDraftId))
const totalModelCalls = computed(() => props.task.runs.reduce((sum, run) => sum + run.modelCallCount, 0))
const totalInputTokens = computed(() => props.task.runs.reduce((sum, run) => sum + (run.inputTokens ?? 0), 0))
const totalOutputTokens = computed(() => props.task.runs.reduce((sum, run) => sum + (run.outputTokens ?? 0), 0))
/** 至少有一轮 run 记录了完整 token 用量才在汇总区展示，避免旧数据把 0 当真实值。 */
const totalTokensKnown = computed(() => props.task.runs.some(run => run.inputTokens != null && run.outputTokens != null))
const totalTokenLabel = (value: number) => value.toLocaleString('zh-CN')
const totalAdditions = computed(() => props.task.patchSets.reduce((sum, patch) => sum + patch.additions, 0))
const totalDeletions = computed(() => props.task.patchSets.reduce((sum, patch) => sum + patch.deletions, 0))
const canPublish = computed(() => props.task.status === 'PROCESSING' && activeRun.value?.status === 'COMPLETED' && changedDocuments.value.length > 0 && !props.publicationConflict)
const canCloseNoChange = computed(() => props.task.status === 'PROCESSING' && !runIsActive.value && changedDocuments.value.length === 0)
const triggerLabel = computed(() => props.task.triggerType === 'SYSTEM' ? '系统触发' : '管理员手动触发')
const unassignedMessages = computed(() => props.task.messages.filter(message => message.runId === null))
const diffLines = computed(() => (props.selectedDiff?.unifiedDiff ?? '').split('\n'))

type Message = KnowledgeTask['messages'][number]
type AgentBlock = { key: string; agent: string; agentLabel: string; phase: string; phaseLabel: string; status: string; statusLabel: string; summary: string | null; at: string; tools: ToolInvocation[]; promptTokens: number | null; completionTokens: number | null }
const STAGE_AGENT_LABELS: Record<string, string> = { coordinator: '调度 Agent', retriever: '检索 Agent', drafter: '协作 Agent', reviewer: '审查 Agent' }
const STAGE_PHASE_LABELS: Record<string, string> = { START: '识别任务', DECIDE: '决定下一步', FINISH: '汇总结果', RETRIEVE: '检索知识', DRAFT: '创建草稿', REVIEW: '审查草稿' }

/** §10.7：把公开 AGENT_STAGE 事件投影为按时间顺序排列的 Agent 块；每个 Agent 展示阶段摘要与其期间的工具调用。 */
function agentBlocksForRun(run: KnowledgeTaskRun): AgentBlock[] {
  const stages = props.task.events
    .filter(event => event.type === 'AGENT_STAGE' && event.runId === run.runId && event.payload?.name)
    .sort((left, right) => left.sequence - right.sequence)
    .filter((event, index, all) => index === 0 || event.sequence !== all[index - 1].sequence)
  const blocks = stages.map(event => {
    const agent = event.payload?.name ?? ''
    const status = event.payload?.status ?? ''
    return {
      key: `a-${run.runId}-${event.sequence}`,
      agent,
      agentLabel: STAGE_AGENT_LABELS[agent] ?? '协作 Agent',
      phase: event.payload?.phase ?? '',
      phaseLabel: STAGE_PHASE_LABELS[event.payload?.phase ?? ''] ?? event.payload?.phase ?? '',
      status,
      statusLabel: status === 'RUNNING' ? '运行中' : status === 'COMPLETED' ? '已完成' : status === 'FAILED' ? '失败' : status || '—',
      summary: event.payload?.summary ?? null,
      at: event.createdAt ?? '',
      tools: [] as ToolInvocation[],
      promptTokens: event.payload?.promptTokens ?? null,
      completionTokens: event.payload?.completionTokens ?? null,
    }
  })
  // 优先按后端记录的 agentNode 归组（工具运行中即可归属到正确的 Agent，不再受阶段事件时序影响）；
  // 旧数据无 agentNode 时退回“该工具开始时间之后第一个已完成阶段”的时间推断。
  for (const tool of toolsForRun(run.runId)) {
    const byAgent = tool.agentNode ? blocks.find(block => block.agent === tool.agentNode) : undefined
    const byTime = blocks.find(block => block.at && new Date(block.at).getTime() >= new Date(tool.startedAt).getTime())
    const target = byAgent ?? byTime ?? (blocks.length ? blocks[blocks.length - 1] : undefined)
    if (target) target.tools.push(tool)
  }
  for (const block of blocks) block.tools.sort((left, right) => left.sequence - right.sequence)
  return blocks
}

/** 仅保留非过程消息（知识缺口记录），供 Agent 块之外的独立卡片展示；SUB_AGENT/调度过程摘要由 Agent 块承载。 */
function leftoverMessagesForRun(run: KnowledgeTaskRun): Message[] {
  return props.task.messages.filter(message => message.runId === run.runId
      && message.subjectName?.startsWith('finding_record:'))
}

function toolsForRun(runId: number): ToolInvocation[] {
  return props.task.toolInvocations.filter(tool => tool.runId === runId)
}
function userMessagesForRun(runId: number): Message[] {
  return props.task.messages.filter(message => message.runId === runId && message.role === 'USER')
}
function finalMessageForRun(run: KnowledgeTaskRun): Message | null {
  return props.task.messages.filter(message => message.runId === run.runId
      && message.role === 'COORDINATOR_AGENT' && message.subjectName === run.definition.skillName).at(-1) ?? null
}
function runIsInProgress(run: KnowledgeTaskRun): boolean {
  return ['ACCEPTED', 'RUNNING', 'PAUSE_REQUESTED'].includes(run.status)
}
function patchSet(runId: number) { return props.task.patchSets.find(patch => patch.runId === runId) }
function review(draftId?: number): void { if (draftId) emit('review-document', draftId) }
function continueTask(): void { if (!runIsActive.value && guidance.value.trim()) { emit('continue-task', guidance.value.trim()); guidance.value = '' } }
function trackScroll(): void {
  const element = timelineElement.value
  if (!element) return
  followsLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 72
  if (followsLatest.value) hasUnseen.value = false
}
function scrollToLatest(): void {
  const element = timelineElement.value
  if (!element) return
  if (typeof element.scrollTo === 'function') element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' })
  else element.scrollTop = element.scrollHeight
  followsLatest.value = true
  hasUnseen.value = false
}
function messageClass(role: string): string { return `message-card message-card--${role.toLowerCase()}` }
function messageLabel(role: string): string { return ({ SYSTEM_TRIGGER: '系统', USER: '你', COORDINATOR_AGENT: '调度 Agent', SUB_AGENT: '协作 Agent', TOOL: '工具结果' } as Record<string, string>)[role] ?? role }
function finding(message: Message): { type: string; topic: string; summary: string; recommendation?: string; humanQuestion?: string } | null {
  if (!message.subjectName?.startsWith('finding_record:')) return null
  try {
    const value = JSON.parse(message.content)
    return value.type && value.topic && value.summary ? value : null
  } catch { return null }
}
function findingLabel(type: string): string { return ({ DUPLICATE: '重复', CONFLICT: '冲突', STALE: '可能过期', GAP: '知识缺口' } as Record<string, string>)[type] ?? type }
function taskStatusLabel(status: string): string { return ({ PROCESSING: '整理中', PUBLISHED: '已发布', CLOSED_NO_CHANGE: '无需变更', ABANDONED: '已放弃' } as Record<string, string>)[status] ?? status }
function runStatusLabel(status?: string): string { return ({ ACCEPTED: '排队中', RUNNING: '运行中', PAUSE_REQUESTED: '停止中', WAITING_FOR_USER: '等待结束', COMPLETED: '已完成', FAILED: '失败', TERMINATED: '已终止', CANCELLED: '已停止' } as Record<string, string>)[status ?? ''] ?? '未运行' }
function toolStatusLabel(status: string): string { return ({ STARTED: '执行中', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[status] ?? status }
function toolGroupStatus(tools: ToolInvocation[]): string { return tools.some(tool => tool.status === 'STARTED') ? '执行中' : tools.some(tool => tool.status === 'FAILED') ? '含失败' : '已完成' }
function duration(value: number | null): string { return value === null ? '执行中' : value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s` }
function formatTime(value?: string | null): string { return value ? new Date(value).toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '—' }
function diffLineClass(line: string): string { return line.startsWith('+') && !line.startsWith('+++') ? 'added' : line.startsWith('-') && !line.startsWith('---') ? 'deleted' : line.startsWith('@@') ? 'range' : 'context' }
function failureTitle(code?: string | null): string { return code === 'AGENT_STEP_LIMIT_EXCEEDED' ? '工具调用达到知识整理上限' : code === 'AGENT_MODEL_CALL_LIMIT_EXCEEDED' ? '模型调用达到知识整理上限' : code === 'AGENT_RUN_TIMEOUT' ? '知识整理运行超时' : code === 'AGENT_TOOL_SCOPE_VIOLATION' ? '工作区写入范围校验失败' : '知识整理运行失败' }
function failureHint(code?: string | null): string { return code?.includes('LIMIT') ? '已经提交的文档修订仍保留，可补充约束后开启新一轮。' : '本轮已结束，公开过程和已提交修订仍保留，可直接给出修正意见。' }

onMounted(() => { void nextTick(scrollToLatest) })
watch(() => props.task.lastEventSequence, async (current, previous) => {
  if (current === previous) return
  await nextTick()
  if (followsLatest.value) scrollToLatest()
  else hasUnseen.value = true
}, { flush: 'post' })
</script>

<style scoped>
.knowledge-task-workspace{position:relative;display:flex;flex-direction:column;gap:14px;min-height:720px;color:var(--ink)}
.task-summary,.review-bar,.conversation-header,.composer__footer,.patch-card header,.tool-card summary{display:flex;align-items:center;justify-content:space-between;gap:16px}.task-summary{min-height:54px}.task-summary__title{display:flex;align-items:center;gap:9px}.task-summary h1{margin:0;font-size:20px}.task-summary p,.task-summary__metrics span{margin:3px 0 0;color:var(--muted);font-size:11px}.task-summary__metrics{display:flex;flex-direction:column;align-items:flex-end;font-size:11px}.status-pill,.operation-badge{border-radius:99px;padding:4px 8px;color:var(--accent);background:var(--accent-soft);font-size:10px;font-weight:700}.status-pill--published{color:#087b58}.status-pill--abandoned{color:var(--danger);background:var(--danger-soft)}
.review-bar{position:sticky;top:0;z-index:5;min-height:66px;border:1px solid color-mix(in srgb,var(--accent) 28%,var(--border));border-radius:13px;padding:10px 12px;background:color-mix(in srgb,var(--surface) 95%,var(--accent-soft));box-shadow:0 5px 18px #1b37300d}.review-bar__count,.review-bar__count>span:last-child{display:flex;align-items:center;gap:9px}.review-bar__count>span:last-child{align-items:flex-start;flex-direction:column;gap:1px}.review-bar__count small{color:var(--muted);font-size:10px}.review-bar__icon,.tool-card__icon{width:32px;height:32px;display:grid;place-items:center;border-radius:9px;color:var(--accent);background:var(--accent-soft)}.review-bar__documents{min-width:0;flex:1;display:flex;gap:6px;overflow:hidden}.review-bar__documents button{max-width:190px;overflow:hidden;border:0;background:transparent;text-overflow:ellipsis;white-space:nowrap;color:var(--muted);font-size:10px;text-align:left;cursor:pointer}.review-bar__documents button span{margin-right:4px;color:var(--accent)}.review-bar__actions{display:flex;gap:7px}
.button{border:1px solid var(--border);border-radius:8px;padding:8px 12px;background:var(--surface);color:var(--ink);font-size:11px;font-weight:650;cursor:pointer}.button:disabled{opacity:.42;cursor:not-allowed}.button--primary{border-color:var(--ink);background:var(--ink);color:#fff}.button--danger{border-color:var(--danger);color:var(--danger);background:var(--danger-soft)}.button--ghost{background:transparent}
.conversation-shell{position:relative;width:min(780px,100%);height:min(760px,calc(100vh - 255px));min-height:620px;align-self:center;display:flex;flex-direction:column;border:1px solid var(--border);border-radius:14px;background:var(--surface);overflow:hidden}.conversation-header{min-height:52px;border-bottom:1px solid var(--border);padding:0 17px}.conversation-header>div{display:flex;align-items:center;gap:8px}.conversation-header .icon-glyph{width:16px;color:var(--accent)}.conversation-header h2{margin:0;font-size:14px}.conversation-header>span{color:var(--quiet);font-size:10px}.timeline{flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column;gap:10px;padding:14px 18px 20px}.new-message{position:absolute;z-index:3;right:18px;bottom:112px;border:1px solid var(--accent);border-radius:99px;padding:7px 11px;color:var(--accent);background:var(--surface);box-shadow:0 4px 12px #13211d1a;font-size:10px;cursor:pointer}.input-card,.tool-card{border:1px solid var(--border);border-radius:10px;background:var(--neutral-soft)}.input-card{padding:11px}.input-card summary,.tool-card summary{cursor:pointer;list-style:none;font-size:11px;font-weight:650}.input-card summary span{float:right;color:var(--quiet);font-weight:400}.input-card ol{display:grid;gap:7px;margin:10px 0 0;padding:0;list-style:none}.input-card li{display:flex;align-items:center;gap:9px}.input-card li>span{width:27px;height:27px;display:grid;place-items:center;border-radius:8px;color:var(--accent);background:var(--accent-soft);font-size:10px}.input-card li div{display:flex;flex-direction:column;font-size:11px}.input-card small{color:var(--quiet)}
.run-section{display:flex;flex-direction:column;gap:9px}.run-divider{display:flex;align-items:center;gap:9px;margin:5px 0;color:var(--quiet);font-size:10px}.run-divider::before,.run-divider::after{content:"";height:1px;flex:1;background:var(--border)}.run-divider span{color:var(--muted);font-weight:700}.message-card{border-radius:10px;padding:11px 12px;background:var(--neutral-soft)}.message-card--coordinator_agent,.message-card--sub_agent{margin-right:42px;border-left:2px solid var(--accent);background:var(--accent-soft)}.message-card--user{margin-left:72px}.message-card--final{margin-right:0}.message-card header{display:flex;justify-content:space-between;color:var(--muted);font-size:10px}.message-card time{color:var(--quiet)}.message-card p{margin:5px 0 0;white-space:pre-wrap;font-size:12px;line-height:1.55}.message-card>small{display:block;margin-top:5px;color:var(--accent);font-size:10px}.finding-card{display:flex;align-items:flex-start;gap:9px;border:1px solid color-mix(in srgb,var(--warning) 32%,var(--border));border-radius:10px;padding:10px;background:var(--warning-soft)}.finding-card>span{border-radius:99px;padding:3px 7px;color:var(--warning);background:var(--surface);font-size:10px;font-weight:700}.finding-card>div{display:flex;flex-direction:column;gap:3px}.finding-card strong{font-size:11px}.finding-card p{margin:0;font-size:11px}.finding-card small{color:var(--muted);font-size:10px}
.run-process{overflow:hidden;border:1px solid var(--border);border-radius:10px;background:var(--neutral-soft)}.run-process>summary{min-height:36px;display:flex;align-items:center;justify-content:space-between;padding:0 10px;cursor:pointer;list-style:none}.run-process>summary>span{display:flex;align-items:center;gap:7px;font-size:10px}.run-process>summary .icon-glyph{width:12px;color:var(--accent)}.run-process>summary small{color:var(--quiet);font-size:9px}.run-process__content{display:flex;flex-direction:column;gap:7px;border-top:1px solid var(--border);padding:8px}.run-process__content>.message-card{margin-left:0;margin-right:0}
.agent-block{overflow:hidden;border:1px solid var(--border);border-radius:10px;background:var(--surface)}.agent-block>summary{min-height:38px;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:0 11px;cursor:pointer;list-style:none;background:var(--neutral-soft)}.agent-block__head{display:flex;align-items:center;gap:7px}.agent-block__head .icon-glyph{width:13px;color:var(--accent)}.agent-block__head strong{font-size:11px}.agent-block__head em{font-style:normal;color:var(--muted);font-size:10px}.agent-block__status{flex:none;color:var(--quiet);font-size:9px}.agent-block__body{display:flex;flex-direction:column;gap:7px;border-top:1px solid var(--border);padding:9px 11px}.agent-block__summary{margin:0;color:var(--ink);font-size:11px;line-height:1.6}.agent-block--running>summary{background:color-mix(in srgb,var(--accent-soft) 60%,var(--surface))}.agent-block--running .agent-block__status{color:var(--accent)}.agent-block--failed .agent-block__status{color:var(--danger)}
.stage-strip{display:flex;flex-wrap:wrap;gap:6px}.stage-card{display:grid;grid-template-columns:auto auto;align-items:center;gap:2px 7px;border:1px solid var(--border);border-radius:8px;padding:5px 8px;background:var(--surface);font-size:10px}.stage-card__name{color:var(--accent);font-weight:700}.stage-card__phase{color:var(--ink)}.stage-card__status{grid-column:1/2;color:var(--quiet)}.stage-card__summary{grid-column:1/3;max-width:520px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--muted)}.stage-card--running{border-color:color-mix(in srgb,var(--accent) 40%,var(--border))}.stage-card--failed{border-color:color-mix(in srgb,var(--danger) 32%,var(--border))}.stage-card--failed .stage-card__status{color:var(--danger)}.message-card--final :deep(.markdown-preview){margin-top:6px;font-size:12px;line-height:1.62}.message-card--final :deep(.markdown-preview)>:first-child{margin-top:0}.message-card--final :deep(.markdown-preview)>:last-child{margin-bottom:0}.message-card--final :deep(.markdown-preview p),.message-card--final :deep(.markdown-preview ul),.message-card--final :deep(.markdown-preview ol),.message-card--final :deep(.markdown-preview blockquote),.message-card--final :deep(.markdown-preview pre){margin:7px 0}.message-card--final :deep(.markdown-preview ul),.message-card--final :deep(.markdown-preview ol){padding-left:20px}.message-card--final :deep(.markdown-preview h1),.message-card--final :deep(.markdown-preview h2),.message-card--final :deep(.markdown-preview h3),.message-card--final :deep(.markdown-preview h4),.message-card--final :deep(.markdown-preview h5),.message-card--final :deep(.markdown-preview h6){margin:12px 0 5px;line-height:1.35}.message-card--final :deep(.markdown-preview h1){font-size:17px}.message-card--final :deep(.markdown-preview h2){font-size:15px}.message-card--final :deep(.markdown-preview h3){font-size:13px}.message-card--final :deep(.markdown-preview code){border-radius:4px;padding:1px 4px;background:color-mix(in srgb,var(--surface) 72%,var(--border));font:10px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace}.message-card--final :deep(.markdown-preview pre){overflow:auto;border-radius:8px;padding:9px;background:var(--surface)}.message-card--final :deep(.markdown-preview pre code){padding:0;background:transparent}.message-card--final :deep(.markdown-preview blockquote){border-left:2px solid var(--accent);padding-left:9px;color:var(--muted)}.message-card--final :deep(.markdown-preview a){color:var(--accent)}
.tool-group{border:1px solid var(--border);border-radius:8px;padding:0 8px;background:var(--neutral-soft)}.tool-group>summary{min-height:32px;display:flex;align-items:center;justify-content:space-between;cursor:pointer;list-style:none}.tool-group>summary>span{display:flex;align-items:center;gap:6px;font-size:10px}.tool-group>summary .icon-glyph{width:12px;color:var(--accent)}.tool-group>summary small{color:var(--quiet);font-size:9px}.tool-group__list{display:grid;gap:4px;border-top:1px solid var(--border);padding:5px 0 7px}.tool-list{display:grid;gap:5px}.tool-card{padding:0 8px;border-radius:8px;background:var(--surface)}.tool-card summary{min-height:34px;gap:7px}.tool-card__icon{width:22px;height:22px;border-radius:6px}.tool-card__icon>.icon-glyph{width:12px}.tool-card__meta{min-width:0;flex:1;display:flex;align-items:baseline;gap:6px;white-space:nowrap}.tool-card__meta strong{overflow:hidden;text-overflow:ellipsis;font-size:10px}.tool-card summary small,.tool-card time{flex:none;color:var(--quiet);font-size:9px}.tool-card__details{display:grid;gap:6px;border-top:1px solid var(--border);padding:8px 0}.tool-card__details label{color:var(--muted);font-size:10px;font-weight:700}.tool-card__details em{color:var(--warning)}.tool-card pre{max-height:190px;overflow:auto;margin:0;border-radius:8px;padding:9px;background:var(--neutral-soft);white-space:pre-wrap;font-size:10px}
.patch-card{overflow:hidden;border:1px solid color-mix(in srgb,var(--accent) 32%,var(--border));border-radius:11px;background:var(--surface)}.patch-card header{padding:10px 12px;background:var(--accent-soft);font-size:11px}.patch-card header>div{display:flex;align-items:center;gap:7px}.patch-card header .icon-glyph{width:14px;color:var(--accent)}.patch-card header>span{color:var(--accent)}.patch-card button{width:100%;display:grid;grid-template-columns:auto 1fr auto 16px;align-items:center;gap:10px;border:0;border-top:1px solid var(--border);padding:10px 12px;background:transparent;text-align:left;cursor:pointer}.patch-card button>span:nth-child(2){display:flex;flex-direction:column;gap:2px}.patch-card button small{color:var(--quiet);font-size:10px}.line-count{color:var(--muted);font-size:10px}.patch-card button>.icon-glyph{width:14px;color:var(--quiet)}.failure-card{display:flex;gap:9px;border-radius:10px;padding:11px;color:var(--danger);background:var(--danger-soft)}.failure-card>.icon-glyph{width:17px}.failure-card p{margin:3px 0;color:var(--ink);font-size:11px}.failure-card small{font-size:10px}
.composer{position:sticky;bottom:0;border-top:1px solid var(--border);padding:12px 16px;background:var(--surface)}.composer textarea{width:100%;min-height:62px;box-sizing:border-box;border:1px solid var(--border);border-radius:10px;padding:11px 12px;resize:vertical;font:inherit;font-size:12px}.composer textarea:disabled{background:var(--neutral-soft)}.composer__footer{margin-top:7px}.composer__footer>span{color:var(--quiet);font-size:10px}.readonly-notice{display:flex;align-items:center;gap:9px;color:var(--muted)}.readonly-notice>.icon-glyph{width:17px}.readonly-notice span{display:flex;flex-direction:column;font-size:11px}.readonly-notice small{color:var(--quiet)}
.drawer-layer{position:fixed;z-index:40;inset:0;background:#13211d24}.diff-drawer{position:absolute;top:0;right:0;width:min(620px,46vw);height:100%;display:flex;flex-direction:column;background:var(--surface);box-shadow:-14px 0 36px #14211d26}.diff-drawer>header{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid var(--border);padding:18px}.diff-drawer>header>div{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:5px 8px}.diff-drawer h2{margin:0;font-size:15px}.diff-drawer header small{grid-column:2;color:var(--muted);font-size:10px}.diff-drawer header button{border:0;background:transparent;font-size:24px;cursor:pointer}.diff-drawer__summary{display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--border);padding:10px 18px;font-size:10px}.diff-drawer__summary span:first-child{color:#087b58}.diff-drawer__summary span:nth-child(2){color:var(--danger)}.diff-drawer__summary strong{margin-left:auto}.diff-lines{flex:1;overflow:auto;padding:14px 0;background:#fbfcfb;font:11px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace}.diff-lines code{display:block;min-height:17px;padding:0 18px;white-space:pre-wrap}.diff-lines .added{color:#087b58;background:#eaf8f1}.diff-lines .deleted{color:#a93232;background:#fff0f0}.diff-lines .range{color:#5668a8;background:#f1f3fb}.diff-drawer>footer{border-top:1px solid var(--border);padding:11px 18px;color:var(--muted);font-size:10px}.drawer-state{margin:auto;color:var(--muted)}
@media(max-width:1100px){.review-bar__documents{display:none}.diff-drawer{width:min(680px,72vw)}}
</style>
