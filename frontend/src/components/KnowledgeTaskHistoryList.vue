<template>
  <section data-testid="knowledge-task-history" class="task-history-list" aria-label="知识任务历史">
    <RouterLink
      v-for="task in tasks"
      :key="task.conversationId"
      :data-conversation-id="task.conversationId"
      :to="`/projects/${projectIdentifier}/knowledge-tasks/${task.conversationId}`"
      class="task-history-item"
    >
      <span class="task-history-item__icon"><IconGlyph name="message" /></span>
      <span class="task-history-item__content">
        <span class="task-history-item__heading">
          <strong>{{ task.goal }}</strong>
          <span class="task-history-status" :class="`task-history-status--${statusTone(task.latestRunStatus)}`">{{ statusLabel(task.latestRunStatus) }}</span>
        </span>
        <span class="task-history-item__meta">
          {{ task.triggerType === 'SYSTEM' ? '系统触发' : '管理员触发' }} · {{ task.selectedDraftCount }} 份输入 · {{ task.runCount }} 轮运行 · 更新于 {{ formatTime(task.updatedAt) }}
        </span>
        <span v-if="task.latestErrorCode" class="task-history-item__error">{{ task.latestErrorCode }}</span>
      </span>
      <span class="task-history-item__action">{{ actionLabel(task.latestRunStatus) }}<IconGlyph name="chevronRight" /></span>
    </RouterLink>
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { KnowledgeTaskRunStatus, KnowledgeTaskSummary } from '../api/knowledgeTasks'
import IconGlyph from './IconGlyph.vue'

defineProps<{ projectIdentifier: string; tasks: KnowledgeTaskSummary[] }>()

function statusLabel(status: KnowledgeTaskRunStatus): string {
  return ({ ACCEPTED: '排队中', RUNNING: '运行中', PAUSE_REQUESTED: '请求暂停', WAITING_FOR_USER: '等待人工', COMPLETED: '已完成', FAILED: '失败', TERMINATED: '已终止', CANCELLED: '已取消' } as Record<string, string>)[status]
}

function statusTone(status: KnowledgeTaskRunStatus): string {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'TERMINATED' || status === 'CANCELLED') return 'danger'
  if (status === 'WAITING_FOR_USER' || status === 'PAUSE_REQUESTED') return 'warning'
  return 'running'
}

function actionLabel(status: KnowledgeTaskRunStatus): string {
  if (status === 'COMPLETED') return '继续调整'
  if (status === 'WAITING_FOR_USER') return '继续并恢复'
  if (status === 'FAILED') return '修正并重试'
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
