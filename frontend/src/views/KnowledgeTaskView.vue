<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main knowledge-task-main">
      <AppTopBar :project-name="project?.name ?? identifier" />
      <section class="knowledge-task-content">
        <ProjectHero
          :name="project?.name ?? identifier"
          :identifier="identifier"
          :technology-stack="project?.technologyStack ?? '知识整理任务'"
        >
          <template #actions>
            <RouterLink :to="`/projects/${identifier}/knowledge-tasks`"><AppButton variant="secondary" icon="arrowLeft">返回任务列表</AppButton></RouterLink>
            <AppButton
              icon="check"
              :disabled="!diff || diff.toRevision === 0 || publicationConflict"
              @click="publish(diff?.toRevision)"
            >发布修订 v{{ diff?.toRevision ?? task?.currentDraftRevision ?? 0 }}</AppButton>
          </template>
        </ProjectHero>
        <ProjectTabs
          active="tasks"
          :role="identity.role"
          :project-identifier="identifier"
          :project-id="project?.id"
        />

        <p v-if="loading" class="task-page-state">正在读取知识任务…</p>
        <p v-else-if="error" class="task-page-state task-page-state--error" role="alert">{{ error }}</p>
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
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { knowledgeTaskApi, type DraftDiff, type DraftRevision, type KnowledgeTask } from '../api/knowledgeTasks'
import type { ProjectDetail } from '../api/types'
import { useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import KnowledgeTaskWorkspace from '../components/KnowledgeTaskWorkspace.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'

const route = useRoute()
const router = useRouter()
const projects = useProjectApi()
const session = useSession()
const identity = computed(() => session.identity.value)
const identifier = String(route.params.identifier)
const conversationId = Number(route.params.conversationId)
const project = ref<ProjectDetail | null>(null)
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
    revision.value = await knowledgeTaskApi.revision(identifier, conversationId, task.value.currentDraftId, task.value.currentDraftRevision)
    revisions.value = await knowledgeTaskApi.revisions(identifier, conversationId, task.value.currentDraftId)
    diff.value = await knowledgeTaskApi.diff(identifier, conversationId, task.value.currentDraftId, null, task.value.currentDraftRevision)
  } else {
    revision.value = null
    revisions.value = []
    diff.value = null
  }
}

async function refresh(): Promise<void> {
  try { await load() } catch { error.value = '知识任务刷新失败，请稍后重试。' }
}

async function pause(runId: number): Promise<void> { await knowledgeTaskApi.pause(identifier, conversationId, runId); await refresh() }
async function resume(value: { runId: number; guidance: string }): Promise<void> { await knowledgeTaskApi.resume(identifier, conversationId, value.runId, value.guidance); await refresh(); schedulePoll() }
async function continueTask(guidance: string): Promise<void> { await knowledgeTaskApi.continueTask(identifier, conversationId, guidance); await refresh(); schedulePoll() }

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

async function logout(): Promise<void> { await session.logout(); await router.push('/login') }

onMounted(async () => {
  try {
    const [, projectDetail] = await Promise.all([load(), projects.getProject(identifier)])
    project.value = projectDetail
    schedulePoll()
  } catch { error.value = '无法打开知识任务。' } finally { loading.value = false }
})

onBeforeUnmount(() => { if (pollTimer !== undefined) window.clearTimeout(pollTimer) })
</script>

<style scoped>
.knowledge-task-main{min-height:960px;background:var(--surface)}
.knowledge-task-content{width:min(1080px,calc(100% - 64px));margin:0 auto;padding:20px 0 32px}
.knowledge-task-content>.project-tabs{margin-top:14px}
.knowledge-task-content>.knowledge-task-workspace{margin-top:14px}
.task-page-state{margin:32px 0;border:1px solid var(--border);border-radius:12px;padding:24px;background:var(--neutral-soft)}
.task-page-state--error{color:var(--danger);background:var(--danger-soft)}
</style>
