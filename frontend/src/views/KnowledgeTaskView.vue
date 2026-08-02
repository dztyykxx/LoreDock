<template>
  <main class="knowledge-task-page">
    <header class="task-page-header">
      <RouterLink :to="`/projects/${identifier}/drafts`">← 返回草稿</RouterLink>
      <div><small>{{ identifier }}</small><h1>知识整理任务</h1></div>
    </header>
    <p v-if="loading" class="page-state">正在读取知识任务…</p>
    <p v-else-if="error" class="page-state page-state--error" role="alert">{{ error }}</p>
    <KnowledgeTaskWorkspace
      v-else-if="task"
      :task="task"
      :revisions="revisions"
      :diff="diff"
      :publication-conflict="publicationConflict"
      :artifact-title="revision?.title"
      @request-pause="pause"
      @resume="resume"
      @continue-task="continueTask"
      @publish="publish"
    />
  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ApiError } from '../api/http'
import { knowledgeTaskApi, type DraftDiff, type DraftRevision, type KnowledgeTask } from '../api/knowledgeTasks'
import KnowledgeTaskWorkspace from '../components/KnowledgeTaskWorkspace.vue'

const route = useRoute()
const identifier = String(route.params.identifier)
const conversationId = Number(route.params.conversationId)
const task = ref<KnowledgeTask | null>(null)
const revision = ref<DraftRevision | null>(null)
const revisions = ref<Array<{ revision: number; changeSummary: string; createdAt: string }>>([])
const diff = ref<DraftDiff | null>(null)
const loading = ref(true)
const error = ref('')
const publicationConflict = ref(false)
let pollTimer: number | undefined
let polling = false

function runIsActive(): boolean {
  const status = task.value?.runs.at(-1)?.status
  return status !== undefined && ['ACCEPTED', 'RUNNING', 'PAUSE_REQUESTED'].includes(status)
}

function schedulePoll(): void {
  if (!runIsActive() || pollTimer !== undefined) return
  pollTimer = window.setTimeout(async () => {
    pollTimer = undefined
    if (polling) return schedulePoll()
    polling = true
    await refresh()
    polling = false
    schedulePoll()
  }, 1200)
}

async function load(): Promise<void> {
  task.value = await knowledgeTaskApi.detail(identifier, conversationId)
  if (task.value.currentDraftId && task.value.currentDraftRevision !== null) {
    revision.value = await knowledgeTaskApi.revision(
      identifier, conversationId, task.value.currentDraftId, task.value.currentDraftRevision)
    revisions.value = await knowledgeTaskApi.revisions(identifier, conversationId, task.value.currentDraftId)
    diff.value = await knowledgeTaskApi.diff(
      identifier, conversationId, task.value.currentDraftId, null, task.value.currentDraftRevision)
  }
}

async function refresh(): Promise<void> {
  try { await load() } catch { error.value = '知识任务刷新失败，请稍后重试。' }
}

async function pause(runId: number): Promise<void> {
  await knowledgeTaskApi.pause(identifier, conversationId, runId)
  await refresh()
}

async function resume(value: { runId: number; guidance: string }): Promise<void> {
  await knowledgeTaskApi.resume(identifier, conversationId, value.runId, value.guidance)
  await refresh()
  schedulePoll()
}

async function continueTask(guidance: string): Promise<void> {
  await knowledgeTaskApi.continueTask(identifier, conversationId, guidance)
  await refresh()
  schedulePoll()
}

async function publish(reviewedRevision?: number): Promise<void> {
  if (!task.value?.currentDraftId || reviewedRevision === undefined) return
  publicationConflict.value = false
  try {
    await knowledgeTaskApi.publish(identifier, conversationId, task.value.currentDraftId, reviewedRevision)
    await refresh()
  } catch (failure) {
    if (failure instanceof ApiError && failure.code === 'KNOWLEDGE_DRAFT_CONFLICT') {
      publicationConflict.value = true
      return
    }
    error.value = '草稿发布失败，请稍后重试。'
  }
}

onMounted(async () => {
  try { await load(); schedulePoll() } catch { error.value = '无法打开知识任务。' } finally { loading.value = false }
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
})
</script>

<style scoped>
.knowledge-task-page{min-height:100vh;background:#f4f6fa;padding:24px 32px}.task-page-header{display:flex;gap:28px;align-items:center;max-width:1500px;margin:0 auto 20px}.task-page-header a{color:#344fc4;text-decoration:none}.task-page-header h1{margin:2px 0;font-size:25px}.knowledge-task-page>:deep(.knowledge-task-workspace){max-width:1500px;margin:auto}.page-state{max-width:1500px;margin:40px auto;background:#fff;padding:24px;border-radius:12px}.page-state--error{color:#b42318}@media(max-width:700px){.knowledge-task-page{padding:16px}}
</style>
