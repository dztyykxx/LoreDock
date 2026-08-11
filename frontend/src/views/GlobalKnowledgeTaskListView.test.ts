import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GlobalKnowledgeTaskListView from './GlobalKnowledgeTaskListView.vue'
import { knowledgeTaskApi, type KnowledgeTaskSummary } from '../api/knowledgeTasks'
import { sessionKey } from '../appContext'

vi.mock('../api/knowledgeTasks', () => ({
  knowledgeTaskApi: { list: vi.fn() },
}))

function sessionStub() {
  return {
    status: { value: 'authenticated' },
    identity: { value: { username: 'admin', displayName: '管理员', role: 'ADMIN' } },
    restore: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
  }
}

function task(overrides: Partial<KnowledgeTaskSummary> = {}): KnowledgeTaskSummary {
  return {
    conversationId: 1,
    projectIdentifier: 'GLOBAL',
    triggerType: 'MANUAL',
    goal: '整理通用业务规则',
    status: 'PROCESSING',
    selectedDraftCount: 2,
    currentDraftId: null,
    workspaceDocumentCount: 1,
    runCount: 1,
    latestRunId: 10,
    latestRunStatus: 'RUNNING',
    latestErrorCode: null,
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
    ...overrides,
  }
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge/knowledge-tasks', name: 'knowledge-global-tasks', component: GlobalKnowledgeTaskListView },
      { path: '/knowledge/drafts', name: 'knowledge-global-drafts', component: { template: '<div />' } },
    ],
  })
  const wrapper = mount(GlobalKnowledgeTaskListView, {
    global: {
      plugins: [router],
      provide: { [sessionKey]: sessionStub() },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(knowledgeTaskApi.list).mockReset()
})

/**
 * 业务目的：全局知识任务页从 /api/admin/knowledge-tasks 读取通用范围任务，
 * 空列表展示引导空态而不是伪造示例数据。
 */
it('shows empty state when no global knowledge tasks exist', async () => {
  vi.mocked(knowledgeTaskApi.list).mockResolvedValue([])

  const wrapper = await mountView()

  expect(knowledgeTaskApi.list).toHaveBeenCalledWith(null)
  expect(wrapper.get('[data-testid="knowledge-task-history-empty"]').text()).toContain('还没有全局知识任务')
})

/**
 * 业务目的：存在全局任务时复用任务历史列表并链接到通用知识页任务详情，
 * 防止把项目任务链接误用于全局任务。
 */
it('renders global task history with global detail links', async () => {
  vi.mocked(knowledgeTaskApi.list).mockResolvedValue([task()])

  const wrapper = await mountView()

  expect(wrapper.get('[data-testid="knowledge-task-history"]').text()).toContain('整理通用业务规则')
  expect(wrapper.get('[data-conversation-id="1"]').attributes('href'))
    .toBe('/knowledge/knowledge-tasks/1')
})
