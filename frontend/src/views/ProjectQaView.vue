<template>
  <div v-if="identity" class="qa-workspace">
    <aside class="qa-sidebar">
      <RouterLink class="sidebar-brand" to="/projects" aria-label="LoreDock 项目列表">
        <span class="brand-mark">L</span><strong>LoreDock</strong>
      </RouterLink>
      <button class="qa-new-question" type="button" @click="startNewQuestion"><IconGlyph name="plus" />新建问答</button>
      <RouterLink v-if="project" class="qa-project-back" :to="`/projects/${project.identifier}`">
        <IconGlyph name="folder" /><span><strong>{{ project.name }}</strong><small>返回项目知识</small></span>
      </RouterLink>
      <QaRecentSidebar :items="history" :selected-id="current?.questionId ?? null" @select="selectQuestion" />
      <button v-if="nextCursor" class="qa-load-more" type="button" :disabled="loading" @click="loadMore">加载更早记录</button>
      <div class="sidebar-profile">
        <span class="sidebar-avatar">{{ identity.role === 'ADMIN' ? '管' : '阅' }}</span>
        <span><strong>{{ identity.displayName }}</strong><small>{{ identity.role === 'ADMIN' ? '内容与项目维护' : '只读浏览与问答' }}</small></span>
        <button type="button" aria-label="退出登录" @click="logout"><IconGlyph name="logout" /></button>
      </div>
    </aside>

    <main class="qa-main">
      <header class="qa-topbar">
        <div class="qa-topbar__project">
          <span class="project-icon"><IconGlyph name="network" /></span>
          <div><strong>{{ project?.name || '正在加载项目' }}</strong><small>{{ identifier }}</small></div>
        </div>
        <div class="qa-topbar__controls">
          <label class="branch-selector">
            <span class="sr-only">下一题使用的分支</span>
            <IconGlyph name="branch" />
            <select data-testid="qa-branch-selector" :value="selectedBranch" :disabled="!project" @change="changeBranch(($event.target as HTMLSelectElement).value)">
              <option v-for="branch in project?.branches ?? []" :key="branch.id" :value="branch.name">{{ branch.name }}</option>
            </select>
          </label>
          <span class="qa-scope-status"><IconGlyph name="lock" />范围已锁定</span>
          <button v-if="current" ref="topSourceButton" class="qa-source-count" type="button" @click="openCitations($event.currentTarget as HTMLElement)">
            来源 {{ current.citations.length }}
          </button>
        </div>
      </header>

      <section v-if="projectError" class="qa-page-state qa-page-state--error" role="alert">
        <IconGlyph name="warning" /><strong>无法打开项目问答</strong><p>项目可能不存在、已停用或当前会话无权访问。</p>
      </section>

      <template v-else>
        <section v-if="current" data-testid="locked-scope" class="qa-range-lock">
          <div><IconGlyph name="lock" /><strong>本次问答范围</strong></div>
          <span>{{ current.scope.projectIdentifier }}</span><code>{{ current.scope.branch }}</code>
          <code v-if="current.scope.commit">{{ current.scope.commit }}</code>
          <span v-else>无活动代码快照</span>
        </section>

        <section class="qa-conversation">
          <div v-if="loading && !project" class="qa-page-state" aria-live="polite">正在加载项目范围…</div>
          <div v-else-if="loadError && history.length === 0" class="qa-page-state qa-page-state--error" role="alert">
            <strong>问答历史加载失败</strong><p>{{ loadError }}</p><button type="button" @click="reloadHistory">重新加载</button>
          </div>
          <div v-else-if="!current" class="qa-empty-state">
            <span><IconGlyph name="message" /></span>
            <h1>还没有问答</h1>
            <p>向 {{ project?.name || identifier }} 提出一个具体问题。每次问题都会建立独立运行，只使用所选项目和分支内的证据。</p>
          </div>
          <template v-else>
            <article class="qa-user-message">
              <div><strong>你</strong><small>{{ current.scope.branch }}</small></div>
              <p>{{ currentQuestion }}</p>
            </article>
            <QaAnswerPanel
              :snapshot="current"
              :partial-text="partialText"
              :connection-state="connectionState"
              @retry="retryCurrent"
              @open-citations="openCitations"
              @feedback="feedbackOpen = true"
            />
          </template>
        </section>

        <div class="qa-composer-wrap">
          <QaQuestionComposer ref="composer" :busy="submitting" :error="submitError" @submit="submitQuestion" />
        </div>
      </template>
    </main>

    <QaCitationDrawer
      v-if="citationsOpen && current"
      :snapshot="current"
      :return-focus-to="citationTrigger"
      @close="citationsOpen = false"
    />
    <div v-if="feedbackOpen && current" class="qa-modal-backdrop" @click.self="feedbackOpen = false">
      <KnowledgeGapDialog :api="api" :snapshot="current" @close="feedbackOpen = false" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { ProjectDetail } from '../api/types'
import { useProjectApi, useQaApi, useSession } from '../appContext'
import IconGlyph from '../components/IconGlyph.vue'
import KnowledgeGapDialog from '../components/KnowledgeGapDialog.vue'
import QaAnswerPanel from '../components/QaAnswerPanel.vue'
import QaCitationDrawer from '../components/QaCitationDrawer.vue'
import QaQuestionComposer from '../components/QaQuestionComposer.vue'
import QaRecentSidebar from '../components/QaRecentSidebar.vue'
import { createProjectQaController } from '../qa/useProjectQa'

const route = useRoute()
const router = useRouter()
const session = useSession()
const projects = useProjectApi()
const api = useQaApi()
const identifier = String(route.params.identifier)
const selectedBranch = ref(queryBranch() ?? 'main')
const project = ref<ProjectDetail | null>(null)
const projectError = ref(false)
const citationsOpen = ref(false)
const feedbackOpen = ref(false)
const citationTrigger = ref<HTMLElement | null>(null)
const composer = ref<InstanceType<typeof QaQuestionComposer> | null>(null)
const qa = createProjectQaController(api, identifier, () => selectedBranch.value)
const { history, nextCursor, current, loading, submitting, loadError, submitError, connectionState, partialText } = qa
const identity = computed(() => session.identity.value)
const currentQuestion = computed(() => current.value?.messages.find(message => message.role === 'USER')?.content ?? '未保存问题正文')

async function initialize(): Promise<void> {
  loading.value = true
  try {
    project.value = await projects.getProject(identifier, queryBranch())
    selectedBranch.value = project.value.selectedBranch || project.value.defaultBranch
  } catch {
    projectError.value = true
    loading.value = false
    return
  }
  await reloadHistory()
}

async function reloadHistory(): Promise<void> {
  try {
    await qa.loadHistory()
    if (!current.value && history.value[0]) await qa.selectQuestion(history.value[0].questionId)
  } catch {
    // composable 已保存可展示的稳定错误，不覆盖已成功加载的项目范围。
  }
}

async function loadMore(): Promise<void> {
  if (!nextCursor.value) return
  try {
    await qa.loadHistory(nextCursor.value)
  } catch {
    // 保留已有历史，页面错误状态允许用户再次尝试。
  }
}

async function selectQuestion(questionId: string): Promise<void> {
  citationsOpen.value = false
  feedbackOpen.value = false
  try {
    await qa.selectQuestion(questionId)
  } catch {
    // 当前范围内的统一错误由页面状态显示，不复用上一条详情。
  }
}

function startNewQuestion(): void {
  qa.dispose()
  current.value = null
  partialText.value = ''
  citationsOpen.value = false
  feedbackOpen.value = false
}

async function submitQuestion(question: string): Promise<void> {
  try {
    await qa.submit(question)
    composer.value?.clear()
  } catch {
    // 创建响应不确定时 composable 保留原幂等键和输入，用户可安全重试。
  }
}

async function retryCurrent(): Promise<void> {
  try {
    await qa.retry(currentQuestion.value)
  } catch {
    // 主动重试错误保留在当前页面，且下一次主动重试仍会生成新键。
  }
}

async function changeBranch(branch: string): Promise<void> {
  selectedBranch.value = branch
  await router.replace({ query: branch === project.value?.defaultBranch ? {} : { branch } })
}

function openCitations(trigger: HTMLElement): void {
  citationTrigger.value = trigger
  citationsOpen.value = true
}

async function logout(): Promise<void> {
  await session.logout()
  await router.replace('/login')
}

function queryBranch(): string | undefined {
  return typeof route.query.branch === 'string' ? route.query.branch : undefined
}

onMounted(initialize)
onBeforeUnmount(qa.dispose)
</script>
