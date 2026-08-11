<template>
  <div v-if="identity" class="qa-workspace">
    <aside class="qa-sidebar">
      <RouterLink class="sidebar-brand" to="/projects" aria-label="LoreDock 项目列表">
        <span class="brand-mark">L</span><strong>LoreDock</strong>
      </RouterLink>
      <button class="qa-new-question" type="button" @click="startNewQuestion"><IconGlyph name="plus" />新建问答</button>
      <QaRecentSidebar :items="conversations" :selected-id="currentConversation?.conversationId ?? null" @select="selectConversation" />
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
          <span class="project-icon"><IconGlyph name="book" /></span>
          <div><strong>全局问答</strong><small>全数据库检索</small></div>
        </div>
        <div class="qa-topbar__controls">
          <span class="qa-scope-status"><IconGlyph name="lock" />范围已锁定</span>
          <button v-if="current" ref="topSourceButton" class="qa-source-count" type="button" @click="openCitations($event.currentTarget as HTMLElement)">
            来源 {{ current.citations.length }}
          </button>
        </div>
      </header>

      <section v-if="current" data-testid="locked-scope" class="qa-range-lock">
        <div><IconGlyph name="lock" /><strong>本次问答范围</strong></div>
        <span>全局</span>
        <span>通用知识与所有项目已发布文档，不含分支</span>
      </section>

      <section class="qa-conversation">
        <div v-if="loadError && conversations.length === 0" class="qa-page-state qa-page-state--error" role="alert">
          <strong>问答历史加载失败</strong><p>{{ loadError }}</p><button type="button" @click="reloadConversations">重新加载</button>
        </div>
        <div v-else-if="!current" class="qa-empty-state">
          <span><IconGlyph name="message" /></span>
          <h1>还没有问答</h1>
          <p>向全数据库提出一个具体问题，回答会引用通用知识与各项目的已发布文档。</p>
        </div>
        <template v-else>
          <div v-for="round in rounds" :key="round.questionId" class="qa-round">
          <article class="qa-user-message">
            <div><strong>你</strong></div>
            <p>{{ questionText(round) }}</p>
          </article>
          <QaAnswerPanel
            :snapshot="round"
            :partial-text="round.questionId === current?.questionId ? partialText : (round.resultText ?? '')"
            :process-events="round.questionId === current?.questionId ? processEvents : round.processEvents"
            :connection-state="round.questionId === current?.questionId ? connectionState : 'idle'"
            :show-feedback="false"
            @retry="retryRound(round)"
            @open-citations="openRoundCitations(round, $event)"
          />
          </div>
        </template>
      </section>

      <div class="qa-composer-wrap">
        <QaQuestionComposer ref="composer" :busy="submitting" :error="submitError" @submit="submitQuestion" />
      </div>
    </main>

    <QaCitationDrawer
      v-if="citationsOpen && detailTarget"
      :snapshot="detailTarget"
      :return-focus-to="citationTrigger"
      @close="closeCitations"
    />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { QaQuestion } from '../api/qa'
import { useQaApi, useSession } from '../appContext'
import IconGlyph from '../components/IconGlyph.vue'
import QaAnswerPanel from '../components/QaAnswerPanel.vue'
import QaCitationDrawer from '../components/QaCitationDrawer.vue'
import QaQuestionComposer from '../components/QaQuestionComposer.vue'
import QaRecentSidebar from '../components/QaRecentSidebar.vue'
import { createProjectQaController } from '../qa/useProjectQa'

const route = useRoute()
const router = useRouter()
const session = useSession()
const api = useQaApi()
const citationsOpen = ref(false)
const citationTrigger = ref<HTMLElement | null>(null)
const detailTarget = ref<QaQuestion | null>(null)
const composer = ref<InstanceType<typeof QaQuestionComposer> | null>(null)
// identifier 为空即全局模式：创建、详情与事件流走 /api/qa 全库端点。
const qa = createProjectQaController(api, null)
const {
  conversations, currentConversation, rounds, processEvents, nextCursor, current, loading, submitting,
  loadError, submitError, connectionState, partialText,
} = qa
const identity = session.identity

async function initialize(): Promise<void> {
  await reloadConversations()
  const deepLink = route.query.conversationId
  if (typeof deepLink === 'string' && /^\d+$/.test(deepLink)) {
    await selectConversation(Number(deepLink))
  }
  if (route.query.new === '1') {
    const query = { ...route.query }
    delete query.new
    await router.replace({ query })
    await nextTick()
    composer.value?.focus()
  }
}

async function reloadConversations(): Promise<void> {
  try {
    await qa.loadConversations()
    if (route.query.new !== '1' && route.query.conversationId === undefined
      && !current.value && conversations.value[0]) {
      await qa.selectConversation(conversations.value[0].conversationId)
    }
  } catch {
    // composable 已保存可展示的稳定错误。
  }
}

async function loadMore(): Promise<void> {
  if (!nextCursor.value) return
  try {
    await qa.loadConversations(nextCursor.value)
  } catch {
    // 保留已有历史，页面错误状态允许用户再次尝试。
  }
}

async function selectConversation(conversationId: number): Promise<void> {
  citationsOpen.value = false
  try {
    await qa.selectConversation(conversationId)
  } catch {
    // 统一错误由页面状态显示。
  }
}

function startNewQuestion(): void {
  qa.dispose()
  current.value = null
  currentConversation.value = null
  rounds.value = []
  processEvents.value = []
  partialText.value = ''
  citationsOpen.value = false
}

async function submitQuestion(question: string): Promise<void> {
  try {
    await qa.submit(question)
    composer.value?.clear()
  } catch {
    // 创建响应不确定时 composable 保留原幂等键和输入，用户可安全重试。
  }
}

async function retryRound(round: QaQuestion): Promise<void> {
  try {
    await qa.retry(questionText(round))
  } catch {
    // 主动重试始终使用新幂等键。
  }
}

function openCitations(trigger: HTMLElement): void {
  detailTarget.value = current.value
  citationTrigger.value = trigger
  citationsOpen.value = true
}

function openRoundCitations(round: QaQuestion, trigger: HTMLElement): void {
  detailTarget.value = round
  citationTrigger.value = trigger
  citationsOpen.value = true
}

function questionText(round: QaQuestion): string {
  return round.messages.find(message => message.role === 'USER')?.content ?? '未保存问题正文'
}

async function closeCitations(): Promise<void> {
  const target = document.querySelector<HTMLElement>('[data-testid="open-citations"]')
  citationsOpen.value = false
  await nextTick()
  target?.focus()
}

async function logout(): Promise<void> {
  await session.logout()
  await router.replace('/login')
}

onMounted(initialize)
onBeforeUnmount(qa.dispose)
</script>
