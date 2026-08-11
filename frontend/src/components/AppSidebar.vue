<template>
  <aside class="app-sidebar">
    <RouterLink class="sidebar-brand" to="/projects" aria-label="LoreDock 项目列表">
      <span class="brand-mark">L</span><strong>LoreDock</strong>
    </RouterLink>

    <RouterLink data-testid="sidebar-new-question-link" class="sidebar-new-question" :to="newQuestionTarget">
      <IconGlyph name="message" />新建问答
    </RouterLink>

    <nav class="sidebar-nav" aria-label="主导航">
      <RouterLink data-testid="sidebar-qa-link" :to="qaTarget"><IconGlyph name="message" />问答</RouterLink>
      <RouterLink to="/projects"><IconGlyph name="folder" />项目</RouterLink>
      <RouterLink data-testid="global-knowledge-link" to="/knowledge"><IconGlyph name="book" />通用业务知识</RouterLink>
      <RouterLink data-testid="global-search-link" to="/search"><IconGlyph name="search" />全局搜索</RouterLink>
    </nav>

    <div v-if="currentProject" class="sidebar-project">
      <p>当前项目</p>
      <RouterLink data-testid="current-project-link" class="sidebar-project__card" :to="`/projects/${currentProject.identifier}`">
        <span class="project-icon"><IconGlyph name="network" /></span>
        <span><strong>{{ currentProject.name }}</strong><small>{{ currentProject.identifier }}</small></span>
      </RouterLink>
    </div>
    <div v-else class="sidebar-recent">
      <p>最近问答</p>
      <button
        v-for="conversation in recentConversations"
        :key="conversation.conversationId"
        type="button"
        @click="openConversation(conversation)"
      >
        <span :title="conversation.title">{{ conversation.title }}</span>
        <em class="qa-recent__scope">{{ scopeLabel(conversation) }}</em>
      </button>
      <p v-if="recentConversations.length === 0" class="sidebar-recent__empty">还没有问答记录</p>
      <button v-if="recentCursor" class="qa-load-more" type="button" :disabled="loadingRecent" @click="loadEarlier">
        加载更早记录
      </button>
    </div>

    <div class="sidebar-profile">
      <span class="sidebar-avatar">{{ role === 'ADMIN' ? '管' : '阅' }}</span>
      <span><strong>{{ displayName }}</strong><small>{{ role === 'ADMIN' ? '内容与项目维护' : '只读浏览与问答' }}</small></span>
      <button type="button" aria-label="退出登录" @click="$emit('logout')"><IconGlyph name="logout" /></button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { inject, onMounted, ref, computed } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import type { WebRole } from '../api/types'
import { scopeLabel, type QaConversationSummary } from '../api/qa'
import { qaApiKey } from '../appContext'
import IconGlyph from './IconGlyph.vue'

const props = defineProps<{
  displayName: string
  role: WebRole
  currentProject?: { name: string; identifier: string }
}>()

const router = useRouter()
// 侧栏最近问答是增强能力：测试或无 QA 上下文时静默跳过，不影响主导航。
const api = inject(qaApiKey, null)
// 无项目上下文时"问答"进入全局（全库）问答页；项目内仍进入项目问答。
const qaTarget = computed(() => props.currentProject
  ? `/projects/${props.currentProject.identifier}/qa`
  : '/qa')
const newQuestionTarget = computed(() => props.currentProject
  ? { path: `/projects/${props.currentProject.identifier}/qa`, query: { new: '1' } }
  : { path: '/qa', query: { new: '1' } })

// 首页侧栏的最近问答：跨全局与各项目的分页会话历史，每项标注检索范围。
const recentConversations = ref<QaConversationSummary[]>([])
const recentCursor = ref<string | null>(null)
const loadingRecent = ref(false)

async function loadRecent(cursor?: string): Promise<void> {
  if (!api || loadingRecent.value) return
  loadingRecent.value = true
  try {
    const page = await api.conversationsGlobal(cursor, 10)
    recentConversations.value = cursor ? [...recentConversations.value, ...page.items] : page.items
    recentCursor.value = page.nextCursor
  } catch {
    // 侧栏最近问答加载失败保持静默；主导航与页面内容不受影响。
  } finally {
    loadingRecent.value = false
  }
}

async function loadEarlier(): Promise<void> {
  if (!recentCursor.value) return
  await loadRecent(recentCursor.value)
}

function openConversation(conversation: QaConversationSummary): void {
  const query = { conversationId: String(conversation.conversationId) }
  void router.push(conversation.scope === 'GLOBAL'
    ? { path: '/qa', query }
    : { path: `/projects/${conversation.projectIdentifier}/qa`, query })
}

onMounted(() => {
  if (!props.currentProject) {
    void loadRecent()
  }
})

defineEmits<{ logout: [] }>()
</script>
