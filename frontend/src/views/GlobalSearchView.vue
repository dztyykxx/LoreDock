<template>
  <div v-if="identity" class="app-shell">
    <AppSidebar
      :display-name="identity.displayName"
      :role="identity.role"
      @logout="logout"
    />
    <main class="app-main global-search-main">
      <header class="list-topbar">
        <div><span>工作空间</span><IconGlyph name="chevronRight" /><strong>全局搜索</strong></div>
      </header>

      <section class="global-search-content">
        <PageHeader
          breadcrumb="知识检索 / GLOBAL"
          title="全局搜索"
          description="仅检索通用业务知识，不混入任何项目内容。"
        />

        <form data-testid="global-search-form" class="global-search-form" role="search" @submit.prevent="search">
          <label class="global-search-field">
            <IconGlyph name="search" />
            <span class="sr-only">搜索通用业务知识</span>
            <input
              v-model="query"
              data-testid="global-search-input"
              type="search"
              maxlength="500"
              autocomplete="off"
              placeholder="输入业务问题、术语或规则"
            >
          </label>
          <AppButton type="submit" :busy="loading" :disabled="!query.trim()" busy-label="检索中…">搜索</AppButton>
        </form>

        <div v-if="loading" class="global-search-state" aria-live="polite">
          <IconGlyph name="search" />
          <strong>正在执行混合检索…</strong>
          <p>同时查询关键词与向量候选，并按相关度融合排序。</p>
        </div>
        <div v-else-if="errorMessage" class="global-search-state global-search-state--error" role="alert">
          <IconGlyph name="warning" />
          <strong>全局搜索失败</strong>
          <p>{{ errorMessage }}</p>
          <AppButton variant="secondary" @click="search">重新搜索</AppButton>
        </div>
        <template v-else-if="searched && response">
          <div data-testid="global-search-summary" class="global-search-summary">
            <div>
              <span>混合检索</span><i />
              <span>关键词 + 向量</span><i />
              <strong>找到 {{ response.results.length }} 篇文档</strong>
            </div>
            <div class="global-search-legend" aria-label="匹配来源说明">
              <span class="search-match search-match--both"><i />精确 + 向量</span>
              <span class="search-match search-match--keyword"><i />精确匹配</span>
              <span class="search-match search-match--semantic"><i />向量匹配</span>
            </div>
          </div>

          <div v-if="response.results.length === 0" data-testid="global-search-empty" class="global-search-state">
            <IconGlyph name="search" />
            <strong>没有匹配的通用业务知识</strong>
            <p>请尝试更换说法；全局搜索不会自动扩大到项目知识。</p>
          </div>
          <section v-else class="global-search-results" aria-label="混合检索结果">
            <article
              v-for="result in response.results"
              :key="result.documentId"
              :data-testid="`search-result-${result.documentId}`"
              class="global-search-result"
            >
              <header>
                <span class="global-search-result__icon" :class="`global-search-result__icon--${result.matchedBy.toLowerCase()}`">
                  <IconGlyph name="file" />
                </span>
                <div>
                  <p>通用业务知识 · {{ sourceLabel(result.source.type) }} · 文档 #{{ result.documentId }}</p>
                  <h2>{{ result.title }}</h2>
                </div>
                <span class="search-match" :class="matchClass(result.matchedBy)"><i />{{ matchLabel(result.matchedBy) }}</span>
                <span class="global-search-result__relevance"><small>相关度</small><strong>{{ relevanceLabel(result.relevance) }}</strong></span>
              </header>
              <p class="global-search-result__snippet">{{ result.snippet }}<template v-if="result.truncated">…</template></p>
              <footer>
                <div>
                  <span v-for="tag in result.tags" :key="tag">{{ tag }}</span>
                  <small>更新于 {{ formatDate(result.sourceUpdatedAt) }}</small>
                </div>
                <RouterLink :to="`/knowledge/${result.documentId}`">查看文档 <IconGlyph name="arrowRight" /></RouterLink>
              </footer>
            </article>
          </section>
          <p v-if="response.results.length" class="global-search-footnote">匹配来源仅表示本次候选进入方式；结果仍按混合检索相关度统一排序。</p>
        </template>
        <div v-else class="global-search-state global-search-state--initial">
          <IconGlyph name="search" />
          <strong>搜索通用业务知识</strong>
          <p>支持自然语言和关键词，结果会标明来自精确匹配、向量匹配或两者共同命中。</p>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { knowledgeSearchApi, type KnowledgeSearchMatchedBy, type KnowledgeSearchResponse } from '../api/knowledgeSearch'
import { useSession } from '../appContext'
import AppButton from '../components/AppButton.vue'
import AppSidebar from '../components/AppSidebar.vue'
import IconGlyph from '../components/IconGlyph.vue'
import PageHeader from '../components/PageHeader.vue'

const session = useSession()
const route = useRoute()
const router = useRouter()
const identity = computed(() => session.identity.value)
const query = ref('')
const loading = ref(false)
const searched = ref(false)
const errorMessage = ref('')
const response = ref<KnowledgeSearchResponse | null>(null)

async function search(): Promise<void> {
  const keyword = query.value.trim()
  if (!keyword || loading.value) return

  loading.value = true
  searched.value = true
  errorMessage.value = ''
  response.value = null
  try {
    await router.replace({ path: '/search', query: { q: keyword } })
    response.value = await knowledgeSearchApi.searchGlobal(keyword)
  } catch (failure) {
    errorMessage.value = failure instanceof ApiError
      ? failure.message
      : '暂时无法获取搜索结果，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function matchLabel(matchedBy: KnowledgeSearchMatchedBy): string {
  return ({ BOTH: '精确 + 向量', KEYWORD: '精确匹配', SEMANTIC: '向量匹配' } as const)[matchedBy]
}

function matchClass(matchedBy: KnowledgeSearchMatchedBy): string {
  return `search-match--${matchedBy.toLowerCase()}`
}

function relevanceLabel(relevance: number): string {
  return `${Math.round(relevance * 100)}%`
}

function sourceLabel(source: string): string {
  return ({ MANUAL: '人工整理', WIKI: '原 Wiki', UPLOAD: '文件上传' } as Record<string, string>)[source] ?? source
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(value))
}

async function logout(): Promise<void> {
  await session.logout()
  await router.replace('/login')
}

onMounted(() => {
  if (typeof route.query.q === 'string' && route.query.q.trim()) {
    query.value = route.query.q.trim()
    void search()
  }
})
</script>
