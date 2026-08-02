<template>
  <section class="knowledge-task-workspace">
    <header class="task-summary">
      <div class="task-summary__identity">
        <div class="task-summary__title">
          <h1>知识整理任务 #{{ task.conversationId ?? '—' }}</h1>
          <span class="task-status" :class="`task-status--${statusTone}`">{{ statusLabel(activeRun?.status) }}</span>
        </div>
        <p>{{ triggerLabel }} · {{ task.targetSkill ?? 'knowledge-curator' }} · 当前草稿 v{{ task.currentDraftRevision ?? 0 }}</p>
      </div>
      <div class="task-summary__metrics">
        <strong>模型 {{ activeRun?.modelCallCount ?? 0 }} 次 · 工具 {{ activeRun?.toolCallCount ?? 0 }} 次</strong>
        <span v-if="activeRun?.checkpointSavedAt">Checkpoint {{ formatTime(activeRun.checkpointSavedAt) }}</span>
        <span v-else>运行 #{{ activeRun?.runId ?? '—' }}</span>
      </div>
    </header>

    <div class="task-columns">
      <section data-testid="knowledge-task-conversation" class="task-panel task-conversation">
        <header class="task-panel__header">
          <div><IconGlyph name="message" /><h2>任务对话</h2></div>
          <span>消息、真实事件与产物变更按会话连续展示</span>
        </header>

        <div class="task-timeline">
          <details open data-testid="selected-draft-inputs" class="task-inputs">
            <summary>固定输入草稿 · {{ task.selectedDrafts?.length ?? 0 }} 份</summary>
            <ol>
              <li v-for="draft in task.selectedDrafts ?? []" :key="draft.documentId">
                <span class="task-inputs__index">{{ draft.documentId }}</span>
                <span><strong>{{ draft.title }}</strong><small>{{ draft.directory || '根目录' }}<template v-if="draft.originalFilename"> · {{ draft.originalFilename }}</template></small></span>
              </li>
            </ol>
          </details>

          <ol class="message-list">
            <li v-for="message in conversationMessages" :key="message.messageId" :class="`message-card message-card--${message.role.toLowerCase()}`">
              <header><strong>{{ messageLabel(message.role) }}</strong><time>{{ formatTime(message.createdAt) }}</time></header>
              <p>{{ message.content }}</p>
              <small v-if="message.subjectName">{{ message.subjectName }}</small>
            </li>
          </ol>

          <details v-if="findings.length" open data-testid="knowledge-task-findings" class="task-findings">
            <summary>整理发现 · {{ findings.length }} 项</summary>
            <ol class="finding-list">
              <li v-for="finding in findings" :key="finding.messageId">
                <span class="finding-type">{{ findingLabel(finding.type) }}</span>
                <strong>{{ finding.topic }}</strong>
                <p>{{ finding.summary }}</p>
                <small v-if="finding.recommendation">建议：{{ finding.recommendation }}</small>
                <small v-if="finding.humanQuestion">待人工确认：{{ finding.humanQuestion }}</small>
              </li>
            </ol>
          </details>

          <details data-testid="knowledge-task-process" class="task-process">
            <summary><span><IconGlyph name="settings" />处理过程 · {{ task.events?.length ?? activeRun?.stepCount ?? 0 }} 项</span><span>展开查看</span></summary>
            <ol>
              <li v-for="event in task.events ?? []" :key="event.sequence">
                <span class="event-icon"><IconGlyph :name="eventIcon(event.type)" /></span>
                <span><strong>{{ event.payload?.purpose || event.payload?.name || eventLabel(event.type) }}</strong><small>{{ event.payload?.resultSummary || event.payload?.status || eventLabel(event.type) }}</small></span>
              </li>
            </ol>
          </details>

          <div v-if="activeRun?.status === 'FAILED'" data-testid="knowledge-task-failure" class="task-failure" role="alert">
            <IconGlyph name="warning" />
            <div><strong>{{ failureTitle(activeRun.errorCode) }}</strong><p>{{ failureHint(activeRun.errorCode) }}</p><small>模型 {{ activeRun.modelCallCount ?? 0 }} 次 · 工具 {{ activeRun.toolCallCount ?? 0 }} 次 · {{ activeRun.errorCode || 'AGENT_MODEL_RESPONSE_INVALID' }}</small></div>
          </div>

          <div v-if="pauseRequested" class="task-notice">将在当前步骤完成后暂停</div>
        </div>

        <footer class="task-composer">
          <template v-if="activeRun?.status === 'WAITING_FOR_USER'">
            <p>最近暂停点：{{ formatTime(activeRun.checkpointSavedAt) }} · 已在安全步骤边界保存 Checkpoint，可加入指导后恢复。</p>
            <div class="task-composer__row">
              <textarea v-model="resumeGuidance" data-testid="resume-task-guidance" aria-label="暂停后指导" placeholder="例如：保留版本差异，并补充迁移风险" />
              <button data-testid="resume-task" type="button" :disabled="!resumeGuidance.trim()" @click="resume">恢复</button>
            </div>
          </template>
          <template v-else-if="activeRun && ['COMPLETED', 'FAILED'].includes(activeRun.status)">
            <p v-if="activeRun.status === 'FAILED'">本轮失败，但任务对话仍可继续；输入修正意见后会在当前草稿上创建新运行。</p>
            <p v-else>本轮已完成，但任务对话不会关闭；可继续提出修改意见并在当前草稿上创建新运行。</p>
            <div class="task-composer__row">
              <textarea v-model="followUp" data-testid="continue-task-guidance" aria-label="继续调整" :placeholder="activeRun.status === 'FAILED' ? '说明如何修正本轮错误' : '继续调整当前草稿'" />
              <button data-testid="continue-task" type="button" :disabled="!followUp.trim()" @click="continueTask">{{ activeRun.status === 'FAILED' ? '修正并重试' : '发送并继续' }}</button>
            </div>
          </template>
          <button
            v-else-if="activeRun && ['ACCEPTED', 'RUNNING'].includes(activeRun.status)"
            data-testid="request-task-pause"
            class="secondary-action"
            type="button"
            @click="requestPause"
          >请求暂停</button>
          <p v-else>对话消息不等于草稿产物</p>
        </footer>
      </section>

      <section data-testid="knowledge-task-artifact" class="task-panel task-artifact">
        <header class="task-panel__header">
          <div><IconGlyph name="lock" /><h2>待审核草稿</h2></div>
          <span class="revision-badge">当前修订 {{ task.currentDraftRevision ?? 0 }}</span>
        </header>

        <nav class="artifact-tabs" aria-label="草稿产物视图">
          <button type="button" disabled>预览</button><button class="active" type="button">Diff</button><button type="button" disabled>来源</button><button type="button" disabled>修订记录</button>
        </nav>

        <div class="artifact-body">
          <div class="artifact-title"><strong>{{ artifactTitle }}</strong><span>服务端生成</span></div>
          <div class="diff-summary">
            <span>+{{ diff?.additions ?? 0 }} 行</span><span>−{{ diff?.deletions ?? 0 }} 行</span>
            <strong>空/正式基线 → 草稿 v{{ diff?.toRevision ?? task.currentDraftRevision ?? 0 }}</strong>
          </div>
          <div data-testid="draft-markdown-diff" class="draft-diff-content">
            <span class="sr-only">+{{ diff?.additions ?? 0 }} / -{{ diff?.deletions ?? 0 }}</span>
            <pre>{{ diff?.unifiedDiff || '尚无可审核变更' }}</pre>
          </div>
          <p v-if="diff?.truncated" class="task-notice">Diff 已截断，请读取完整修订后再确认</p>
          <p v-if="publicationConflict" class="task-error">草稿已产生新修订，请重新查看 Diff</p>

          <section class="revision-section">
            <header><strong>修订记录</strong><span>发布锁定所选修订</span></header>
            <nav data-testid="draft-revision-list" aria-label="草稿修订">
              <button v-for="revision in revisions" :key="revision.revision" :class="{ active: revision.revision === task.currentDraftRevision }" type="button">
                <strong>v{{ revision.revision }}</strong><small>{{ revision.changeSummary }}</small>
              </button>
            </nav>
          </section>

          <details class="runtime-definition">
            <summary>本次运行定义（只读）</summary>
            <p>Skill {{ task.targetSkill ?? 'knowledge-curator' }}</p>
            <p>模型 {{ activeRun?.definition?.modelName ?? '服务端固定模型' }} · {{ activeRun?.definition?.toolNames?.length ?? 0 }} 个授权 Tool</p>
          </details>
        </div>

        <footer class="artifact-footer">
          <span>对话消息不等于草稿产物</span>
          <button
            data-testid="publish-reviewed-revision"
            type="button"
            :disabled="!canPublish"
            @click="$emit('publish', diff?.toRevision)"
          >发布 v{{ diff?.toRevision ?? task.currentDraftRevision ?? 0 }}</button>
        </footer>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import IconGlyph from './IconGlyph.vue'

interface RunDefinition { modelName?: string; toolNames?: string[] }
interface Run { runId: number; status: string; checkpointSavedAt?: string | null; stepCount?: number; modelCallCount?: number; toolCallCount?: number; errorCode?: string | null; definition?: RunDefinition }
interface Message { messageId: number; role: string; subjectName?: string | null; content: string; createdAt?: string }
interface Finding { messageId: number; type: string; topic: string; summary: string; recommendation?: string; humanQuestion?: string }
interface TaskEvent { sequence: number; type: string; payload?: { purpose?: string; name?: string; resultSummary?: string; status?: string } }
interface Task {
  conversationId?: number
  triggerType?: string
  targetSkill?: string
  goal: string
  selectedDrafts?: Array<{ documentId: number; title: string; directory: string; originalFilename?: string | null }>
  currentDraftRevision?: number | null
  messages: Message[]
  runs: Run[]
  events?: TaskEvent[]
}
interface Revision { revision: number; changeSummary: string; createdAt: string }
interface Diff { toRevision: number; unifiedDiff: string; additions: number; deletions: number; truncated: boolean }

const props = withDefaults(defineProps<{ task: Task; revisions: Revision[]; diff?: Diff | null; publicationConflict?: boolean; artifactTitle?: string }>(), {
  diff: null,
  publicationConflict: false,
  artifactTitle: '知识整理草稿',
})

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
const canPublish = computed(() => Boolean(props.diff && props.diff.toRevision > 0 && !props.publicationConflict))
const triggerLabel = computed(() => props.task.triggerType === 'SYSTEM' ? '系统触发' : '管理员手动触发')
const statusTone = computed(() => ({ COMPLETED: 'success', FAILED: 'danger', WAITING_FOR_USER: 'warning', PAUSE_REQUESTED: 'warning' } as Record<string, string>)[activeRun.value?.status ?? ''] ?? 'running')
const conversationMessages = computed(() => props.task.messages.filter(message => !message.subjectName?.startsWith('finding_record:')))
const findings = computed<Finding[]>(() => props.task.messages.flatMap(message => {
  if (!message.subjectName?.startsWith('finding_record:')) return []
  try {
    const value = JSON.parse(message.content) as Partial<Finding>
    if (!value.type || !value.topic || !value.summary) return []
    return [{ messageId: message.messageId, type: value.type, topic: value.topic, summary: value.summary, recommendation: value.recommendation, humanQuestion: value.humanQuestion }]
  } catch { return [] }
}))

watch(() => activeRun.value?.status, status => { if (status !== 'PAUSE_REQUESTED') pauseRequested.value = false })

function requestPause(): void { if (activeRun.value) { pauseRequested.value = true; emit('request-pause', activeRun.value.runId) } }
function resume(): void { if (activeRun.value && resumeGuidance.value.trim()) emit('resume', { runId: activeRun.value.runId, guidance: resumeGuidance.value.trim() }) }
function continueTask(): void { if (followUp.value.trim()) emit('continue-task', followUp.value.trim()) }
function messageLabel(role: string): string { return ({ SYSTEM_TRIGGER: '系统触发', USER: '你', COORDINATOR_AGENT: '知识整理 Agent', TOOL: '工具结果' } as Record<string, string>)[role] ?? role }
function findingLabel(type: string): string { return ({ DUPLICATE: '重复内容', CONFLICT: '规则冲突', STALE: '可能过期', GAP: '知识缺口' } as Record<string, string>)[type] ?? type }
function statusLabel(status?: string): string { return ({ ACCEPTED: '排队中', RUNNING: '运行中', PAUSE_REQUESTED: '请求暂停', WAITING_FOR_USER: '等待人工', COMPLETED: '已完成', FAILED: '失败', TERMINATED: '已终止', CANCELLED: '已取消' } as Record<string, string>)[status ?? ''] ?? '未运行' }
function eventLabel(type: string): string { return ({ RUN_STARTED: '运行开始', TOOL_STARTED: '工具开始', TOOL_COMPLETED: '工具完成', RUN_FAILED: '运行失败', RUN_COMPLETED: '运行完成' } as Record<string, string>)[type] ?? type }
function eventIcon(type: string): string { return type.includes('FAILED') ? 'warning' : type.includes('TOOL') ? 'settings' : type.includes('COMPLETED') ? 'circleCheck' : 'message' }
function formatTime(value?: string | null): string { return value ? new Date(value).toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '已保存' }
function failureTitle(code?: string | null): string { return code === 'AGENT_STEP_LIMIT_EXCEEDED' ? '工具调用达到知识整理上限' : code === 'AGENT_MODEL_CALL_LIMIT_EXCEEDED' ? '模型调用达到知识整理上限' : code === 'AGENT_RUN_TIMEOUT' ? '知识整理运行超时' : code === 'AGENT_TOOL_SCOPE_VIOLATION' ? '草稿写入范围校验失败' : '知识整理运行失败' }
function failureHint(code?: string | null): string { return code?.includes('LIMIT') ? '本轮保留了已完成的读取过程；可在下方补充约束后创建新运行。' : code === 'AGENT_TOOL_SCOPE_VIOLATION' ? 'Agent 选择了当前项目范围之外的正式知识基线，服务端已阻止写入；可在下方给出正确范围后重试。' : '已完成的输入和公开过程仍保留，可在下方说明修正方式并重试。' }
</script>

<style scoped>
.knowledge-task-workspace{display:flex;flex-direction:column;gap:14px;min-height:700px;color:var(--ink)}
.task-summary{min-height:54px;display:flex;align-items:center;justify-content:space-between;gap:20px}
.task-summary__title{display:flex;align-items:center;gap:9px}.task-summary h1{margin:0;font-size:18px;line-height:26px}.task-summary p,.task-summary__metrics span{margin:3px 0 0;color:var(--muted);font-size:11px}
.task-summary__metrics{display:flex;flex-direction:column;align-items:flex-end;gap:2px;font-size:11px}
.task-status,.revision-badge{border-radius:99px;padding:4px 8px;color:var(--accent);background:var(--accent-soft);font-size:11px;font-weight:650}.task-status--warning{color:var(--warning);background:var(--warning-soft)}.task-status--danger{color:var(--danger);background:var(--danger-soft)}
.task-columns{min-height:610px;display:grid;grid-template-columns:minmax(0,1fr) 444px;gap:16px}
.task-panel{min-width:0;min-height:0;display:flex;flex-direction:column;overflow:hidden;border:1px solid var(--border);border-radius:12px;background:var(--surface)}
.task-panel__header{height:48px;display:flex;align-items:center;justify-content:space-between;gap:12px;border-bottom:1px solid var(--border);padding:0 16px}.task-panel__header>div{display:flex;align-items:center;gap:8px}.task-panel__header .icon-glyph{width:15px;color:var(--accent)}.task-panel__header h2{margin:0;font-size:13px}.task-panel__header>span{color:var(--quiet);font-size:10px}
.task-timeline{flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column;gap:9px;padding:12px 16px}
details{border:1px solid var(--border);border-radius:9px;background:var(--surface)}summary{cursor:pointer;list-style:none;font-size:11px;font-weight:620}summary::-webkit-details-marker{display:none}
.task-inputs{padding:10px;background:var(--neutral-soft)}.task-inputs ol{display:grid;gap:7px;margin:9px 0 0;padding:0;list-style:none}.task-inputs li{display:flex;gap:9px;align-items:center}.task-inputs__index{width:25px;height:25px;display:grid;place-items:center;border-radius:7px;color:var(--accent);background:var(--accent-soft);font-size:10px}.task-inputs li>span:last-child{display:flex;flex-direction:column;gap:2px;font-size:11px}.task-inputs small{color:var(--quiet);font-size:10px}
.message-list{display:flex;flex-direction:column;gap:9px;margin:0;padding:0;list-style:none}.message-card{border-radius:9px;padding:10px;background:var(--neutral-soft)}.message-card--coordinator_agent{background:var(--accent-soft)}.message-card--user{margin-left:36px}.message-card header{display:flex;justify-content:space-between;color:var(--muted);font-size:11px}.message-card time{color:var(--quiet);font-size:10px}.message-card p{margin:5px 0 0;white-space:pre-wrap;font-size:12px;line-height:1.45}.message-card>small{display:block;margin-top:5px;color:var(--accent)}
.task-findings{padding:10px}.finding-list{display:grid;gap:8px;margin:9px 0 0;padding:0;list-style:none}.finding-list li{display:grid;grid-template-columns:auto 1fr;gap:4px 8px;border-top:1px solid var(--border);padding-top:8px}.finding-list li:first-child{border-top:0}.finding-type{grid-row:1/3;border-radius:99px;padding:3px 7px;align-self:start;color:var(--warning);background:var(--warning-soft);font-size:10px}.finding-list strong{font-size:11px}.finding-list p,.finding-list small{grid-column:2;margin:0;font-size:11px}.finding-list small{color:var(--muted)}
.task-process{padding:2px 10px}.task-process summary{height:34px;display:flex;align-items:center;justify-content:space-between}.task-process summary>span{display:flex;align-items:center;gap:7px}.task-process summary>span:last-child{color:var(--quiet);font-size:10px;font-weight:400}.task-process .icon-glyph{width:13px}.task-process ol{margin:0;padding:0;list-style:none}.task-process li{display:flex;gap:9px;border-top:1px solid var(--border);padding:8px 0}.event-icon{width:27px;height:27px;display:grid;place-items:center;border-radius:8px;background:var(--neutral-soft)}.task-process li>span:last-child{display:flex;flex-direction:column;gap:2px}.task-process strong{font-size:11px}.task-process small{color:var(--muted);font-size:10px}
.task-failure{display:flex;gap:10px;border-radius:9px;padding:10px;color:var(--danger);background:var(--danger-soft)}.task-failure>.icon-glyph{width:17px}.task-failure p{margin:4px 0;color:var(--ink);font-size:11px}.task-failure small{font-size:10px}
.task-composer{border-top:1px solid var(--border);padding:10px 16px 12px}.task-composer p{margin:0 0 7px;color:var(--quiet);font-size:10px}.task-composer__row{display:flex;gap:8px}.task-composer textarea{flex:1;min-height:38px;max-height:82px;border:1px solid var(--border);border-radius:9px;padding:9px 12px;resize:vertical;font-size:12px}.task-composer button,.artifact-footer button{border:1px solid var(--ink);border-radius:8px;padding:9px 14px;color:#fff;background:var(--ink);font-size:12px;cursor:pointer}.task-composer .secondary-action{color:var(--ink);background:var(--surface)}button:disabled{opacity:.5;cursor:not-allowed}
.artifact-tabs{height:36px;display:flex;align-items:stretch;gap:18px;border-bottom:1px solid var(--border);padding:0 14px}.artifact-tabs button{border:0;border-bottom:2px solid transparent;padding:0;color:var(--muted);background:transparent;font-size:11px}.artifact-tabs button.active{border-bottom-color:var(--accent);color:var(--accent);font-weight:650}.artifact-tabs button:disabled{opacity:1}
.artifact-body{flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column;gap:9px;padding:10px 14px}.artifact-title,.diff-summary,.revision-section>header{display:flex;align-items:center;justify-content:space-between}.artifact-title{font-size:12px}.artifact-title span,.revision-section>header span{color:var(--quiet);font-size:10px}.diff-summary{justify-content:flex-start;gap:10px;font-size:10px}.diff-summary span:first-child{color:var(--accent)}.diff-summary span:nth-child(2){color:var(--danger)}.diff-summary strong{margin-left:auto;color:var(--muted)}
.draft-diff-content{min-height:280px;flex:1;overflow:auto;border:1px solid var(--border);border-radius:8px;background:#fafafa}.draft-diff-content pre{min-height:100%;margin:0;padding:10px;white-space:pre-wrap;color:var(--ink);font-family:"Geist Mono",monospace;font-size:11px;line-height:1.55}
.task-notice,.task-error{margin:0;border-radius:8px;padding:9px;color:var(--warning);background:var(--warning-soft);font-size:11px}.task-error{color:var(--danger);background:var(--danger-soft)}
.revision-section{display:flex;flex-direction:column;gap:6px}.revision-section>header{font-size:11px}.revision-section nav{display:flex;gap:6px;overflow:auto}.revision-section button{min-width:104px;display:flex;flex-direction:column;gap:2px;border:0;border-radius:7px;padding:7px;text-align:left;background:var(--neutral-soft)}.revision-section button.active{color:var(--accent);background:var(--accent-soft)}.revision-section small{max-width:95px;overflow:hidden;color:var(--muted);font-size:9px;text-overflow:ellipsis;white-space:nowrap}
.runtime-definition{padding:9px;background:var(--neutral-soft)}.runtime-definition p{margin:5px 0 0;color:var(--muted);font-size:10px}
.artifact-footer{height:48px;display:flex;align-items:center;justify-content:space-between;border-top:1px solid var(--border);padding:0 14px}.artifact-footer span{color:var(--quiet);font-size:10px}
@media(max-width:1100px){.task-columns{grid-template-columns:minmax(0,1fr) 390px}}
</style>
