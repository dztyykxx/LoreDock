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
  id: 'project-1',
  identifier: 'network-designer',
  name: '网络设计工具',
  description: '网络拓扑设计',
  technologyStack: 'Java 21 + Vue 3',
  defaultBranch: 'main',
  selectedBranch: 'main',
  branches: [
    { id: 'branch-main', name: 'main', createdAt: '', updatedAt: '', createdBy: 'admin', updatedBy: 'admin' },
    { id: 'branch-feature', name: 'feature/import', createdAt: '', updatedAt: '', createdBy: 'admin', updatedBy: 'admin' },
  ],
}

function snapshot(overrides: Partial<QaQuestion> = {}): QaQuestion {
  return {
    questionId: 'question-1', runId: 'run-1', createdAt: '2026-07-31T08:00:00Z',
    scope: { projectIdentifier: 'network-designer', branch: 'feature/import', commit: 'abc1234', codeSnapshotAvailable: true },
    status: 'COMPLETED', resultType: 'ANSWER', trustState: 'RELIABLE_ANSWER', answerBasis: 'BUSINESS_RULE', refusalReason: null, errorCode: null,
    resultText: '服务端固定到运行创建时的范围。', stepCount: 2, modelCallCount: 1, lastEventSequence: 8,
    messages: [{ id: 'message-1', role: 'USER', content: '当前范围是什么？', resultType: null, refusalReason: null, createdAt: '2026-07-31T08:00:00Z' }],
    citations: [], ...overrides,
  }
}

function qaApi(overrides: Partial<QaApi> = {}): QaApi {
  return {
    history: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    detail: vi.fn(), createQuestion: vi.fn(), createKnowledgeGap: vi.fn(),
    openEventStream: vi.fn().mockReturnValue({ close: vi.fn() }), ...overrides,
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
    getProject: vi.fn().mockImplementation(async (_identifier, branch) => ({ ...project, selectedBranch: branch ?? 'main' })),
    getAdminProject: vi.fn(), createProject: vi.fn(), addBranch: vi.fn(), changeStatus: vi.fn(),
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
   * 业务目的：直接刷新项目问答路由时必须恢复项目与查询分支，并以真实空历史展示可用输入，不能混入设计样例。
   */
  it('loads the route branch and renders a usable empty workspace', async () => {
    const qa = qaApi()
    const { wrapper, projects } = await mountView('/projects/network-designer/qa?branch=feature%2Fimport', qa)

    expect(projects.getProject).toHaveBeenCalledWith('network-designer', 'feature/import')
    expect(qa.history).toHaveBeenCalledWith('network-designer', undefined)
    expect(wrapper.text()).toContain('网络设计工具')
    expect(wrapper.text()).toContain('还没有问答')
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('场景包导入后为何刷新拓扑')
  })

  /**
   * 业务目的：已存在问答必须持续显示服务端固定分支；切换选择只能影响下一题，不能重写当前回答的范围或来源。
   */
  it('keeps the current snapshot scope while a branch change applies to the next question', async () => {
    const existing = snapshot()
    const accepted = snapshot({ questionId: 'question-2', runId: 'run-2', status: 'ACCEPTED', resultType: null, trustState: 'IN_PROGRESS', answerBasis: null, resultText: null, scope: { ...existing.scope, branch: 'main' } })
    const qa = qaApi({
      history: vi.fn().mockResolvedValue({ items: [existing], nextCursor: null }),
      detail: vi.fn().mockResolvedValue(existing),
      createQuestion: vi.fn().mockResolvedValue(accepted),
    })
    const { wrapper } = await mountView('/projects/network-designer/qa?branch=feature%2Fimport', qa)

    expect(wrapper.get('[data-testid="locked-scope"]').text()).toContain('feature/import')
    await wrapper.get('[data-testid="qa-branch-selector"]').setValue('main')
    expect(wrapper.get('[data-testid="locked-scope"]').text()).toContain('feature/import')
    await wrapper.get('textarea').setValue('下一题使用哪个分支？')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(qa.createQuestion).toHaveBeenCalledWith('network-designer', expect.objectContaining({ branch: 'main', question: '下一题使用哪个分支？' }))
  })
})
