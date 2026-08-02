import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import KnowledgeTaskHistoryList from './KnowledgeTaskHistoryList.vue'

const tasks = [
  {
    conversationId: 42, projectIdentifier: 'atlas', triggerType: 'MANUAL', goal: '整合退款规则',
    selectedDraftCount: 2, currentDraftId: 51, runCount: 2, latestRunId: 63,
    latestRunStatus: 'COMPLETED', latestErrorCode: null,
    createdAt: '2026-08-01T08:00:00Z', updatedAt: '2026-08-02T08:00:00Z',
  },
  {
    conversationId: 41, projectIdentifier: 'atlas', triggerType: 'SYSTEM', goal: '检查规则冲突',
    selectedDraftCount: 1, currentDraftId: null, runCount: 1, latestRunId: 61,
    latestRunStatus: 'WAITING_FOR_USER', latestErrorCode: null,
    createdAt: '2026-08-01T07:00:00Z', updatedAt: '2026-08-01T09:00:00Z',
  },
] as const

describe('KnowledgeTaskHistoryList', () => {
  /**
   * 业务目的：历史任务必须显示真实状态并链接回原会话；
   * 防止管理员只能记住详情 URL，或误以为“继续”会创建另一份任务记录。
   */
  it('links each persisted task to its original conversation with actionable status', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:identifier/knowledge-tasks/:conversationId', component: { template: '<div />' } }],
    })
    const wrapper = mount(KnowledgeTaskHistoryList, {
      props: { projectIdentifier: 'atlas', tasks: tasks as any },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-testid="knowledge-task-history"]').text()).toContain('整合退款规则')
    expect(wrapper.text()).toContain('继续调整')
    expect(wrapper.text()).toContain('等待人工')
    expect(wrapper.get('[data-conversation-id="42"]').attributes('href')).toBe('/projects/atlas/knowledge-tasks/42')
  })
})
