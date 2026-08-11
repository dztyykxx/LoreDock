<template>
  <section data-testid="knowledge-task-history" class="task-history-list" aria-label="知识任务历史">
    <RouterLink
      v-for="task in tasks"
      :key="task.conversationId"
      :data-conversation-id="task.conversationId"
      :to="taskTarget(task.conversationId)"
      class="task-history-item"
    >
      <span class="task-history-item__icon"><IconGlyph name="message" /></span>
      <span class="task-history-item__content">
        <span class="task-history-item__heading">
          <strong>{{ task.goal }}</strong>
          <span class="task-history-status" :class="`task-history-status--${taskTone(task.status)}`">{{ taskStatusLabel(task.status) }}</span>
        </span>
        <span class="task-history-item__meta">
          {{ task.triggerType === 'SYSTEM' ? '系统触发' : '管理员触发' }} · {{ task.selectedDraftCount }} 份输入 · {{ task.workspaceDocumentCount ?? 0 }} 份待审核 · 最近一轮 {{ statusLabel(task.latestRunStatus) }} · 更新于 {{ formatTime(task.updatedAt) }}
        </span>
        <span v-if="task.latestErrorCode" class="task-history-item__error">{{ task.latestErrorCode }}</span>
      </span>
      <span class="task-history-item__action">{{ actionLabel(task.status, task.latestRunStatus) }}<IconGlyph name="chevronRight" /></span>
    </RouterLink>
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { KnowledgeTaskRunStatus, KnowledgeTaskStatus, KnowledgeTaskSummary } from '../api/knowledgeTasks'
import IconGlyph from './IconGlyph.vue'

const props = defineProps<{ projectIdentifier: string | null; tasks: KnowledgeTaskSummary[] }>()

/** 项目任务进入项目详情；全局知识任务（projectIdentifier 为空）进入通用知识页任务详情。 */
function taskTarget(conversationId: number): string {
  return props.projectIdentifier
    ? `/projects/${props.projectIdentifier}/knowledge-tasks/${conversationId}`
    : `/knowledge/knowledge-tasks/${conversationId}`
}

function statusLabel(status: KnowledgeTaskRunStatus | null): string {
  if (!status) return '未运行'
  return ({ ACCEPTED: '排队中', RUNNING: '运行中', PAUSE_REQUESTED: '请求暂停', WAITING_FOR_USER: '等待人工', COMPLETED: '已完成', FAILED: '失败', TERMINATED: '已终止', CANCELLED: '已取消' } as Record<string, string>)[status]
}

function taskStatusLabel(status?: KnowledgeTaskStatus): string {
  return ({ PROCESSING: '整理中', PUBLISHED: '已发布', CLOSED_NO_CHANGE: '无需变更', ABANDONED: '已放弃' } as Record<string, string>)[status ?? 'PROCESSING']
}

function taskTone(status?: KnowledgeTaskStatus): string {
  if (status === 'PUBLISHED' || status === 'CLOSED_NO_CHANGE') return 'success'
  if (status === 'ABANDONED') return 'danger'
  return 'running'
}

function actionLabel(taskStatus: KnowledgeTaskStatus | undefined, runStatus: KnowledgeTaskRunStatus | null): string {
  if (taskStatus && taskStatus !== 'PROCESSING') return '查看记录'
  if (runStatus === 'COMPLETED') return '继续调整'
  if (runStatus === 'FAILED' || runStatus === 'CANCELLED') return '修正并重试'
  return '查看任务'
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.task-history-list{overflow:hidden;border:1px solid var(--border);border-radius:12px;background:var(--surface)}
.task-history-item{min-height:88px;display:flex;align-items:center;gap:14px;border-bottom:1px solid var(--border);padding:14px 16px;color:var(--ink);text-decoration:none;transition:background .15s ease}.task-history-item:last-child{border-bottom:0}.task-history-item:hover{background:var(--neutral-soft)}
.task-history-item__icon{width:38px;height:38px;display:grid;flex:0 0 auto;place-items:center;border-radius:10px;color:var(--accent);background:var(--accent-soft)}.task-history-item__icon .icon-glyph{width:18px}
.task-history-item__content{min-width:0;display:flex;flex:1;flex-direction:column;gap:5px}.task-history-item__heading{display:flex;align-items:center;gap:9px}.task-history-item__heading strong{overflow:hidden;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.task-history-item__meta{color:var(--quiet);font-size:11px}.task-history-item__error{color:var(--danger);font-family:"Geist Mono",monospace;font-size:10px}
.task-history-status{flex:0 0 auto;border-radius:999px;padding:4px 8px;color:var(--accent);background:var(--accent-soft);font-size:10px;font-weight:650}.task-history-status--warning{color:var(--warning);background:var(--warning-soft)}.task-history-status--danger{color:var(--danger);background:var(--danger-soft)}
.task-history-item__action{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:11px;font-weight:600}.task-history-item__action .icon-glyph{width:14px}
@media(max-width:700px){.task-history-item{align-items:flex-start}.task-history-item__action{font-size:0}.task-history-item__meta{line-height:1.5}}
</style>
