import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import type { ProjectApi } from '../api/projects'
import type { QaApi, QaQuestion } from '../api/qa'
import type { ProjectDetail, SessionView } from '../api/types'
import { projectApiKey, qaApiKey, sessionKey } from '../appContext'
import type { SessionController } from '../session/useSession'
import ProjectQaView from './ProjectQaView.vue'

const project: ProjectDetail = {
  id: 1,
  identifier: 'network-designer',
  name: '网络设计工具',
  description: '网络拓扑设计',
  technologyStack: 'Java 21 + Vue 3',
  defaultBranch: 'main',
  selectedBranch: 'main',
  branches: [
    { id: 11, name: 'main', createdAt: '', updatedAt: '', createdBy: 'admin', updatedBy: 'admin' },
    { id: 12, name: 'feature/import', createdAt: '', updatedAt: '', createdBy: 'admin', updatedBy: 'admin' },
  ],
}

function snapshot(overrides: Partial<QaQuestion> = {}): QaQuestion {
  return {
    questionId: 61, conversationId: 51, runId: 71, createdAt: '2026-07-31T08:00:00Z',
    scope: { projectIdentifier: 'network-designer', branch: 'feature/import', commit: null, codeSnapshotAvailable: false },
    status: 'COMPLETED', resultType: 'ANSWER', trustState: 'RELIABLE_ANSWER', answerBasis: 'BUSINESS_RULE', refusalReason: null, errorCode: null,
    failureMessage: null, processEvents: [],
    resultText: '服务端固定到运行创建时的范围。', stepCount: 2, modelCallCount: 1, lastEventSequence: 8,
    messages: [{ id: 81, role: 'USER', content: '当前范围是什么？', resultType: null, refusalReason: null, createdAt: '2026-07-31T08:00:00Z' }],
    citations: [], ...overrides,
  }
}

function qaApi(overrides: Partial<QaApi> = {}): QaApi {
  return {
    conversations: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    conversation: vi.fn(),
    history: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    detail: vi.fn(), createQuestion: vi.fn(), createKnowledgeGap: vi.fn(),
    openEventStream: vi.fn().mockReturnValue({ close: vi.fn() }),
    conversationsGlobal: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    conversationGlobal: vi.fn(),
    historyGlobal: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    detailGlobal: vi.fn(), createQuestionGlobal: vi.fn(),
    openEventStreamGlobal: vi.fn().mockReturnValue({ close: vi.fn() }), ...overrides,
  }
}

function conversationSummary() {
  return {
    conversationId: 51,
    projectIdentifier: 'network-designer',
    projectName: '网络设计',
    scope: 'PROJECT' as const,
    title: '当前范围是什么？',
    lastQuestion: '当前范围是什么？',
    status: 'COMPLETED' as const,
    createdAt: '2026-07-31T08:00:00Z',
    updatedAt: '2026-07-31T08:00:00Z',
    lastQuestionAt: '2026-07-31T08:00:00Z',
  }
}

async function mountView(path: string, qa: QaApi) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:identifier/qa', component: ProjectQaView },
      { path: '/projects/:identifier', component: { template: '<div />' } },
      { path: '/projects', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const identity: SessionView = { username: 'member', displayName: '组内成员', role: 'MEMBER' }
  const session: SessionController = {
    status: ref('authenticated'), identity: ref(identity), restore: vi.fn(), login: vi.fn(), logout: vi.fn(), clear: vi.fn(),
  }
  const projects: ProjectApi = {
    listProjects: vi.fn(),
    getProject: vi.fn().mockResolvedValue(project),
    getAdminProject: vi.fn(), createProject: vi.fn(), changeStatus: vi.fn(),
  }
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ProjectQaView, {
    global: { plugins: [router], provide: { [sessionKey as symbol]: session, [projectApiKey as symbol]: projects, [qaApiKey as symbol]: qa } },
  })
  await flushPromises()
  return { wrapper, router, projects }
}

describe('ProjectQaView', () => {
  /**
   * 业务目的：即使旧链接仍带分支参数，项目问答也必须忽略它并使用默认项目范围。
   */
  it('ignores legacy branch queries and renders a usable empty workspace', async () => {
    const qa = qaApi()
    const { wrapper, projects } = await mountView('/projects/network-designer/qa?branch=feature%2Fimport', qa)

    expect(projects.getProject).toHaveBeenCalledWith('network-designer')
    expect(qa.conversations).toHaveBeenCalledWith('network-designer', undefined)
    expect(wrapper.text()).toContain('网络设计工具')
    expect(wrapper.text()).toContain('还没有问答')
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect(wrapper.find('[data-testid="qa-branch-selector"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('feature/import')
    expect(wrapper.text()).not.toContain('场景包导入后为何刷新拓扑')
  })

  /**
   * 业务目的：侧栏新建问答必须打开空白会话并保留历史列表，不能自动重新选中上一条问题。
   */
  it('opens an empty composer when new question navigation is requested', async () => {
    const existing = snapshot()
    const qa = qaApi({
      conversations: vi.fn().mockResolvedValue({ items: [conversationSummary()], nextCursor: null }),
      conversation: vi.fn().mockResolvedValue({ conversation: conversationSummary(), rounds: [existing] }),
    })
    const { wrapper, router } = await mountView('/projects/network-designer/qa?new=1', qa)

    expect(qa.conversations).toHaveBeenCalled()
    expect(qa.conversation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('还没有问答')
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect(router.currentRoute.value.query.new).toBeUndefined()
  })

  /**
   * 业务目的：问答创建请求不得包含分支参数，服务端响应中的历史分支也不能重新成为前端操作入口。
   */
  it('creates questions without exposing or sending branch scope', async () => {
    const existing = snapshot()
    const accepted = snapshot({ questionId: 64, runId: 72, status: 'ACCEPTED', resultType: null, trustState: 'IN_PROGRESS', answerBasis: null, resultText: null, scope: { ...existing.scope, branch: 'main' } })
    const qa = qaApi({
      conversations: vi.fn().mockResolvedValue({ items: [conversationSummary()], nextCursor: null }),
      conversation: vi.fn().mockResolvedValue({ conversation: conversationSummary(), rounds: [existing] }),
      createQuestion: vi.fn().mockResolvedValue(accepted),
    })
    const { wrapper } = await mountView('/projects/network-designer/qa?branch=feature%2Fimport', qa)

    expect(wrapper.get('[data-testid="locked-scope"]').text()).not.toContain('feature/import')
    expect(wrapper.get('[data-testid="locked-scope"]').text()).toContain('仅使用已发布文档')
    expect(wrapper.find('[data-testid="qa-branch-selector"]').exists()).toBe(false)
    await wrapper.get('textarea').setValue('下一题使用哪些项目文档？')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(qa.createQuestion).toHaveBeenCalledWith('network-designer', expect.objectContaining({ question: '下一题使用哪些项目文档？' }))
    expect(qa.createQuestion).toHaveBeenCalledWith('network-designer', expect.not.objectContaining({ branch: expect.anything() }))
  })
})
