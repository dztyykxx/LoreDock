import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { knowledgeSearchApi, type KnowledgeSearchResponse } from '../api/knowledgeSearch'
import { sessionKey } from '../appContext'
import type { SessionController } from '../session/useSession'
import GlobalSearchView from './GlobalSearchView.vue'

const response: KnowledgeSearchResponse = {
  context: { type: 'GLOBAL', projectIdentifier: null, branch: null },
  mode: 'HYBRID',
  generationId: 91,
  warnings: [],
  results: [
    {
      documentId: 11,
      scope: { type: 'GLOBAL', projectIdentifier: null, branch: null },
      title: '账号密码与登录安全规范',
      snippet: '密码重置必须由账号本人发起。',
      truncated: false,
      format: 'MARKDOWN',
      tags: ['账号安全'],
      source: { type: 'MANUAL', wikiUrl: null, originalFilename: null, curationNote: null },
      sourceUpdatedAt: '2026-08-10T00:00:00Z',
      relevance: 0.92,
      matchedBy: 'BOTH',
    },
    {
      documentId: 12,
      scope: { type: 'GLOBAL', projectIdentifier: null, branch: null },
      title: '内部系统账号恢复流程',
      snippet: '服务台核验身份后创建限时恢复链接。',
      truncated: false,
      format: 'MARKDOWN',
      tags: [],
      source: { type: 'WIKI', wikiUrl: 'https://wiki.example/recovery', originalFilename: null, curationNote: null },
      sourceUpdatedAt: '2026-08-09T00:00:00Z',
      relevance: 0.81,
      matchedBy: 'KEYWORD',
    },
    {
      documentId: 13,
      scope: { type: 'GLOBAL', projectIdentifier: null, branch: null },
      title: '访问凭据生命周期管理',
      snippet: '身份验证因素变化时需要重新确认恢复通道。',
      truncated: false,
      format: 'PLAIN_TEXT',
      tags: [],
      source: { type: 'UPLOAD', wikiUrl: null, originalFilename: 'credential.txt', curationNote: null },
      sourceUpdatedAt: '2026-08-08T00:00:00Z',
      relevance: 0.74,
      matchedBy: 'SEMANTIC',
    },
  ],
}

function session(): SessionController {
  return {
    status: ref('authenticated'),
    identity: ref({ username: 'member', displayName: '组内成员', role: 'MEMBER' }),
    restore: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    clear: vi.fn(),
  }
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', component: { template: '<div />' } },
      { path: '/search', name: 'global-search', component: GlobalSearchView },
      { path: '/knowledge', component: { template: '<div />' } },
      { path: '/knowledge/:documentId', name: 'knowledge-global-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/search')
  await router.isReady()
  return mount(GlobalSearchView, {
    global: { plugins: [router], provide: { [sessionKey as symbol]: session() } },
  })
}

describe('GlobalSearchView', () => {
  afterEach(() => vi.restoreAllMocks())

  /**
   * 业务目的：普通成员提交自然语言查询后必须直接看到混合检索文档及其真实候选来源，
   * 防止把向量命中伪装成精确命中，或只显示摘要而无法进入来源文档。
   */
  it('shows global hybrid results with exact match provenance and document links', async () => {
    const search = vi.spyOn(knowledgeSearchApi, 'searchGlobal').mockResolvedValue(response)
    const wrapper = await mountView()

    await wrapper.get('[data-testid="global-search-input"]').setValue('密码重置规则')
    await wrapper.get('[data-testid="global-search-form"]').trigger('submit')
    await flushPromises()

    expect(search).toHaveBeenCalledWith('密码重置规则')
    expect(wrapper.get('[data-testid="global-search-summary"]').text()).toContain('找到 3 篇文档')
    expect(wrapper.get('[data-testid="search-result-11"]').text()).toContain('精确 + 向量')
    expect(wrapper.get('[data-testid="search-result-12"]').text()).toContain('精确匹配')
    expect(wrapper.get('[data-testid="search-result-13"]').text()).toContain('向量匹配')
    expect(wrapper.get('[data-testid="search-result-11"] a').attributes('href')).toBe('/knowledge/11')
  })

  /**
   * 业务目的：混合检索没有命中时必须明确展示空结果而不扩大到项目知识，
   * 防止用户把静默空白误认为搜索仍在执行或范围自动变化。
   */
  it('keeps the global boundary when hybrid search returns no documents', async () => {
    vi.spyOn(knowledgeSearchApi, 'searchGlobal').mockResolvedValue({ ...response, results: [] })
    const wrapper = await mountView()

    await wrapper.get('[data-testid="global-search-input"]').setValue('不存在的内部术语')
    await wrapper.get('[data-testid="global-search-form"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="global-search-empty"]').text()).toContain('没有匹配的通用业务知识')
    expect(wrapper.text()).toContain('不会自动扩大到项目知识')
  })
})
