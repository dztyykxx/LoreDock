<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main knowledge-editor-main">
      <AppTopBar
        v-if="project"
        :project-name="project.name"
        :selected-branch="selectedBranch"
        :branches="project.branches.map(branch => branch.name)"
        @branch-change="selectedBranch = $event"
      />
      <header v-else class="list-topbar"><div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>通用业务知识</strong></div></header>

      <section v-if="identity.role !== 'ADMIN'" class="knowledge-editor-denied">
        <NoticeBanner tone="warning">仅管理员可以维护知识。你仍可返回当前知识目录继续浏览已发布内容。</NoticeBanner>
        <RouterLink :to="backTarget"><AppButton variant="secondary">返回知识目录</AppButton></RouterLink>
      </section>

      <section v-else class="knowledge-editor-content">
        <ProjectHero
          v-if="project"
          :name="project.name"
          :identifier="project.identifier"
          :technology-stack="project.technologyStack"
        />
        <ProjectTabs
          v-if="project"
          active="knowledge"
          role="ADMIN"
          :project-identifier="project.identifier"
          :project-id="project.id"
          :branch="selectedBranch"
        />

        <PageHeader
          :breadcrumb="isImportMode ? '知识文档 / 导入' : isEditMode ? '知识文档 / 编辑' : '知识文档 / 新建'"
          :title="isImportMode ? '导入知识资料' : isEditMode ? '编辑知识' : '新建知识'"
          :description="isImportMode ? '上传 Markdown、纯文本或 ZIP，并逐项核对导入结果。' : '使用原生文本编辑器录入可检索、可引用的业务规则与设计依据。'"
        >
          <template #actions><RouterLink :to="backTarget"><AppButton variant="secondary">返回目录</AppButton></RouterLink></template>
        </PageHeader>

        <div v-if="loading" class="knowledge-state" aria-live="polite">正在准备知识表单…</div>
        <div v-else-if="loadError" class="knowledge-state knowledge-state--error" role="alert">
          <strong>知识表单加载失败</strong><p>当前文档或项目上下文不可用。</p>
          <AppButton variant="secondary" @click="initialize">重新加载</AppButton>
        </div>

        <div v-else-if="isImportMode" class="knowledge-import-layout">
          <form class="knowledge-import-card" @submit.prevent="submitImport">
            <NoticeBanner>支持 Markdown、纯文本或 ZIP，单批上传上限 20 MiB。ZIP 中不支持、危险或无效条目会分别记录，不会掩盖其他结果。</NoticeBanner>
            <label class="editor-field">
              <span>选择文件</span>
              <input data-testid="import-file" type="file" accept=".md,.markdown,.txt,.zip,text/markdown,text/plain,application/zip" :disabled="importing" @change="selectFile">
              <small>文件名和压缩包条目均视为不可信文本。</small>
            </label>
            <ScopeFields v-model="scope" :projects="scopeProjects" :disabled="importing" />
            <label class="editor-field">
              <span>目录前缀</span>
              <input v-model="directory" type="text" :disabled="importing" placeholder="例如 导入资料/原 Wiki">
            </label>
            <TagInput id="import-tags" v-model="tags" label="默认标签" :disabled="importing" help="每个成功条目都会继承这些标签。" />
            <label class="editor-field">
              <span>人工整理说明</span>
              <textarea v-model="source.curationNote" :disabled="importing" />
            </label>
            <p v-if="importError" data-testid="import-error" class="inline-error" role="alert">{{ importError }}</p>
            <AppButton data-testid="import-submit" icon="file" :busy="importing" busy-label="正在导入…" :disabled="!selectedFile" @click="submitImport">开始导入</AppButton>
          </form>
          <aside class="knowledge-import-results">
            <ImportResultPanel v-if="importBatch" :batch="importBatch" @open-document="openImportedDocument" />
            <div v-else class="knowledge-detail-state"><IconGlyph name="file" /><span>完成上传后在这里查看成功、失败和忽略结果</span></div>
          </aside>
        </div>

        <form v-else data-testid="document-form" class="knowledge-editor-layout" @submit.prevent="saveDocument">
          <section class="knowledge-editor-body">
            <NoticeBanner v-if="currentDocument?.status === 'ARCHIVED'" tone="warning">归档文档只读，正文、范围和来源均不可再修改。</NoticeBanner>
            <NoticeBanner v-else-if="currentDocument?.syncStatus === 'PENDING' || currentDocument?.syncStatus === 'STALE'" tone="warning">索引待同步：普通浏览仍可使用上一个成功 generation，重新索引完成后才更新检索投影。</NoticeBanner>
            <label class="editor-field">
              <span>知识标题</span>
              <input data-testid="document-title" v-model="form.title" type="text" maxlength="200" required :disabled="readOnly">
              <small v-if="fieldErrors.title" data-testid="field-error-title" class="field-error">{{ fieldErrors.title }}</small>
              <small v-else>清晰描述该知识解决的业务问题。</small>
            </label>
            <div class="knowledge-editor-toolbar">
              <label>正文格式
                <select data-testid="document-format" v-model="form.format" :disabled="readOnly">
                  <option value="MARKDOWN">Markdown</option>
                  <option value="PLAIN_TEXT">纯文本</option>
                </select>
              </label>
              <span>{{ form.format === 'MARKDOWN' ? 'Markdown 仅作为文本保存，不在前端执行 HTML。' : '纯文本不会解释任何标记。' }}</span>
            </div>
            <label class="editor-field editor-field--body">
              <span class="sr-only">知识正文</span>
              <textarea data-testid="document-body" v-model="form.body" required :disabled="readOnly" placeholder="输入知识正文…" />
              <small v-if="fieldErrors.body" class="field-error">{{ fieldErrors.body }}</small>
            </label>
          </section>

          <aside class="knowledge-editor-metadata">
            <div class="knowledge-editor-status">
              <h2>发布信息</h2>
              <DocumentStatusBadge v-if="currentDocument" :status="currentDocument.status" :sync-status="currentDocument.syncStatus" />
              <span v-else>新草稿</span>
            </div>
            <ScopeFields v-model="scope" :projects="scopeProjects" :disabled="readOnly" />
            <label class="editor-field">
              <span>逻辑目录</span>
              <input data-testid="document-directory" v-model="directory" type="text" :disabled="readOnly" placeholder="例如 业务规则/导入">
            </label>
            <TagInput id="document-tags" v-model="tags" label="知识标签" :disabled="readOnly" help="最多 20 个，大小写不重复。" />
            <label class="editor-field">
              <span>知识来源</span>
              <select data-testid="source-type" v-model="source.type" :disabled="readOnly">
                <option value="MANUAL">人工整理</option>
                <option value="WIKI">原 Wiki</option>
                <option value="UPLOAD">文件上传</option>
              </select>
            </label>
            <label v-if="source.type === 'WIKI'" class="editor-field">
              <span>Wiki URL</span>
              <input data-testid="source-wiki-url" v-model="source.wikiUrl" type="url" :disabled="readOnly">
            </label>
            <label v-if="source.type === 'UPLOAD'" class="editor-field">
              <span>原文件名</span>
              <input v-model="source.originalFilename" type="text" :disabled="readOnly">
            </label>
            <label class="editor-field">
              <span>人工整理说明</span>
              <textarea v-model="source.curationNote" :disabled="readOnly" />
            </label>

            <label v-if="currentDocument?.status === 'DRAFT' && replacementCandidates.length" class="editor-field">
              <span>发布后替代</span>
              <select data-testid="replacement-select" v-model="replacementId">
                <option value="">不替代现有文档</option>
                <option v-for="candidate in replacementCandidates" :key="candidate.id" :value="candidate.id">{{ candidate.title }}</option>
              </select>
              <small>只能替代同一范围内的已发布文档。</small>
            </label>

            <p v-if="saveError" class="inline-error" role="alert">{{ saveError }}</p>
            <div class="knowledge-editor-actions">
              <AppButton v-if="!readOnly" data-testid="save-document" icon="save" :busy="saving" busy-label="正在保存…" @click="saveDocument">{{ isEditMode ? '保存修改' : '保存草稿' }}</AppButton>
              <AppButton v-if="currentDocument?.status === 'DRAFT'" data-testid="publish-document" variant="secondary" :disabled="saving || lifecycleBusy" @click="askLifecycle('publish')">发布</AppButton>
              <AppButton v-if="currentDocument?.status === 'PUBLISHED'" data-testid="archive-document" variant="danger" :disabled="saving || lifecycleBusy" @click="askLifecycle('archive')">归档</AppButton>
            </div>
          </aside>
        </form>
      </section>
    </main>

    <ConfirmDialog
      :open="confirmAction !== null"
      :title="confirmAction === 'publish' ? '确认发布知识' : '确认归档知识'"
      :message="confirmAction === 'publish' ? '发布后文档将进入正式知识范围；如选择替代，旧文档会被原子归档。' : '归档后文档会退出普通浏览和实时检索资格。'"
      :confirm-label="confirmAction === 'publish' ? '确认发布' : '确认归档'"
      :danger="confirmAction === 'archive'"
      :busy="lifecycleBusy"
      @cancel="confirmAction = null"
      @confirm="confirmLifecycle"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import type {
  AdminKnowledgeDocumentView,
  DocumentSourceInput,
  KnowledgeDocumentSummary,
  KnowledgeDocumentWriteInput,
  KnowledgeImportBatch,
  KnowledgeScopeInput,
} from '../api/knowledge'
import type { ProjectDetail } from '../api/types'
import { useKnowledgeApi, useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import DocumentStatusBadge from '../components/DocumentStatusBadge.vue'
import IconGlyph from '../components/IconGlyph.vue'
import ImportResultPanel from '../components/ImportResultPanel.vue'
import NoticeBanner from '../components/NoticeBanner.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'
import ScopeFields from '../components/ScopeFields.vue'
import TagInput from '../components/TagInput.vue'

const api = useKnowledgeApi()
const projects = useProjectApi()
const session = useSession()
const route = useRoute()
const router = useRouter()
const identity = computed(() => session.identity.value)
const isImportMode = computed(() => String(route.name).endsWith('-import'))
const isEditMode = computed(() => String(route.name).endsWith('-edit'))
const project = ref<ProjectDetail | null>(null)
const selectedBranch = ref('main')
const scopeProjects = ref<Array<{ identifier: string; name: string; branches: string[] }>>([])
const currentDocument = ref<AdminKnowledgeDocumentView | null>(null)
const replacementCandidates = ref<KnowledgeDocumentSummary[]>([])
const replacementId = ref('')
const loading = ref(true)
const loadError = ref(false)
const saving = ref(false)
const lifecycleBusy = ref(false)
const confirmAction = ref<'publish' | 'archive' | null>(null)
const saveError = ref('')
const fieldErrors = reactive<Record<string, string>>({})
const selectedFile = ref<File | null>(null)
const importing = ref(false)
const importError = ref('')
const importBatch = ref<KnowledgeImportBatch | null>(null)

const form = reactive({ format: 'MARKDOWN' as 'MARKDOWN' | 'PLAIN_TEXT', title: '', body: '' })
const scope = ref<KnowledgeScopeInput>({ type: 'GLOBAL', project: null, branch: null })
const directory = ref('')
const tags = ref<string[]>([])
const source = reactive<DocumentSourceInput>({ type: 'MANUAL', wikiUrl: null, originalFilename: null, curationNote: null })
const readOnly = computed(() => currentDocument.value?.status === 'ARCHIVED')
const backTarget = computed(() => project.value
  ? { path: `/projects/${project.value.identifier}`, query: selectedBranch.value === project.value.defaultBranch ? {} : { branch: selectedBranch.value } }
  : '/knowledge')

async function initialize(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    if (typeof route.params.identifier === 'string') {
      project.value = await projects.getProject(String(route.params.identifier), queryBranch())
      selectedBranch.value = project.value.selectedBranch || project.value.defaultBranch
      scopeProjects.value = [{
        identifier: project.value.identifier,
        name: project.value.name,
        branches: project.value.branches.map(branch => branch.name),
      }]
    } else {
      await loadScopeProjects()
    }
    if (isEditMode.value) {
      currentDocument.value = await api.getAdminDocument(String(route.params.documentId))
      fillFromDocument(currentDocument.value)
      await loadReplacementCandidates(currentDocument.value)
    } else if (project.value) {
      scope.value = { type: 'PROJECT', project: project.value.identifier, branch: null }
    }
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadScopeProjects(): Promise<void> {
  const summaries = await projects.listProjects()
  const details = await Promise.all(summaries.map(item => projects.getProject(item.identifier)))
  scopeProjects.value = details.map(item => ({
    identifier: item.identifier,
    name: item.name,
    branches: item.branches.map(branch => branch.name),
  }))
}

function fillFromDocument(document: AdminKnowledgeDocumentView): void {
  form.format = document.format
  form.title = document.title
  form.body = document.body
  directory.value = document.directory
  tags.value = [...document.tags]
  Object.assign(source, document.source)
  if (document.scope.type === 'GLOBAL') {
    scope.value = { type: 'GLOBAL', project: null, branch: null }
  } else {
    const contextProject = project.value
    const branch = contextProject?.branches.find(item => item.id === document.scope.branchId)?.name ?? null
    scope.value = { type: document.scope.type, project: contextProject?.identifier ?? null, branch }
  }
}

async function loadReplacementCandidates(document: AdminKnowledgeDocumentView): Promise<void> {
  const result = await api.listAdmin({
    scopeType: document.scope.type,
    projectId: document.scope.projectId ?? undefined,
    branchId: document.scope.branchId ?? undefined,
    status: 'PUBLISHED',
    size: 100,
  })
  replacementCandidates.value = result.items.filter(item => item.id !== document.id)
}

function writeInput(): KnowledgeDocumentWriteInput {
  return {
    format: form.format,
    title: form.title,
    body: form.body,
    directory: directory.value,
    tags: [...tags.value],
    scope: { ...scope.value },
    source: {
      type: source.type,
      wikiUrl: source.type === 'WIKI' ? source.wikiUrl ?? null : null,
      originalFilename: source.type === 'UPLOAD' ? source.originalFilename ?? null : null,
      curationNote: source.curationNote ?? null,
    },
  }
}

async function saveDocument(): Promise<void> {
  if (saving.value || readOnly.value) {
    return
  }
  saving.value = true
  saveError.value = ''
  Object.keys(fieldErrors).forEach(key => delete fieldErrors[key])
  try {
    if (isEditMode.value) {
      currentDocument.value = await api.updateDocument(String(route.params.documentId), writeInput())
    } else {
      currentDocument.value = await api.createDocument(writeInput())
      const editPath = project.value
        ? `/projects/${project.value.identifier}/knowledge/${currentDocument.value.id}/edit`
        : `/knowledge/${currentDocument.value.id}/edit`
      await router.replace({ path: editPath, query: route.query })
    }
  } catch (error) {
    if (error instanceof ApiError) {
      error.fieldErrors.forEach(item => { fieldErrors[item.field] = item.message })
      saveError.value = error.message
    } else {
      saveError.value = '知识保存失败，当前输入已保留，请稍后重试。'
    }
  } finally {
    saving.value = false
  }
}

function askLifecycle(action: 'publish' | 'archive'): void {
  confirmAction.value = action
}

async function confirmLifecycle(): Promise<void> {
  if (!currentDocument.value || !confirmAction.value || lifecycleBusy.value) {
    return
  }
  lifecycleBusy.value = true
  saveError.value = ''
  const action = confirmAction.value
  try {
    currentDocument.value = action === 'publish'
      ? await api.publishDocument(currentDocument.value.id, replacementId.value || undefined)
      : await api.archiveDocument(currentDocument.value.id)
    fillFromDocument(currentDocument.value)
    confirmAction.value = null
  } catch {
    saveError.value = action === 'publish' ? '发布失败，草稿状态保持不变。' : '归档失败，已发布状态保持不变。'
    confirmAction.value = null
  } finally {
    lifecycleBusy.value = false
  }
}

function selectFile(event: Event): void {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  importBatch.value = null
  importError.value = ''
}

async function submitImport(): Promise<void> {
  if (!selectedFile.value || importing.value) {
    return
  }
  importing.value = true
  importError.value = ''
  importBatch.value = null
  try {
    importBatch.value = await api.importDocuments(selectedFile.value, {
      scope: { ...scope.value },
      directoryPrefix: directory.value,
      tags: [...tags.value],
      sourceDefaults: {
        type: 'UPLOAD',
        originalFilename: selectedFile.value.name,
        curationNote: source.curationNote ?? null,
      },
    })
  } catch (error) {
    if (error instanceof ApiError) {
      importError.value = ({
        DOCUMENT_IMPORT_TOO_LARGE: '文件超过导入上限，请拆分后重试。',
        DOCUMENT_IMPORT_TYPE_UNSUPPORTED: '文件格式不受支持，请选择 Markdown、纯文本或 ZIP。',
        DOCUMENT_IMPORT_ARCHIVE_INVALID: 'ZIP 无法安全读取，请检查加密、分卷或压缩结构。',
      } as Record<string, string>)[error.code] ?? error.message
    } else {
      importError.value = '导入请求失败，未生成任何批次结果。'
    }
  } finally {
    importing.value = false
  }
}

async function openImportedDocument(documentId: string): Promise<void> {
  const target = project.value
    ? `/projects/${project.value.identifier}/knowledge/${documentId}/edit`
    : `/knowledge/${documentId}/edit`
  await router.push(target)
}

function queryBranch(): string | undefined {
  return typeof route.query.branch === 'string' ? route.query.branch : undefined
}

async function logout(): Promise<void> {
  await session.logout()
  await router.replace('/login')
}

onMounted(initialize)
</script>
