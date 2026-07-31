<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main knowledge-main">
      <AppTopBar
        v-if="project"
        :project-name="project.name"
        :selected-branch="selectedBranch"
        :branches="project.branches.map(branch => branch.name)"
        @branch-change="changeBranch"
      />
      <header v-else class="list-topbar">
        <div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>通用业务知识</strong></div>
      </header>

      <section class="knowledge-content">
        <ProjectHero
          v-if="project"
          :name="project.name"
          :identifier="project.identifier"
          :technology-stack="project.technologyStack"
        >
          <template v-if="isAdministrator" #actions>
            <RouterLink :to="importTarget"><AppButton data-testid="import-knowledge" variant="secondary" icon="file">导入资料</AppButton></RouterLink>
            <RouterLink :to="newTarget"><AppButton data-testid="new-knowledge" icon="plus">新建知识</AppButton></RouterLink>
          </template>
        </ProjectHero>

        <ProjectTabs
          v-if="project"
          active="knowledge"
          :role="identity.role"
          :project-identifier="project.identifier"
          :project-id="project.id"
          :branch="selectedBranch"
          :knowledge-count="totalElements"
        />

        <PageHeader
          :breadcrumb="project ? `项目 / ${selectedBranch} / 知识文档` : '工作空间 / 通用业务知识'"
          :title="project ? '知识文档' : '通用业务知识'"
          :description="project ? '当前项目和分支决定浏览边界，切换分支会重新加载全部结果。' : '这里只展示明确属于全局范围的业务术语、流程和规范。'"
        >
          <template v-if="isAdministrator && !project" #actions>
            <RouterLink to="/knowledge/import"><AppButton data-testid="import-knowledge" variant="secondary" icon="file">导入资料</AppButton></RouterLink>
            <RouterLink to="/knowledge/new"><AppButton data-testid="new-knowledge" icon="plus">新建知识</AppButton></RouterLink>
          </template>
        </PageHeader>

        <div v-if="isAdministrator" class="knowledge-admin-tools">
          <NoticeBanner>草稿和归档状态只在管理员视图展示；普通成员始终由只读接口获取已发布文档。</NoticeBanner>
          <AppButton
            data-testid="reindex-knowledge"
            variant="secondary"
            icon="settings"
            :busy="reindexBusy"
            busy-label="正在重建…"
            @click="startReindex"
          >重新索引</AppButton>
        </div>
        <section v-if="indexJob" data-testid="index-job-panel" class="knowledge-index-job" aria-live="polite">
          <div>
            <strong>{{ indexJobLabel }}</strong>
            <code>{{ indexJob.id }}</code>
          </div>
          <span>{{ indexJob.progress }}%</span>
          <p v-if="indexJob.failureSummary" role="alert">{{ indexJob.failureSummary }}</p>
        </section>

        <div v-if="loading" class="knowledge-state" aria-live="polite">正在加载知识目录…</div>
        <div v-else-if="loadError" class="knowledge-state knowledge-state--error" role="alert">
          <strong>知识目录加载失败</strong>
          <p>当前范围保持不变，请检查连接后重试。</p>
          <AppButton data-testid="retry-knowledge" variant="secondary" @click="loadDocuments">重新加载</AppButton>
        </div>
        <div v-else class="knowledge-workspace">
          <aside class="knowledge-directory-panel">
            <div class="knowledge-panel-heading"><h2>文档目录</h2><span>{{ totalElements }}</span></div>
            <DocumentDirectoryTree :nodes="directories" :current-path="currentDirectory" @select="selectDirectory" />
          </aside>

          <section class="knowledge-list-panel">
            <div class="knowledge-list-toolbar">
              <div><h2>{{ currentDirectory || '全部文档' }}</h2><p>{{ isAdministrator ? '全部生命周期状态' : '仅已发布文档' }}</p></div>
              <span>第 {{ page + 1 }} 页</span>
            </div>
            <div v-if="documents.length === 0" data-testid="knowledge-empty" class="knowledge-empty">
              <IconGlyph name="book" />
              <strong>{{ isAdministrator ? '当前范围暂无知识文档' : '暂无已发布知识' }}</strong>
              <p>{{ isAdministrator ? '可以新建或导入资料后再发布。' : '请联系管理员补充并发布知识。' }}</p>
            </div>
            <DocumentList v-else :documents="documents" :selected-id="selectedDocumentId" @select="openDocument" />
            <nav v-if="totalPages > 1" class="knowledge-pagination" aria-label="知识分页">
              <AppButton variant="secondary" :disabled="page === 0" @click="changePage(page - 1)">上一页</AppButton>
              <AppButton variant="secondary" :disabled="page >= totalPages - 1" @click="changePage(page + 1)">下一页</AppButton>
            </nav>
          </section>

          <aside class="knowledge-detail-panel">
            <div v-if="detailLoading" class="knowledge-detail-state" aria-live="polite">正在加载文档…</div>
            <div v-else-if="detailError" data-testid="detail-error" class="knowledge-detail-state knowledge-detail-state--error" role="alert">
              <strong>当前范围内找不到该文档</strong>
              <p>文档可能已归档、属于其他项目或不适用于当前分支。</p>
            </div>
            <article v-else-if="detail" data-testid="knowledge-detail" class="knowledge-detail">
              <header>
                <div><p>{{ detail.directory || '根目录' }}</p><h2>{{ detail.title }}</h2></div>
                <div class="knowledge-detail__actions">
                  <DocumentStatusBadge :status="detail.status" :sync-status="detail.syncStatus" />
                  <RouterLink v-if="isAdministrator" :to="editTarget(detail.id)">编辑</RouterLink>
                </div>
              </header>
              <div class="knowledge-detail__meta">
                <span>修订 {{ detail.revision }}</span><span>{{ formatLabel(detail.format) }}</span><span>{{ scopeLabel(detail.scope.type) }}</span>
              </div>
              <pre>{{ detail.body }}</pre>
              <footer>
                <strong>来源</strong>
                <span>{{ sourceLabel(detail.source.type) }}</span>
                <span v-if="detail.source.originalFilename">{{ detail.source.originalFilename }}</span>
                <span v-if="detail.source.wikiUrl">{{ detail.source.wikiUrl }}</span>
                <p v-if="detail.source.curationNote">{{ detail.source.curationNote }}</p>
              </footer>
            </article>
            <div v-else class="knowledge-detail-state"><IconGlyph name="file" /><span>选择一篇文档查看正文与来源</span></div>
          </aside>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { AdminKnowledgeDocumentView, KnowledgeDirectoryNode, KnowledgeDocumentSummary, KnowledgeDocumentView, KnowledgeIndexJob } from '../api/knowledge'
import type { ProjectDetail } from '../api/types'
import { useKnowledgeApi, useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import DocumentDirectoryTree from '../components/DocumentDirectoryTree.vue'
import DocumentList from '../components/DocumentList.vue'
import DocumentStatusBadge from '../components/DocumentStatusBadge.vue'
import IconGlyph from '../components/IconGlyph.vue'
import NoticeBanner from '../components/NoticeBanner.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'

const api = useKnowledgeApi()
const projects = useProjectApi()
const session = useSession()
const route = useRoute()
const router = useRouter()
const identity = computed(() => session.identity.value)
const isAdministrator = computed(() => identity.value?.role === 'ADMIN')
const isProjectContext = computed(() => typeof route.params.identifier === 'string')
const project = ref<ProjectDetail | null>(null)
const selectedBranch = ref('main')
const currentDirectory = ref('')
const page = ref(0)
const documents = ref<KnowledgeDocumentSummary[]>([])
const directories = ref<KnowledgeDirectoryNode[]>([])
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(true)
const loadError = ref(false)
const detail = ref<KnowledgeDocumentView | AdminKnowledgeDocumentView | null>(null)
const detailLoading = ref(false)
const detailError = ref(false)
const indexJob = ref<KnowledgeIndexJob | null>(null)
const reindexBusy = ref(false)
let indexPollController: AbortController | null = null
const selectedDocumentId = computed(() => typeof route.params.documentId === 'string' ? Number(route.params.documentId) : null)
const newTarget = computed(() => project.value ? `/projects/${project.value.identifier}/knowledge/new?branch=${encodeURIComponent(selectedBranch.value)}` : '/knowledge/new')
const importTarget = computed(() => project.value ? `/projects/${project.value.identifier}/knowledge/import?branch=${encodeURIComponent(selectedBranch.value)}` : '/knowledge/import')
const indexJobLabel = computed(() => ({
  PENDING: '重新索引已排队',
  RUNNING: '正在重新索引',
  SUCCEEDED: '重新索引完成',
  FAILED: '重新索引失败，浏览继续使用上一个成功索引',
} as Record<string, string>)[indexJob.value?.status ?? ''] ?? '重新索引任务')

async function initialize(): Promise<void> {
  if (isProjectContext.value) {
    try {
      project.value = await projects.getProject(String(route.params.identifier), queryBranch())
      selectedBranch.value = project.value.selectedBranch || project.value.defaultBranch
    } catch {
      loadError.value = true
      loading.value = false
      return
    }
  }
  await loadDocuments()
}

async function loadDocuments(): Promise<void> {
  loading.value = true
  loadError.value = false
  documents.value = []
  directories.value = []
  detail.value = null
  try {
    if (isAdministrator.value) {
      await loadAdminDocuments()
    } else {
      const result = await api.browse({
        context: project.value ? 'PROJECT' : 'GLOBAL',
        project: project.value?.identifier,
        branch: project.value ? selectedBranch.value : undefined,
        directory: currentDirectory.value || undefined,
        page: page.value,
        size: 20,
      })
      documents.value = result.documents.items
      directories.value = result.directories
      totalElements.value = result.documents.totalElements
      totalPages.value = result.documents.totalPages
    }
    if (selectedDocumentId.value) {
      await loadDetail(selectedDocumentId.value)
    }
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadAdminDocuments(): Promise<void> {
  const common = { directory: currentDirectory.value || undefined, size: 100 }
  const filters = project.value
    ? [
        { ...common, scopeType: 'GLOBAL' as const },
        { ...common, scopeType: 'PROJECT' as const, projectId: project.value.id },
        {
          ...common,
          scopeType: 'BRANCH' as const,
          projectId: project.value.id,
          branchId: project.value.branches.find(branch => branch.name === selectedBranch.value)?.id,
        },
      ]
    : [{ ...common, scopeType: 'GLOBAL' as const }]
  const pages = await Promise.all(filters.map(filter => api.listAdmin(filter)))
  const unique = new Map<number, KnowledgeDocumentSummary>()
  pages.flatMap(result => result.items).forEach(item => unique.set(item.id, item))
  const allItems = [...unique.values()].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt) || left.id - right.id)
  totalElements.value = allItems.length
  totalPages.value = Math.ceil(allItems.length / 20)
  documents.value = allItems.slice(page.value * 20, (page.value + 1) * 20)
  directories.value = buildDirectories(allItems)
}

function buildDirectories(items: KnowledgeDocumentSummary[]): KnowledgeDirectoryNode[] {
  const counts = new Map<string, number>()
  for (const item of items) {
    const parts = item.directory.split('/').filter(Boolean)
    for (let index = 0; index < parts.length; index += 1) {
      const path = parts.slice(0, index + 1).join('/')
      counts.set(path, (counts.get(path) ?? 0) + 1)
    }
  }
  return [...counts].map(([path, documentCount]) => ({ path, name: path.split('/').at(-1) ?? path, documentCount }))
}

async function loadDetail(documentId: number): Promise<void> {
  detailLoading.value = true
  detailError.value = false
  detail.value = null
  try {
    detail.value = isAdministrator.value
      ? await api.getAdminDocument(documentId)
      : await api.getDocument(documentId, {
          context: project.value ? 'PROJECT' : 'GLOBAL',
          project: project.value?.identifier,
          branch: project.value ? selectedBranch.value : undefined,
        })
  } catch {
    detailError.value = true
  } finally {
    detailLoading.value = false
  }
}

async function changeBranch(branch: string): Promise<void> {
  selectedBranch.value = branch
  page.value = 0
  documents.value = []
  detail.value = null
  await router.replace({ query: branch === project.value?.defaultBranch ? {} : { branch } })
  await loadDocuments()
}

async function selectDirectory(path: string): Promise<void> {
  currentDirectory.value = path
  page.value = 0
  await loadDocuments()
}

async function changePage(target: number): Promise<void> {
  page.value = target
  await loadDocuments()
}

async function openDocument(documentId: number): Promise<void> {
  const target = project.value
    ? `/projects/${project.value.identifier}/knowledge/${documentId}`
    : `/knowledge/${documentId}`
  await router.push({ path: target, query: route.query })
  await loadDetail(documentId)
}

async function startReindex(): Promise<void> {
  if (!isAdministrator.value || reindexBusy.value) {
    return
  }
  reindexBusy.value = true
  indexPollController?.abort()
  indexPollController = new AbortController()
  try {
    indexJob.value = await api.submitIndexJob()
    indexJob.value = await api.pollIndexJob(indexJob.value.id, {
      maxAttempts: 20,
      intervalMs: 1_000,
      signal: indexPollController.signal,
    })
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      // 提交或轮询失败不能清空文档列表；旧 ACTIVE generation 仍是普通浏览事实。
      indexJob.value = indexJob.value
        ? { ...indexJob.value, status: 'FAILED', failureSummary: '任务状态暂时无法获取，请稍后重新查看。' }
        : null
    }
  } finally {
    reindexBusy.value = false
  }
}

function queryBranch(): string | undefined {
  return typeof route.query.branch === 'string' ? route.query.branch : undefined
}

function formatLabel(format: string): string {
  return format === 'MARKDOWN' ? 'Markdown' : '纯文本'
}

function scopeLabel(scope: string): string {
  return ({ GLOBAL: '通用范围', PROJECT: '项目范围', BRANCH: '分支范围' } as Record<string, string>)[scope] ?? scope
}

function sourceLabel(source: string): string {
  return ({ MANUAL: '人工整理', WIKI: '原 Wiki', UPLOAD: '文件上传' } as Record<string, string>)[source] ?? source
}

function editTarget(documentId: number): string {
  return project.value
    ? `/projects/${project.value.identifier}/knowledge/${documentId}/edit?branch=${encodeURIComponent(selectedBranch.value)}`
    : `/knowledge/${documentId}/edit`
}

async function logout(): Promise<void> {
  await session.logout()
  await router.replace('/login')
}

onMounted(initialize)
onBeforeUnmount(() => indexPollController?.abort())
</script>
