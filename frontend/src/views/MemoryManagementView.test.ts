import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryManagementView from './MemoryManagementView.vue'
import { memoryApi, type MemoryView } from '../api/memories'
import { projectApiKey, sessionKey } from '../appContext'

vi.mock('../api/memories', () => ({ memoryApi: { list: vi.fn(), changeStatus: vi.fn(), delete: vi.fn(), create: vi.fn(), update: vi.fn(), revisions: vi.fn() } }))

const memory: MemoryView = {
  id: 7, scope: 'GLOBAL', projectId: null, projectIdentifier: null, category: 'FORMAT', title: '正文格式偏好', summary: '正文使用三级标题组织。', content: '正文使用三级标题组织，并保留具体细节。', status: 'ACTIVE', sourceType: 'MANUAL', sourceRunId: null, sourceConversationId: null, useCount: 3, lastUsedAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
}

function sessionStub() {
  return { status: { value: 'authenticated' }, identity: { value: { username: 'admin', displayName: '管理员', role: 'ADMIN' } }, restore: vi.fn(), logout: vi.fn().mockResolvedValue(undefined) }
}

async function mountView() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/memories', component: MemoryManagementView }, { path: '/login', component: { template: '<div />' } }] })
  await router.push('/memories'); await router.isReady()
  const wrapper = mount(MemoryManagementView, { global: { plugins: [router], provide: { [sessionKey]: sessionStub(), [projectApiKey]: { listProjects: vi.fn() } } } })
  await flushPromises()
  return wrapper
}

describe('MemoryManagementView', () => {
  beforeEach(() => {
    vi.mocked(memoryApi.list).mockReset()
    vi.mocked(memoryApi.changeStatus).mockReset()
    vi.mocked(memoryApi.revisions).mockReset()
    vi.mocked(memoryApi.list).mockResolvedValue({ total: 1, page: 1, size: 20, items: [memory] })
    vi.mocked(memoryApi.revisions).mockResolvedValue({ total: 1, page: 1, size: 20, items: [{ id: 1, memoryId: 7, revision: 1, snapshot: '{}', operation: 'CREATE', relation: 'NEW', reason: '初次创建', sourceRunId: null, sourceConversationId: null, sourceMessageId: null, operatorId: null, createdAt: '2026-08-01T00:00:00Z' }] })
  })

  /**
   * 业务目的：管理页必须展示服务端返回的真实记忆摘要和范围，防止页面用静态示例掩盖接口未接通。
   */
  it('renders memories returned by the management API', async () => {
    const wrapper = await mountView()
    expect(memoryApi.list).toHaveBeenCalledWith({ keyword: undefined, scope: undefined, category: undefined, status: undefined, page: 1, size: 20 })
    expect(wrapper.get('[data-testid="memory-card-7"]').text()).toContain('正文格式偏好')
    expect(wrapper.text()).toContain('通用')
  })

  /**
   * 业务目的：修改历史必须展示服务端提供的真实时间，防止 null 时间被浏览器解释为 Unix epoch 的 1970 年。
   */
  it('renders revision timestamps from the history API', async () => {
    const wrapper = await mountView()
    await wrapper.get('[data-testid="memory-card-7"]').trigger('click')
    await flushPromises()
    expect(memoryApi.revisions).toHaveBeenCalledWith(7)
    expect(wrapper.get('.memory-history').text()).toContain('2026年8月1日')
    expect(wrapper.get('.memory-history').text()).not.toContain('1970')
  })

  /**
   * 业务目的：管理员停用记忆时必须调用状态接口并刷新列表，确保停用后的记录不再被误认为当前有效偏好。
   */
  it('changes the selected memory status through the API', async () => {
    const wrapper = await mountView()
    vi.mocked(memoryApi.changeStatus).mockResolvedValue({ ...memory, status: 'DISABLED' })
    await wrapper.get('[data-testid="memory-card-7"]').trigger('click')
    await wrapper.get('.memory-detail-actions .app-button:nth-child(2)').trigger('click')
    await flushPromises()
    expect(memoryApi.changeStatus).toHaveBeenCalledWith(7, 'DISABLED')
  })
})
