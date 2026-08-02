<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main task-list-main">
      <AppTopBar :project-name="project?.name ?? identifier" />
      <section class="task-list-content">
        <ProjectHero
          :name="project?.name ?? identifier"
          :identifier="identifier"
          :technology-stack="project?.technologyStack ?? '知识整理任务'"
        >
          <template #actions>
            <RouterLink :to="`/projects/${identifier}/drafts`"><AppButton icon="plus">从草稿发起任务</AppButton></RouterLink>
          </template>
        </ProjectHero>
        <ProjectTabs
          active="tasks"
          :role="identity.role"
          :project-identifier="identifier"
          :project-id="project?.id"
          :task-count="tasks.length"
        />
        <PageHeader
          breadcrumb="项目 / 知识任务"
          title="知识任务"
          description="查看已保存的整理会话和每轮运行状态；进入原会话后可以恢复等待中的任务，或在已完成任务上继续调整。"
        />

        <div v-if="loading" class="task-list-state">正在加载知识任务…</div>
        <div v-else-if="error" class="task-list-state task-list-state--error" role="alert">
          <strong>知识任务加载失败</strong><p>当前项目范围保持不变，请稍后重试。</p>
          <AppButton variant="secondary" @click="load">重新加载</AppButton>
        </div>
        <div v-else-if="tasks.length === 0" data-testid="knowledge-task-history-empty" class="task-list-empty">
          <IconGlyph name="message" /><strong>还没有知识任务</strong>
          <p>前往草稿页勾选一份或多份待处理草稿，启动 AI 合并整理。</p>
          <RouterLink :to="`/projects/${identifier}/drafts`">进入草稿列表</RouterLink>
        </div>
        <KnowledgeTaskHistoryList v-else :project-identifier="identifier" :tasks="tasks" />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { knowledgeTaskApi, type KnowledgeTaskSummary } from '../api/knowledgeTasks'
import type { ProjectDetail } from '../api/types'
import { useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import IconGlyph from '../components/IconGlyph.vue'
import KnowledgeTaskHistoryList from '../components/KnowledgeTaskHistoryList.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'

const route = useRoute()
const router = useRouter()
const projects = useProjectApi()
const session = useSession()
const identity = computed(() => session.identity.value)
const identifier = String(route.params.identifier)
const project = ref<ProjectDetail | null>(null)
const tasks = ref<KnowledgeTaskSummary[]>([])
const loading = ref(true)
const error = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = false
  try {
    const [projectDetail, taskHistory] = await Promise.all([
      projects.getProject(identifier), knowledgeTaskApi.list(identifier),
    ])
    project.value = projectDetail
    tasks.value = taskHistory
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

async function logout(): Promise<void> { await session.logout(); await router.replace('/login') }

onMounted(load)
</script>

<style scoped>
.task-list-main{min-height:960px;background:var(--surface)}
.task-list-content{width:min(1080px,calc(100% - 64px));margin:0 auto;padding:20px 0 40px}.task-list-content>.project-tabs{margin-top:14px}.task-list-content>.page-header{margin-top:22px}
.task-list-state,.task-list-empty{margin-top:18px;border:1px solid var(--border);border-radius:12px;padding:28px;background:var(--neutral-soft)}.task-list-state--error{color:var(--danger);background:var(--danger-soft)}.task-list-state p{margin:6px 0 14px}
.task-list-empty{display:flex;min-height:240px;align-items:center;justify-content:center;flex-direction:column;gap:9px;text-align:center}.task-list-empty>.icon-glyph{width:28px;color:var(--accent)}.task-list-empty p{margin:0;color:var(--muted);font-size:12px}.task-list-empty a{color:var(--accent);font-size:12px;font-weight:650}
</style>
