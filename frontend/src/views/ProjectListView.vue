<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      @logout="logout"
    />
    <main class="app-main">
      <header class="list-topbar">
        <div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>项目</strong></div>
        <label class="list-search">
          <IconGlyph name="search" />
          <span class="sr-only">筛选项目</span>
          <input
            v-model="filterText"
            data-testid="project-filter"
            type="search"
            placeholder="搜索项目或知识"
          >
          <kbd>⌘ K</kbd>
        </label>
      </header>

      <div class="project-list-content">
        <PageHeader
          breadcrumb="工作空间 / 项目"
          title="选择一个知识空间"
          description="项目与分支决定检索边界，LoreDock 不会跨范围拼接答案。"
        >
          <template v-if="identity.role === 'ADMIN'" #actions>
            <AppButton data-testid="open-create-project" icon="plus" @click="showCreate = true">创建项目</AppButton>
          </template>
        </PageHeader>

        <section class="general-knowledge-card">
          <span class="general-knowledge-card__icon"><IconGlyph name="book" /></span>
          <div><h2>通用业务知识</h2><p>查看跨项目适用的术语、流程、规范与兼容策略</p></div>
          <button type="button" disabled>进入知识库<IconGlyph name="arrowRight" /></button>
        </section>

        <section class="projects-section" aria-labelledby="enabled-projects-title">
          <header><h2 id="enabled-projects-title">已启用项目</h2><span>{{ projects.length }} 个项目</span></header>

          <div v-if="loading" class="page-state" aria-live="polite">正在加载项目…</div>
          <div v-else-if="loadError" class="page-state page-state--error" role="alert">
            <strong>项目列表加载失败</strong>
            <p>暂时无法获取项目，请稍后重试。</p>
            <AppButton data-testid="retry-projects" variant="secondary" @click="loadProjects">重新加载</AppButton>
          </div>
          <div v-else-if="projects.length === 0" class="page-state">
            <strong>暂无可用项目</strong>
            <p>{{ identity.role === 'ADMIN' ? '创建第一个项目以开始整理业务上下文。' : '请联系管理员创建或启用项目。' }}</p>
            <AppButton v-if="identity.role === 'ADMIN'" icon="plus" @click="showCreate = true">创建项目</AppButton>
          </div>
          <div v-else-if="filteredProjects.length === 0" class="page-state">
            <strong>没有匹配的项目</strong><p>请尝试输入项目名称或标识中的其他字符。</p>
          </div>
          <div v-else class="project-grid">
            <ProjectCard
              v-for="project in filteredProjects"
              :key="project.id"
              :project="project"
              :role="identity.role"
              :sample-knowledge-count="sampleKnowledgeCount(project)"
            />
          </div>
        </section>
      </div>
    </main>

    <div v-if="showCreate" class="dialog-backdrop" @click.self="showCreate = false">
      <section class="app-dialog" role="dialog" aria-modal="true" aria-labelledby="create-project-title" @keydown.esc="showCreate = false">
        <header><div><h2 id="create-project-title">创建项目</h2><p>创建后将自动生成默认 main 分支。</p></div><button type="button" aria-label="关闭" @click="showCreate = false">×</button></header>
        <form data-testid="create-project-form" @submit.prevent="createProject">
          <FormField id="project-name" v-model="createInput.name" label="项目名称" required autofocus :disabled="creating" />
          <FormField id="project-identifier" v-model="createInput.identifier" label="项目标识" help="使用小写字母、数字和单连字符" required :disabled="creating" />
          <FormField id="project-description" v-model="createInput.description" label="项目简介" multiline :disabled="creating" />
          <FormField id="project-stack" v-model="createInput.technologyStack" label="主要技术栈" :disabled="creating" />
          <p v-if="createError" class="dialog-error" role="alert">{{ createError }}</p>
          <footer>
            <AppButton variant="secondary" :disabled="creating" @click="showCreate = false">取消</AppButton>
            <AppButton type="submit" icon="plus" :busy="creating" busy-label="正在创建…">创建项目</AppButton>
          </footer>
        </form>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { CreateProjectInput, ProjectSummary } from '../api/types'
import { useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import FormField from '../components/FormField.vue'
import IconGlyph from '../components/IconGlyph.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectCard from '../components/ProjectCard.vue'
import { DESIGN_SAMPLES } from '../designSamples'

const api = useProjectApi()
const session = useSession()
const router = useRouter()
const identity = computed(() => session.identity.value)
const projects = ref<ProjectSummary[]>([])
const filterText = ref('')
const loading = ref(true)
const loadError = ref(false)
const showCreate = ref(false)
const creating = ref(false)
const createError = ref('')
const createInput = reactive<CreateProjectInput>({
  name: '',
  identifier: '',
  description: '',
  technologyStack: '',
})

const filteredProjects = computed(() => {
  const keyword = filterText.value.trim().toLocaleLowerCase()
  if (!keyword) {
    return projects.value
  }
  return projects.value.filter(project =>
    project.name.toLocaleLowerCase().includes(keyword)
    || project.identifier.toLocaleLowerCase().includes(keyword))
})

function sampleKnowledgeCount(project: ProjectSummary) {
  // 知识数尚无后端接口，仅按原始项目顺序取设计样例，筛选不得把样例错配给其他项目。
  const sourceIndex = projects.value.findIndex(item => item.id === project.id)
  return DESIGN_SAMPLES.projectKnowledgeCounts[sourceIndex] ?? 8
}

async function loadProjects() {
  loading.value = true
  loadError.value = false
  projects.value = []
  try {
    projects.value = await api.listProjects()
  } catch {
    // 列表失败时清空旧结果，避免用户把已经过期或越权的数据误认为本次查询成功。
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function createProject() {
  if (creating.value || identity.value?.role !== 'ADMIN') {
    return
  }
  creating.value = true
  createError.value = ''
  try {
    await api.createProject({ ...createInput })
    showCreate.value = false
    await loadProjects()
  } catch {
    createError.value = '项目创建失败，请检查字段后重试。'
  } finally {
    creating.value = false
  }
}

async function logout() {
  await session.logout()
  await router.replace('/login')
}

onMounted(loadProjects)
</script>
