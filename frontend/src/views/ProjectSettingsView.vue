<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main settings-main">
      <AppTopBar
        :project-name="project?.name ?? '项目设置'"
        :selected-branch="selectedBranch"
        :branches="branchNames"
        @branch-change="changeBranch"
      />

      <div v-if="loading" class="settings-state" aria-live="polite">正在加载项目详情…</div>
      <div v-else-if="loadError" class="settings-state settings-state--error" role="alert">
        <strong>项目详情加载失败</strong>
        <p>暂时无法获取项目设置，请稍后重试。</p>
        <AppButton data-testid="retry-project-detail" variant="secondary" @click="loadProject()">重新加载</AppButton>
      </div>

      <div v-else-if="project" class="settings-content">
        <ProjectHero
          :name="project.name"
          :identifier="project.identifier"
          :technology-stack="project.technologyStack"
          :status="projectStatus"
        >
          <template #actions>
            <AppButton variant="secondary" icon="file" disabled>导入知识</AppButton>
            <AppButton icon="plus" disabled>新建知识</AppButton>
          </template>
        </ProjectHero>
        <ProjectTabs
          active="settings"
          :role="identity.role"
          :project-identifier="project.identifier"
          :project-id="project.id"
          :branch="selectedBranch"
        />
        <PageHeader
          breadcrumb="项目 / 项目设置"
          :title="isAdministrator ? '项目设置' : '项目范围'"
          :description="isAdministrator ? '维护项目范围、分支和查询可见状态。' : '查看当前项目范围并选择用于后续查询的分支。'"
        />

        <div class="settings-grid">
          <section class="settings-card settings-basic-card">
            <header><h2>基本信息</h2><StatusBadge :status="projectStatus" /></header>
            <div class="settings-fields">
              <FormField id="project-name" label="项目名称" :model-value="project.name" help="T2 基本信息只读" readonly />
              <FormField id="project-identifier" label="项目标识" :model-value="project.identifier" help="用于 Web、检索与 MCP 的稳定业务标识" readonly />
              <FormField id="project-description" label="项目简介" :model-value="project.description" readonly multiline />
              <FormField id="project-stack" label="主要技术栈" :model-value="project.technologyStack" readonly />
            </div>
            <NoticeBanner>页面权限只用于减少误操作，所有写请求仍由后端重新校验管理员身份。</NoticeBanner>
          </section>

          <aside class="settings-side-column">
            <section class="settings-card branch-card">
              <header>
                <div><h2>项目分支</h2><p>问答与检索必须先锁定分支</p></div>
                <AppButton
                  v-if="isAdministrator"
                  data-testid="open-add-branch"
                  variant="secondary"
                  icon="plus"
                  @click="showAddBranch = true"
                >添加分支</AppButton>
              </header>
              <ul class="branch-list">
                <li v-for="(branch, index) in project.branches" :key="branch.id" :class="{ 'branch-list__item--selected': branch.name === selectedBranch }">
                  <span class="branch-list__icon"><IconGlyph name="branch" /></span>
                  <span><strong>{{ branch.name }}</strong><small>{{ index === 0 ? DESIGN_SAMPLES.branchMetadata.main : DESIGN_SAMPLES.branchMetadata.secondary }}</small></span>
                  <span v-if="branch.name === project.defaultBranch" class="default-badge">默认</span>
                  <IconGlyph name="more" />
                </li>
              </ul>
              <p class="branch-hint"><IconGlyph name="file" />新增分支后，需要单独上传代码快照。</p>
            </section>

            <section class="settings-card status-card">
              <h2>项目状态</h2>
              <p>停用后不再出现在普通查询入口；历史知识、快照与引用仍保留。</p>
              <p v-if="statusError" class="inline-error" role="alert">{{ statusError }}</p>
              <AppButton
                v-if="isAdministrator"
                data-testid="change-project-status"
                :variant="projectStatus === 'ENABLED' ? 'danger' : 'secondary'"
                :busy="changingStatus"
                :busy-label="projectStatus === 'ENABLED' ? '正在停用…' : '正在启用…'"
                @click="changeStatus"
              >{{ projectStatus === 'ENABLED' ? '停用项目' : '启用项目' }}</AppButton>
            </section>

            <section class="mcp-identifier-card">
              <span>MCP 项目标识</span>
              <strong>{{ project.identifier }}</strong>
              <p>Token 权限校验后方可读取</p>
            </section>
          </aside>
        </div>
      </div>
    </main>

    <div v-if="showAddBranch" class="dialog-backdrop" @click.self="showAddBranch = false">
      <section class="app-dialog app-dialog--small" role="dialog" aria-modal="true" aria-labelledby="add-branch-title" @keydown.esc="showAddBranch = false">
        <header><div><h2 id="add-branch-title">添加项目分支</h2><p>分支将在当前项目范围内保持唯一。</p></div><button type="button" aria-label="关闭" @click="showAddBranch = false">×</button></header>
        <form data-testid="add-branch-form" @submit.prevent="addBranch">
          <FormField id="branch-name" v-model="branchName" label="分支名称" help="例如 feature/import-export" required autofocus :disabled="addingBranch" />
          <p v-if="branchError" class="dialog-error" role="alert">{{ branchError }}</p>
          <footer>
            <AppButton variant="secondary" :disabled="addingBranch" @click="showAddBranch = false">取消</AppButton>
            <AppButton type="submit" icon="plus" :busy="addingBranch" busy-label="正在添加…">添加分支</AppButton>
          </footer>
        </form>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { AdminProjectDetail, ProjectDetail, ProjectStatus } from '../api/types'
import { useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import FormField from '../components/FormField.vue'
import IconGlyph from '../components/IconGlyph.vue'
import NoticeBanner from '../components/NoticeBanner.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { DESIGN_SAMPLES } from '../designSamples'

const api = useProjectApi()
const session = useSession()
const route = useRoute()
const router = useRouter()
const identity = computed(() => session.identity.value)
const isAdministrator = computed(() => identity.value?.role === 'ADMIN' && route.name === 'project-settings')
const adminProject = ref<AdminProjectDetail | null>(null)
const memberProject = ref<ProjectDetail | null>(null)
const selectedAdminBranch = ref('')
const loading = ref(true)
const loadError = ref(false)
const showAddBranch = ref(false)
const branchName = ref('')
const branchError = ref('')
const addingBranch = ref(false)
const changingStatus = ref(false)
const statusError = ref('')

const project = computed(() => adminProject.value ?? memberProject.value)
const projectStatus = computed<ProjectStatus>(() => adminProject.value?.status ?? 'ENABLED')
const selectedBranch = computed(() => adminProject.value
  ? selectedAdminBranch.value || adminProject.value.defaultBranch
  : memberProject.value?.selectedBranch ?? '')
const branchNames = computed(() => project.value?.branches.map(branch => branch.name) ?? [])

async function loadProject(branch?: string) {
  loading.value = true
  loadError.value = false
  adminProject.value = null
  memberProject.value = null
  try {
    if (isAdministrator.value) {
      const result = await api.getAdminProject(String(route.params.projectId))
      adminProject.value = result
      selectedAdminBranch.value = branch && result.branches.some(item => item.name === branch)
        ? branch
        : result.defaultBranch
    } else {
      memberProject.value = await api.getProject(String(route.params.identifier), branch)
    }
  } catch {
    // 详情失败时不保留旧项目，避免管理员基于过期状态继续执行分支或启停操作。
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function changeBranch(branch: string) {
  if (isAdministrator.value) {
    selectedAdminBranch.value = branch
    return
  }
  await router.replace({ query: branch === memberProject.value?.defaultBranch ? {} : { branch } })
  await loadProject(branch)
}

async function addBranch() {
  if (!isAdministrator.value || addingBranch.value) {
    return
  }
  addingBranch.value = true
  branchError.value = ''
  try {
    await api.addBranch(String(route.params.projectId), branchName.value)
    showAddBranch.value = false
    branchName.value = ''
    await loadProject(selectedBranch.value)
  } catch {
    branchError.value = '分支添加失败，请检查名称是否有效或重复。'
  } finally {
    addingBranch.value = false
  }
}

async function changeStatus() {
  if (!adminProject.value || changingStatus.value || !isAdministrator.value) {
    return
  }
  const target: ProjectStatus = adminProject.value.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  if (target === 'DISABLED' && !window.confirm('停用后项目将退出普通查询，但项目、分支和历史数据仍会保留。确认停用吗？')) {
    return
  }
  changingStatus.value = true
  statusError.value = ''
  try {
    adminProject.value = await api.changeStatus(adminProject.value.id, target)
  } catch {
    statusError.value = '项目状态更新失败，当前页面仍保留原状态，请稍后重试。'
  } finally {
    changingStatus.value = false
  }
}

async function logout() {
  await session.logout()
  await router.replace('/login')
}

onMounted(() => loadProject(typeof route.query.branch === 'string' ? route.query.branch : undefined))
</script>
