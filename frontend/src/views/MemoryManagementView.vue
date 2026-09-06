<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar :display-name="identity.displayName" :role="identity.role" @logout="logout" />
    <main class="app-main memory-main">
      <header class="list-topbar">
        <div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>用户记忆</strong></div>
      </header>

      <section class="memory-content">
        <PageHeader
          breadcrumb="工作空间 / 用户记忆"
          title="用户记忆"
          description="管理会话中沉淀的长期偏好；它只影响产出风格，不作为业务事实或引用证据。"
        >
          <template #actions>
            <AppButton data-testid="new-memory" icon="plus" @click="openCreate">新建记忆</AppButton>
          </template>
        </PageHeader>

        <NoticeBanner>记忆按通用或项目范围生效。停用会保留记录但不再注入，删除后不可恢复。</NoticeBanner>

        <section class="memory-filters" aria-label="记忆筛选">
          <label class="memory-filter-search">
            <IconGlyph name="search" />
            <span class="sr-only">搜索记忆</span>
            <input v-model="filters.keyword" data-testid="memory-keyword" type="search" placeholder="搜索标题、摘要或正文" @keyup.enter="loadMemories">
          </label>
          <select v-model="filters.scope" data-testid="memory-scope" aria-label="记忆范围">
            <option value="">全部范围</option>
            <option value="GLOBAL">通用记忆</option>
            <option value="PROJECT">项目记忆</option>
          </select>
          <select v-model="filters.category" data-testid="memory-category" aria-label="记忆分类">
            <option value="">全部分类</option>
            <option v-for="option in categoryOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <select v-model="filters.status" data-testid="memory-status" aria-label="记忆状态">
            <option value="">全部状态</option>
            <option value="ACTIVE">启用</option>
            <option value="DISABLED">已停用</option>
          </select>
          <AppButton variant="secondary" :busy="loading" busy-label="加载中…" @click="loadMemories">筛选</AppButton>
        </section>

        <div v-if="errorMessage" class="memory-state memory-state--error" role="alert">
          <strong>记忆列表加载失败</strong><p>{{ errorMessage }}</p>
          <AppButton variant="secondary" @click="loadMemories">重新加载</AppButton>
        </div>
        <div v-else class="memory-workspace">
          <section class="memory-list-panel">
            <header class="memory-panel-heading"><div><h2>记忆列表</h2><p>{{ page.total }} 条记录</p></div><span>第 {{ page.page }} 页</span></header>
            <div v-if="loading" class="memory-state">正在加载记忆…</div>
            <div v-else-if="page.items.length === 0" data-testid="memory-empty" class="memory-state"><IconGlyph name="user" /><strong>暂无记忆</strong><p>会话沉淀或人工创建的偏好会显示在这里。</p></div>
            <div v-else class="memory-list">
              <button
                v-for="memory in page.items"
                :key="memory.id"
                type="button"
                class="memory-card"
                :class="{ 'memory-card--selected': selected?.id === memory.id }"
                :data-testid="`memory-card-${memory.id}`"
                @click="selectMemory(memory)"
              >
                <div class="memory-card__top"><span class="memory-category">{{ categoryLabel(memory.category) }}</span><span :class="`memory-status memory-status--${memory.status.toLowerCase()}`">{{ statusLabel(memory.status) }}</span></div>
                <strong>{{ memory.title }}</strong>
                <p>{{ memory.summary || memory.content }}</p>
                <footer><span>{{ scopeLabel(memory) }}</span><span>{{ sourceLabel(memory.sourceType) }}</span><span>使用 {{ memory.useCount }} 次</span></footer>
              </button>
            </div>
            <nav v-if="page.total > page.size" class="memory-pagination" aria-label="记忆分页">
              <AppButton variant="secondary" :disabled="page.page <= 1 || loading" @click="changePage(page.page - 1)">上一页</AppButton>
              <AppButton variant="secondary" :disabled="page.page * page.size >= page.total || loading" @click="changePage(page.page + 1)">下一页</AppButton>
            </nav>
          </section>

          <section class="memory-detail-panel">
            <template v-if="editorOpen">
              <header class="memory-detail-heading"><div><p>管理员维护</p><h2>{{ editingId ? '编辑记忆' : '新建记忆' }}</h2></div><button class="memory-close" type="button" aria-label="关闭编辑" @click="closeEditor">×</button></header>
              <form class="memory-form" @submit.prevent="saveMemory">
                <div v-if="!editingId" class="memory-form-grid">
                  <label class="form-field"><span>记忆范围</span><select v-model="form.scope" data-testid="memory-form-scope" required @change="ensureProjectsLoaded"><option value="GLOBAL">通用记忆</option><option value="PROJECT">项目记忆</option></select></label>
                  <label v-if="form.scope === 'PROJECT'" class="form-field"><span>所属项目</span><select v-model="form.projectId" data-testid="memory-form-project" required><option :value="null" disabled>选择项目</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.identifier }}</option></select></label>
                </div>
                <label class="form-field"><span>分类</span><select v-model="form.category" required><option v-for="option in categoryOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
                <label class="form-field"><span>标题</span><input v-model="form.title" maxlength="200" required placeholder="例如：正文格式偏好"></label>
                <label class="form-field"><span>摘要 <small>用于主 Agent 预载</small></span><textarea v-model="form.summary" maxlength="300" rows="3" placeholder="简短描述这条长期偏好"></textarea></label>
                <label class="form-field"><span>正文 <small>最多 4000 字</small></span><textarea v-model="form.content" maxlength="4000" rows="8" required placeholder="描述需要长期遵循的偏好"></textarea></label>
                <p v-if="saveError" class="memory-form-error" role="alert">{{ saveError }}</p>
                <footer class="memory-form-actions"><AppButton variant="secondary" type="button" :disabled="saving" @click="closeEditor">取消</AppButton><AppButton type="submit" :busy="saving" busy-label="保存中…">保存记忆</AppButton></footer>
              </form>
            </template>
            <template v-else-if="selected">
              <header class="memory-detail-heading"><div><p>{{ scopeLabel(selected) }} · {{ categoryLabel(selected.category) }}</p><h2>{{ selected.title }}</h2></div><span :class="`memory-status memory-status--${selected.status.toLowerCase()}`">{{ statusLabel(selected.status) }}</span></header>
              <div class="memory-detail-meta"><span>来源：{{ sourceLabel(selected.sourceType) }}</span><span>使用 {{ selected.useCount }} 次</span><span>更新于 {{ formatDate(selected.updatedAt) }}</span></div>
              <article class="memory-detail-body"><h3>摘要</h3><p>{{ selected.summary || '未填写摘要，系统将使用正文前缀。' }}</p><h3>正文</h3><pre>{{ selected.content }}</pre></article>
              <section class="memory-audit"><h3>溯源与审计</h3><p v-if="selected.projectIdentifier">项目：{{ selected.projectIdentifier }}</p><p v-if="selected.sourceRunId">整理 run：#{{ selected.sourceRunId }}<span v-if="selected.sourceConversationId"> · 会话 #{{ selected.sourceConversationId }}</span></p><p>创建于 {{ formatDate(selected.createdAt) }}</p></section>
              <footer class="memory-detail-actions"><AppButton variant="secondary" @click="openEdit">编辑</AppButton><AppButton variant="secondary" :busy="statusSaving" busy-label="处理中…" @click="toggleStatus">{{ selected.status === 'ACTIVE' ? '停用' : '重新启用' }}</AppButton><AppButton variant="danger" :busy="deleting" busy-label="删除中…" @click="confirmDelete">删除</AppButton></footer>
            </template>
            <div v-else class="memory-state memory-state--detail"><IconGlyph name="user" /><strong>选择一条记忆</strong><p>查看摘要、来源和使用情况，或进行人工维护。</p></div>
          </section>
        </div>
      </section>
    </main>
    <ConfirmDialog :open="deleteOpen" title="删除这条记忆？" message="删除后记忆正文、来源和审计信息都不可恢复，请确认操作。" confirm-label="确认删除" danger :busy="deleting" @confirm="deleteMemory" @cancel="deleteOpen = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { memoryApi, type MemoryCategory, type MemoryScope, type MemoryStatus, type MemoryView } from '../api/memories'
import type { ProjectSummary } from '../api/types'
import { useProjectApi, useSession } from '../appContext'
import { ApiError } from '../api/http'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import IconGlyph from '../components/IconGlyph.vue'
import NoticeBanner from '../components/NoticeBanner.vue'
import PageHeader from '../components/PageHeader.vue'

const session = useSession()
const projectApi = useProjectApi()
const router = useRouter()
const identity = computed(() => session.identity.value)
const categoryOptions: Array<{ value: MemoryCategory; label: string }> = [
  { value: 'FORMAT', label: '格式偏好' }, { value: 'TEMPLATE', label: '模板偏好' },
  { value: 'CONTENT', label: '内容取舍' }, { value: 'STYLE', label: '语言风格' },
  { value: 'PROCESS', label: '流程习惯' }, { value: 'OTHER', label: '其他' },
]
const filters = reactive<{ keyword: string; scope: MemoryScope | ''; category: MemoryCategory | ''; status: MemoryStatus | '' }>({ keyword: '', scope: '', category: '', status: '' })
const page = ref({ total: 0, page: 1, size: 20, items: [] as MemoryView[] })
const selected = ref<MemoryView | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const editorOpen = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const statusSaving = ref(false)
const deleting = ref(false)
const deleteOpen = ref(false)
const saveError = ref('')
const projects = ref<ProjectSummary[]>([])
const projectsLoaded = ref(false)
const form = reactive({ scope: 'GLOBAL' as MemoryScope, projectId: null as number | null, category: 'FORMAT' as MemoryCategory, title: '', summary: '', content: '' })

async function loadMemories(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    page.value = await memoryApi.list({
      keyword: filters.keyword || undefined,
      scope: filters.scope || undefined,
      category: filters.category || undefined,
      status: filters.status || undefined,
      page: page.value.page,
      size: page.value.size,
    })
    if (selected.value) selected.value = page.value.items.find(item => item.id === selected.value?.id) ?? null
  } catch (failure) {
    errorMessage.value = failure instanceof ApiError ? failure.message : '暂时无法获取记忆列表，请稍后重试。'
  } finally { loading.value = false }
}

function changePage(nextPage: number): void { page.value.page = nextPage; void loadMemories() }
function selectMemory(memory: MemoryView): void { selected.value = memory; editorOpen.value = false }
function openCreate(): void {
  editingId.value = null; Object.assign(form, { scope: 'GLOBAL', projectId: null, category: 'FORMAT', title: '', summary: '', content: '' }); saveError.value = ''; editorOpen.value = true
}
function openEdit(): void {
  if (!selected.value) return
  editingId.value = selected.value.id
  Object.assign(form, { scope: selected.value.scope, projectId: selected.value.projectId, category: selected.value.category, title: selected.value.title, summary: selected.value.summary, content: selected.value.content })
  saveError.value = ''; editorOpen.value = true
}
function closeEditor(): void { editorOpen.value = false; editingId.value = null; saveError.value = '' }
async function ensureProjectsLoaded(): Promise<void> {
  if (projectsLoaded.value || form.scope !== 'PROJECT') return
  try { projects.value = await projectApi.listProjects(); projectsLoaded.value = true } catch { saveError.value = '项目列表加载失败，请稍后重试。' }
}
async function saveMemory(): Promise<void> {
  if (!form.title.trim() || !form.content.trim() || saving.value) return
  saving.value = true; saveError.value = ''
  try {
    const memory = editingId.value
      ? await memoryApi.update(editingId.value, { category: form.category, title: form.title.trim(), summary: form.summary.trim(), content: form.content.trim() })
      : await memoryApi.create({ scope: form.scope, projectId: form.scope === 'PROJECT' ? form.projectId : null, category: form.category, title: form.title.trim(), summary: form.summary.trim(), content: form.content.trim() })
    await loadMemories(); selected.value = memory; closeEditor()
  } catch (failure) { saveError.value = failure instanceof ApiError ? failure.message : '保存失败，请稍后重试。' } finally { saving.value = false }
}
async function toggleStatus(): Promise<void> {
  if (!selected.value || statusSaving.value) return
  statusSaving.value = true
  try { selected.value = await memoryApi.changeStatus(selected.value.id, selected.value.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await loadMemories() } catch (failure) { errorMessage.value = failure instanceof ApiError ? failure.message : '状态更新失败，请稍后重试。' } finally { statusSaving.value = false }
}
function confirmDelete(): void { deleteOpen.value = true }
async function deleteMemory(): Promise<void> {
  if (!selected.value || deleting.value) return
  deleting.value = true
  try { await memoryApi.delete(selected.value.id); deleteOpen.value = false; selected.value = null; await loadMemories() } catch (failure) { errorMessage.value = failure instanceof ApiError ? failure.message : '删除失败，请稍后重试。' } finally { deleting.value = false }
}
function categoryLabel(category: MemoryCategory): string { return categoryOptions.find(option => option.value === category)?.label ?? category }
function statusLabel(status: MemoryStatus): string { return status === 'ACTIVE' ? '启用' : '已停用' }
function sourceLabel(source: string): string { return source === 'MANUAL' ? '人工创建' : '知识整理沉淀' }
function scopeLabel(memory: MemoryView): string { return memory.scope === 'GLOBAL' ? '通用' : `项目 · ${memory.projectIdentifier ?? memory.projectId ?? '未知'}` }
function formatDate(value: string): string { return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value)) }
async function logout(): Promise<void> { await session.logout(); await router.replace('/login') }

onMounted(() => { void loadMemories() })
</script>
