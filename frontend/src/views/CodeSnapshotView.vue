<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      :current-project="project ? { name: project.name, identifier: project.identifier } : undefined"
      @logout="logout"
    />
    <main class="app-main snapshot-main">
      <AppTopBar
        :project-name="project?.name ?? '代码快照'"
        :selected-branch="selectedBranch"
        :branches="branchNames"
        @branch-change="changeBranch"
      />

      <div v-if="loading" class="settings-state" aria-live="polite">正在加载代码快照…</div>
      <div v-else-if="loadError" class="settings-state settings-state--error" role="alert">
        <strong>代码快照加载失败</strong>
        <p>暂时无法读取项目或活动快照，请稍后重试。</p>
        <AppButton data-testid="retry-snapshot-page" variant="secondary" @click="loadPage">重新加载</AppButton>
      </div>

      <div v-else-if="project" class="snapshot-content">
        <ProjectHero
          :name="project.name"
          :identifier="project.identifier"
          :technology-stack="project.technologyStack"
          :status="project.status"
        />
        <ProjectTabs
          active="code-snapshots"
          :role="identity.role"
          :project-identifier="project.identifier"
          :project-id="project.id"
          :branch="selectedBranch"
        />
        <PageHeader
          breadcrumb="项目 / 代码快照"
          title="上传与索引"
          description="把指定 Commit 的代码 ZIP 建立为当前分支唯一活动快照。"
        />

        <section class="snapshot-metrics" aria-label="代码快照概览">
          <article class="snapshot-metric">
            <span class="snapshot-metric__icon"><IconGlyph name="branch" /></span>
            <div><small>当前分支</small><strong>{{ selectedBranch }}</strong><span>查询范围已锁定</span></div>
          </article>
          <article class="snapshot-metric">
            <span class="snapshot-metric__icon"><IconGlyph name="save" /></span>
            <div><small>活动 Commit</small><strong class="mono">{{ active?.commit ? shortCommit(active.commit) : '—' }}</strong><span>{{ active?.indexedAt ? formatTime(active.indexedAt) : '尚未上传' }}</span></div>
          </article>
          <article class="snapshot-metric">
            <span class="snapshot-metric__icon"><IconGlyph name="search" /></span>
            <div><small>索引状态</small><strong>{{ activeStatus }}</strong><span>{{ active?.indexedFileCount == null ? '无可查询文件' : `${active.indexedFileCount} 个文件` }}</span></div>
          </article>
        </section>

        <div class="snapshot-workspace">
          <section class="snapshot-upload-card">
            <header>
              <div><h2>替换 {{ selectedBranch }} 分支活动快照</h2><p>上传成功后，旧快照不再进入问答和搜索范围。</p></div>
              <span>ZIP · 最大 100MB</span>
            </header>

            <form data-testid="snapshot-upload-form" @submit.prevent="uploadSnapshot">
              <label
                class="snapshot-dropzone"
                :class="{ 'snapshot-dropzone--selected': selectedFile }"
                @dragover.prevent
                @drop.prevent="dropFile"
              >
                <input
                  data-testid="snapshot-file"
                  class="sr-only"
                  type="file"
                  accept=".zip,application/zip"
                  @change="chooseFile"
                >
                <span class="snapshot-dropzone__icon"><IconGlyph name="file" /></span>
                <strong>{{ selectedFile?.name ?? '拖入代码快照 ZIP，或点击选择文件' }}</strong>
                <span>{{ selectedFile ? fileSize(selectedFile.size) : '建议使用 git archive，确保内容与 Commit 一致' }}</span>
              </label>

              <div class="snapshot-form-grid">
                <label>
                  <span>目标分支</span>
                  <select data-testid="snapshot-branch" :value="selectedBranch" :disabled="jobBusy" @change="changeBranch(($event.target as HTMLSelectElement).value)">
                    <option v-for="branch in project.branches" :key="branch.id" :value="branch.name">{{ branch.name }}</option>
                  </select>
                </label>
                <label>
                  <span>Git Commit</span>
                  <input
                    data-testid="snapshot-commit"
                    v-model.trim="commit"
                    class="mono"
                    maxlength="64"
                    placeholder="7～64 位十六进制"
                    :disabled="jobBusy"
                  >
                </label>
              </div>

              <NoticeBanner tone="warning">
                仅索引安全文本文件；`.git`、构建产物、敏感路径、二进制和超过 2MB 的单文件会被忽略。
              </NoticeBanner>
              <p v-if="formError" class="snapshot-error" role="alert">{{ formError }}</p>
              <footer>
                <AppButton
                  type="submit"
                  icon="file"
                  :busy="submitting"
                  busy-label="正在上传…"
                  :disabled="jobBusy"
                >上传并建立索引</AppButton>
              </footer>
            </form>
          </section>

          <aside class="snapshot-side">
            <section class="snapshot-job-card" aria-live="polite">
              <header><div><h2>{{ currentJob ? '当前索引任务' : '活动快照' }}</h2><p>{{ currentJob ? shortCommit(currentJob.commit) : selectedBranch }}</p></div><span :class="`snapshot-job-status snapshot-job-status--${jobTone}`">{{ jobLabel }}</span></header>
              <template v-if="currentJob">
                <progress :value="currentJob.progress" max="100">{{ currentJob.progress }}%</progress>
                <div class="snapshot-job-progress"><strong>{{ currentJob.progress }}%</strong><span>{{ currentJob.indexedFileCount }} 个已索引 · {{ currentJob.ignoredFileCount }} 个已忽略</span></div>
                <p v-if="currentJob.failureCode" class="snapshot-error" role="alert">
                  {{ currentJob.failureSummary || '索引任务未完成' }}（{{ currentJob.failureCode }}）
                </p>
              </template>
              <p v-else-if="active?.status === 'INDEXED'">当前快照已可用于代码搜索和项目问答。</p>
              <p v-else>当前分支还没有代码快照，请上传 ZIP 开始建立索引。</p>
              <AppButton
                v-if="active?.status === 'INDEXED' && active.snapshotId"
                data-testid="reindex-snapshot"
                variant="secondary"
                icon="search"
                :disabled="jobBusy"
                @click="reindexSnapshot"
              >重新索引当前快照</AppButton>
            </section>

            <section class="snapshot-boundary-card">
              <h2>检索边界</h2>
              <p>代码只做文件路径和文本关键词索引，不进入知识向量索引。问答只读取当前项目、分支和活动 Commit 的有限片段。</p>
            </section>
          </aside>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ActiveCodeSnapshot, CodeSnapshotJob } from '../api/codeSnapshots'
import type { AdminProjectDetail } from '../api/types'
import { useCodeSnapshotApi, useProjectApi, useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AppTopBar from '../components/AppTopBar.vue'
import IconGlyph from '../components/IconGlyph.vue'
import NoticeBanner from '../components/NoticeBanner.vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectHero from '../components/ProjectHero.vue'
import ProjectTabs from '../components/ProjectTabs.vue'

const MAX_UPLOAD_BYTES = 100 * 1024 * 1024
const TERMINAL_JOB_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
const api = useCodeSnapshotApi()
const projects = useProjectApi()
const session = useSession()
const route = useRoute()
const router = useRouter()
const identity = computed(() => session.identity.value)
const project = ref<AdminProjectDetail | null>(null)
const selectedBranch = ref('')
const active = ref<ActiveCodeSnapshot | null>(null)
const currentJob = ref<CodeSnapshotJob | null>(null)
const selectedFile = ref<File | null>(null)
const commit = ref('')
const loading = ref(true)
const loadError = ref(false)
const submitting = ref(false)
const formError = ref('')
let pollTimer: number | null = null

const branchNames = computed(() => project.value?.branches.map(branch => branch.name) ?? [])
const selectedBranchView = computed(() => project.value?.branches.find(branch => branch.name === selectedBranch.value))
const jobBusy = computed(() => submitting.value
  || currentJob.value?.status === 'PENDING'
  || currentJob.value?.status === 'RUNNING')
const activeStatus = computed(() => active.value?.status === 'INDEXED' ? '可查询' : '未建立')
const jobLabel = computed(() => {
  if (!currentJob.value) return active.value?.status === 'INDEXED' ? '可查询' : '未建立'
  return ({ PENDING: '等待索引', RUNNING: '正在索引', SUCCEEDED: '索引完成', FAILED: '索引失败', CANCELLED: '已取消' } as const)[currentJob.value.status]
})
const jobTone = computed(() => {
  if (currentJob.value?.status === 'FAILED' || currentJob.value?.status === 'CANCELLED') return 'danger'
  if (currentJob.value?.status === 'PENDING' || currentJob.value?.status === 'RUNNING') return 'warning'
  return 'success'
})

onMounted(loadPage)
onUnmounted(clearPoll)

async function loadPage() {
  loading.value = true
  loadError.value = false
  try {
    const result = await projects.getAdminProject(Number(route.params.projectId))
    project.value = result
    const requested = typeof route.query.branch === 'string' ? route.query.branch : ''
    selectedBranch.value = result.branches.some(branch => branch.name === requested)
      ? requested
      : result.defaultBranch
    await loadActive()
  } catch {
    project.value = null
    active.value = null
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadActive() {
  if (!project.value || !selectedBranch.value) return
  active.value = await api.getActive(project.value.identifier, selectedBranch.value)
}

async function changeBranch(branch: string) {
  if (!project.value || branch === selectedBranch.value || jobBusy.value) return
  selectedBranch.value = branch
  active.value = null
  currentJob.value = null
  selectedFile.value = null
  formError.value = ''
  clearPoll()
  await router.replace({ query: branch === project.value.defaultBranch ? {} : { branch } })
  try {
    await loadActive()
  } catch {
    formError.value = '活动快照读取失败，请稍后重试。'
  }
}

function chooseFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  formError.value = ''
}

function dropFile(event: DragEvent) {
  selectedFile.value = event.dataTransfer?.files?.[0] ?? null
  formError.value = ''
}

async function uploadSnapshot() {
  formError.value = validateUpload()
  if (formError.value || !project.value || !selectedBranchView.value || !selectedFile.value) return
  submitting.value = true
  try {
    const job = await api.upload({
      projectId: project.value.id,
      branchId: selectedBranchView.value.id,
      commit: commit.value.toLowerCase(),
      file: selectedFile.value,
    })
    startWatching(job)
  } catch {
    formError.value = '代码快照上传失败，请检查文件、Commit 或当前分支任务状态。'
  } finally {
    submitting.value = false
  }
}

async function reindexSnapshot() {
  if (!active.value?.snapshotId || jobBusy.value) return
  formError.value = ''
  try {
    startWatching(await api.reindex(active.value.snapshotId))
  } catch {
    formError.value = '重新索引任务创建失败，请稍后重试。'
  }
}

function startWatching(job: CodeSnapshotJob) {
  currentJob.value = job
  if (TERMINAL_JOB_STATUSES.has(job.status)) {
    void finishJob(job)
  } else {
    schedulePoll()
  }
}

function schedulePoll() {
  clearPoll()
  pollTimer = window.setTimeout(async () => {
    if (!currentJob.value) return
    try {
      const latest = await api.getJob(currentJob.value.jobId)
      currentJob.value = latest
      if (TERMINAL_JOB_STATUSES.has(latest.status)) {
        await finishJob(latest)
      } else {
        schedulePoll()
      }
    } catch {
      formError.value = '索引任务状态刷新失败，请重新加载页面确认结果。'
    }
  }, 1000)
}

async function finishJob(job: CodeSnapshotJob) {
  clearPoll()
  if (job.status === 'SUCCEEDED') {
    await loadActive()
    selectedFile.value = null
  }
}

function clearPoll() {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

function validateUpload(): string {
  if (!selectedFile.value || !selectedFile.value.name.toLowerCase().endsWith('.zip')) {
    return '请选择 ZIP 文件。'
  }
  if (selectedFile.value.size > MAX_UPLOAD_BYTES) {
    return 'ZIP 文件不能超过 100MB。'
  }
  if (!/^[0-9a-fA-F]{7,64}$/.test(commit.value)) {
    return 'Commit 必须是 7～64 位十六进制。'
  }
  return ''
}

function shortCommit(value: string): string {
  return value.slice(0, 12)
}

function fileSize(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

async function logout() {
  await session.logout()
  await router.replace('/login')
}
</script>
