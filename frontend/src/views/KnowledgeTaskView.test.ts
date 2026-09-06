import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { KnowledgeTask } from '../api/knowledgeTasks'
import { projectApiKey, sessionKey } from '../appContext'
import KnowledgeTaskView from './KnowledgeTaskView.vue'

vi.mock('../api/knowledgeTasks', () => ({
  knowledgeTaskApi: {
    detail: vi.fn(),
    continueTask: vi.fn(),
    stop: vi.fn(),
    publishWorkspace: vi.fn(),
    closeNoChange: vi.fn(),
    eventUrl: vi.fn((_identifier: string | null, _conversationId: number, after: number) => `/events?after=${after}`),
  },
}))
// jsdom 不提供 EventSource；SSE 仅作为低延迟触发器，测试只关心定时链是否存活。
class EventSourceStub {
  constructor(public url: string) {}
  addEventListener = vi.fn()
  onerror: (() => void) | null = null
  close = vi.fn()
}

const { knowledgeTaskApi } = await import('../api/knowledgeTasks')
const detail = vi.mocked(knowledgeTaskApi.detail)

function snapshotWith(lastRunStatus: string, extraRunStatuses: string[] = [], taskStatus = 'PROCESSING'): KnowledgeTask {
  const statuses = [...extraRunStatuses, lastRunStatus]
  return {
    conversationId: 23,
    projectIdentifier: 'net',
    triggerType: 'MANUAL',
    targetSkill: 'knowledge-curator',
    goal: '整理项目约束',
    status: taskStatus as KnowledgeTask['status'],
    selectedDrafts: [],
    currentDraftId: null,
    currentDraftRevision: null,
    messages: [],
    runs: statuses.map((status, index) => ({
      runId: 100 + statuses.length + index, conversationId: 23, threadId: `thread-${index}`,
      status: status as KnowledgeTask['runs'][number]['status'], checkpointSavedAt: null,
      stepCount: 0, modelCallCount: 0, toolCallCount: 0, inputTokens: null, outputTokens: null,
      errorCode: status === 'FAILED' ? 'AGENT_MODEL_RESPONSE_INVALID' : null,
      acceptedAt: '2026-08-30T12:49:45Z', startedAt: null, finishedAt: status === 'FAILED' ? '2026-08-30T12:50:48Z' : null,
      definition: { skillName: 'knowledge-curator', skillDigest: 'a', agentSpecDigest: 'b', modelName: 'm', toolNames: [] },
    })),
    events: [],
    workspaceDocuments: [],
    toolInvocations: [],
    patchSets: [],
    lastEventSequence: 2088,
  }
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/projects/:identifier/knowledge-tasks/:conversationId', component: KnowledgeTaskView }],
  })
  await router.push('/projects/net/knowledge-tasks/23')
  await router.isReady()
  const wrapper = mount(KnowledgeTaskView, {
    global: {
      plugins: [router],
      stubs: {
        AppSidebar: true,
        AppTopBar: true,
        ProjectHero: true,
        ProjectTabs: true,
        KnowledgeTaskWorkspace: true,
      },
      provide: {
        [projectApiKey]: {
          getProject: vi.fn().mockResolvedValue({ id: 3, name: 'net', identifier: 'net', technologyStack: [] }),
        },
        [sessionKey]: { identity: { value: { displayName: '管理员', role: 'ADMIN' } }, logout: vi.fn() },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('KnowledgeTaskView 轮询续链', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', EventSourceStub)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  /**
   * 业务目的：失败一轮结束后，即使最近一轮是终态（FAILED），刷新链也必须继续；
   * 否则后续新一轮运行的过程不会实时出现（“报错中断后没有流式效果，要等运行结束才看得到”）。
   */
  it('最近一轮失败后仍持续刷新，任务 PROCESSING 期间定时链不断', async () => {
    vi.mocked(detail)
      .mockResolvedValueOnce(snapshotWith('FAILED'))
      .mockResolvedValueOnce(snapshotWith('RUNNING', ['FAILED']))
      .mockResolvedValue(snapshotWith('COMPLETED', ['RUNNING', 'FAILED']))
    await mountView()
    expect(detail).toHaveBeenCalledTimes(1)

    // 旧实现：schedulePoll 仅在最近一轮为 ACTIV… 时被激活，失败后即停；
    // 新实现：任务仍是 PROCESSING，就该每 5 秒刷新一次。
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(detail).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(detail).toHaveBeenCalledTimes(3)
  })

  /**
   * 业务目的：任务状态离开 PROCESSING（发布/结束）后停止刷新，避免对终态任务无意义地持续请求。
   */
  it('任务关闭后停止轮询', async () => {
    vi.mocked(detail)
      .mockResolvedValueOnce(snapshotWith('FAILED', [], 'PUBLISHED'))
      .mockResolvedValue(snapshotWith('FAILED', [], 'PUBLISHED'))
    await mountView()
    expect(detail).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(15000)
    await flushPromises()
    expect(detail).toHaveBeenCalledTimes(1)
  })
})
