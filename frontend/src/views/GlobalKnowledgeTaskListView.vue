<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      @logout="logout"
    />
    <main class="app-main task-list-main">
      <header class="list-topbar">
        <div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>通用业务知识</strong></div>
      </header>
      <section class="task-list-content">
        <ProjectTabs active="tasks" :role="identity.role" global :task-count="tasks.length" />
        <PageHeader
          breadcrumb="工作空间 / 知识任务"
          title="知识任务"
          description="查看全局知识整理任务的生命周期、最近运行和累计待审核文档；任务只整理通用业务知识的草稿，发布后成为通用范围知识文档。"
        />

        <div v-if="loading" class="task-list-state">正在加载知识任务…</div>
        <div v-else-if="error" class="task-list-state task-list-state--error" role="alert">
          <strong>知识任务加载失败</strong><p>当前范围保持不变，请稍后重试。</p>
          <AppButton variant="secondary" @click="load">重新加载</AppButton>
        </div>
        <div v-else-if="tasks.length === 0" data-testid="knowledge-task-history-empty" class="task-list-empty">
          <IconGlyph name="message" /><strong>还没有全局知识任务</strong>
          <p>前往通用知识草稿页勾选一份或多份待处理草稿，启动 AI 合并整理。</p>
          <RouterLink to="/knowledge/drafts">进入通用知识草稿列表</RouterLink>
        </div>
        <KnowledgeTaskHistoryList v-else :project-identifier="null" :tasks="tasks" />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { knowledgeTaskApi, type KnowledgeTaskSummary } from '../api/knowledgeTasks'
import { useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import IconGlyph from '../components/IconGlyph.vue'
import KnowledgeTaskHistoryList from '../components/KnowledgeTaskHistoryList.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectTabs from '../components/ProjectTabs.vue'

const router = useRouter()
const session = useSession()
const identity = computed(() => session.identity.value)
const tasks = ref<KnowledgeTaskSummary[]>([])
const loading = ref(true)
const error = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = false
  try {
    tasks.value = await knowledgeTaskApi.list(null)
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
