<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main knowledge-task-main">
      <header v-if="!project" class="list-topbar">
        <div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>通用业务知识</strong></div>
      </header>
      <AppTopBar v-else :project-name="project.name" />
      <section class="knowledge-task-content">
        <template v-if="project">
          <ProjectHero
            :name="project.name"
            :identifier="project.identifier"
            :technology-stack="project.technologyStack"
          >
            <template #actions>
              <RouterLink :to="`/projects/${project.identifier}/knowledge-tasks`"><AppButton variant="secondary" icon="arrowLeft">返回任务列表</AppButton></RouterLink>
            </template>
          </ProjectHero>
        </template>
        <div v-else class="global-task-heading">
          <RouterLink to="/knowledge/knowledge-tasks"><AppButton variant="secondary" icon="arrowLeft">返回任务列表</AppButton></RouterLink>
        </div>
        <ProjectTabs
          active="tasks"
          :role="identity.role"
          :project-identifier="project?.identifier ?? ''"
          :project-id="project?.id ?? 0"
          :global="!project"
        />

        <p v-if="loading" class="task-page-state">正在读取知识任务…</p>
        <p v-else-if="error" class="task-page-state task-page-state--error" role="alert">{{ error }}</p>
        <KnowledgeTaskWorkspace
          v-else-if="task"
          :task="task"
          :selected-draft-id="selectedDraftId"
          :selected-diff="diff"
          :diff-loading="diffLoading"
          :publication-conflict="publicationConflict"
          @stop="stop"
          @continue-task="continueTask"
          @review-document="openDiff"
          @close-diff="closeDiff"
          @publish="publish"
          @close-no-change="closeNoChange"
        />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { knowledgeTaskApi, type DraftDiff, type KnowledgeTask } from '../api/knowledgeTasks'
import type { ProjectDetail } from '../api/types'
import { useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import IconGlyph from '../components/IconGlyph.vue'
import KnowledgeTaskWorkspace from '../components/KnowledgeTaskWorkspace.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'

const route = useRoute()
const router = useRouter()
const projects = useProjectApi()
const session = useSession()
const identity = computed(() => session.identity.value)
// identifier 为空表示全局知识任务详情（/knowledge/knowledge-tasks/:conversationId）。
const identifier = typeof route.params.identifier === 'string' ? route.params.identifier : null
const conversationId = Number(route.params.conversationId)
const project = ref<ProjectDetail | null>(null)
const task = ref<KnowledgeTask | null>(null)
const diff = ref<DraftDiff | null>(null)
const selectedDraftId = ref<number | null>(null)
const diffLoading = ref(false)
const loading = ref(true)
const error = ref('')
const publicationConflict = ref(false)
let pollTimer: number | undefined
let polling = false
let eventSource: EventSource | undefined

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
  }, 5000)
}

async function load(): Promise<void> {
  task.value = await knowledgeTaskApi.detail(identifier, conversationId)
  if (selectedDraftId.value) {
    const document = task.value.workspaceDocuments.find(value => value.draftId === selectedDraftId.value)
    if (document) await loadDiff(document.draftId, document.currentRevision)
    else closeDiff()
  }
}

async function refresh(): Promise<void> {
  try { await load() } catch { error.value = '知识任务刷新失败，请稍后重试。' }
}

async function stop(runId: number): Promise<void> { await knowledgeTaskApi.stop(identifier, conversationId, runId); await refresh() }
async function continueTask(guidance: string): Promise<void> { await knowledgeTaskApi.continueTask(identifier, conversationId, guidance); await refresh(); schedulePoll() }

async function publish(): Promise<void> {
  if (!task.value) return
  const reviewedDrafts = task.value.workspaceDocuments
    .filter(document => document.currentRevision > 0)
    .map(document => ({ draftId: document.draftId, reviewedRevision: document.currentRevision }))
  if (reviewedDrafts.length === 0) return
  publicationConflict.value = false
  try {
    await knowledgeTaskApi.publishWorkspace(identifier, conversationId, reviewedDrafts)
    await refresh()
  } catch (failure) {
    if (failure instanceof ApiError && failure.code === 'KNOWLEDGE_DRAFT_CONFLICT') {
      publicationConflict.value = true
      return
    }
    error.value = '工作区发布失败；全部文档均未发布，请重新审核后重试。'
  }
}

async function closeNoChange(): Promise<void> {
  const reason = window.prompt('请填写无需变更的结论', '已核对，现有知识无需调整')?.trim()
  if (!reason) return
  await knowledgeTaskApi.closeNoChange(identifier, conversationId, reason)
  await refresh()
}

async function openDiff(draftId: number): Promise<void> {
  selectedDraftId.value = draftId
  const document = task.value?.workspaceDocuments.find(value => value.draftId === draftId)
  if (document) await loadDiff(draftId, document.currentRevision)
}

async function loadDiff(draftId: number, revision: number): Promise<void> {
  diffLoading.value = true
  diff.value = null
  try { diff.value = await knowledgeTaskApi.diff(identifier, conversationId, draftId, null, revision) }
  catch { error.value = '无法读取文档 Diff。' }
  finally { diffLoading.value = false }
}

function closeDiff(): void { selectedDraftId.value = null; diff.value = null }

function openEvents(): void {
  eventSource?.close()
  if (!task.value || task.value.status !== 'PROCESSING') return
  eventSource = new EventSource(knowledgeTaskApi.eventUrl(identifier, conversationId, task.value.lastEventSequence))
  eventSource.addEventListener('task', () => { void refresh() })
  eventSource.onerror = () => schedulePoll()
}

async function logout(): Promise<void> { await session.logout(); await router.push('/login') }

onMounted(async () => {
  try {
    if (identifier) {
      const [, projectDetail] = await Promise.all([load(), projects.getProject(identifier)])
      project.value = projectDetail
    } else {
      await load()
    }
    openEvents()
    schedulePoll()
  } catch { error.value = '无法打开知识任务。' } finally { loading.value = false }
})

onBeforeUnmount(() => { if (pollTimer !== undefined) window.clearTimeout(pollTimer); eventSource?.close() })
</script>

<style scoped>
.knowledge-task-main{min-height:960px;background:var(--surface)}
.knowledge-task-content{width:min(1220px,calc(100% - 64px));margin:0 auto;padding:20px 0 32px}
.knowledge-task-content>.project-tabs{margin-top:14px}
.global-task-heading{margin-top:14px}
.knowledge-task-content>.knowledge-task-workspace{margin-top:14px}
.task-page-state{margin:32px 0;border:1px solid var(--border);border-radius:12px;padding:24px;background:var(--neutral-soft)}
.task-page-state--error{color:var(--danger);background:var(--danger-soft)}
</style>
