import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import type { KnowledgeApi, KnowledgeBrowseResult, KnowledgeDocumentSummary } from '../api/knowledge'
import type { ProjectApi } from '../api/projects'
import type { ProjectDetail, SessionView } from '../api/types'
import { knowledgeApiKey, projectApiKey, sessionKey } from '../appContext'
import type { SessionController } from '../session/useSession'
import KnowledgeWorkspaceView from './KnowledgeWorkspaceView.vue'

const published: KnowledgeDocumentSummary = {
  id: 'published-1',
  format: 'MARKDOWN',
  title: '已发布业务规则',
  directory: '规则/导入',
  tags: ['场景包'],
  source: { type: 'UPLOAD', wikiUrl: null, originalFilename: 'rule.md', curationNote: null },
  scope: { type: 'PROJECT', projectId: 'project-1', branchId: null },
  status: 'PUBLISHED',
  revision: 2,
  syncStatus: 'SYNCED',
  updatedAt: '2026-07-30T00:00:00Z',
}

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

function browseResult(items: KnowledgeDocumentSummary[] = []): KnowledgeBrowseResult {
  return {
    directories: items.length ? [{ path: '规则/导入', name: '导入', documentCount: items.length }] : [],
    documents: { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 },
  }
}

function createKnowledgeApi(overrides: Partial<KnowledgeApi> = {}): KnowledgeApi {
  return {
    browse: vi.fn().mockResolvedValue(browseResult()),
    getDocument: vi.fn(),
    listAdmin: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
    getAdminDocument: vi.fn(),
    createDocument: vi.fn(),
    updateDocument: vi.fn(),
    publishDocument: vi.fn(),
    archiveDocument: vi.fn(),
    importDocuments: vi.fn(),
    getImportBatch: vi.fn(),
    submitIndexJob: vi.fn(),
    getIndexJob: vi.fn(),
    pollIndexJob: vi.fn(),
    ...overrides,
  }
}

function createProjectApi(): ProjectApi {
  return {
    listProjects: vi.fn(),
    getProject: vi.fn().mockImplementation(async (_identifier, branch) => ({ ...project, selectedBranch: branch ?? 'main' })),
    getAdminProject: vi.fn(),
    createProject: vi.fn(),
    addBranch: vi.fn(),
    changeStatus: vi.fn(),
  }
}

async function mountView(path: string, identity: SessionView, api: KnowledgeApi, projects = createProjectApi()) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', name: 'knowledge-global', component: KnowledgeWorkspaceView },
      { path: '/projects', name: 'projects', component: { template: '<div />' } },
      { path: '/knowledge/:documentId', name: 'knowledge-global-detail', component: KnowledgeWorkspaceView },
      { path: '/projects/:identifier', name: 'project-knowledge', component: KnowledgeWorkspaceView },
      { path: '/projects/:identifier/knowledge/:documentId', name: 'project-knowledge-detail', component: KnowledgeWorkspaceView },
      { path: '/projects/:identifier/knowledge/new', name: 'project-knowledge-new', component: { template: '<div />' } },
      { path: '/projects/:identifier/knowledge/import', name: 'project-knowledge-import', component: { template: '<div />' } },
      { path: '/projects/:projectId/settings', name: 'project-settings', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const session: SessionController = {
    status: ref('authenticated'),
    identity: ref(identity),
    restore: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    clear: vi.fn(),
  }
  await router.push(path)
  await router.isReady()
  return {
    router,
    wrapper: mount(KnowledgeWorkspaceView, {
      global: {
        plugins: [router],
        provide: { [sessionKey as symbol]: session, [projectApiKey as symbol]: projects, [knowledgeApiKey as symbol]: api },
      },
    }),
  }
}

describe('KnowledgeWorkspaceView', () => {
  /**
   * 业务目的：通用知识加载失败必须提供可重试错误态，成功重试后的空目录不能继续展示旧样例或失败提示。
   */
  it('recovers from a global browse failure into an empty state', async () => {
    const browse = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(browseResult())
    const { wrapper } = await mountView('/knowledge', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, createKnowledgeApi({ browse }))
    await flushPromises()

    expect(browse).toHaveBeenLastCalledWith(expect.objectContaining({ directory: undefined }))
    expect(wrapper.get('[role="alert"]').text()).toContain('知识目录加载失败')
    await wrapper.get('[data-testid="retry-knowledge"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="knowledge-empty"]').text()).toContain('暂无已发布知识')
    expect(wrapper.text()).not.toContain('场景包导入导出链路')
  })

  /**
   * 业务目的：普通成员只能通过普通接口看到已发布文档，正文和来源文件名必须作为文本显示而不能执行上传的 HTML。
   */
  it('shows published member detail as plain text', async () => {
    const body = '<img src=x onerror=alert(1)>'
    const api = createKnowledgeApi({
      browse: vi.fn().mockResolvedValue(browseResult([published])),
      getDocument: vi.fn().mockResolvedValue({ ...published, body, publishedAt: '2026-07-29T00:00:00Z' }),
    })
    const { wrapper } = await mountView('/knowledge/published-1', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, api)
    await flushPromises()

    expect(wrapper.get('[data-testid="knowledge-detail"]').text()).toContain(body)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[data-testid="new-knowledge"]').exists()).toBe(false)
  })

  /**
   * 业务目的：目录和分页操作必须把各自边界送回服务端并替换当前结果，防止前端在不同目录或页码之间拼接文档。
   */
  it('reloads exact directory and page boundaries', async () => {
    const browse = vi.fn()
      .mockResolvedValueOnce({ ...browseResult([published]), documents: { items: [published], page: 0, size: 20, totalElements: 21, totalPages: 2 } })
      .mockResolvedValueOnce({ ...browseResult([{ ...published, id: 'directory-1', title: '目录文档' }]), documents: { items: [{ ...published, id: 'directory-1', title: '目录文档' }], page: 0, size: 20, totalElements: 21, totalPages: 2 } })
      .mockResolvedValueOnce({ ...browseResult([{ ...published, id: 'page-2', title: '第二页文档' }]), documents: { items: [{ ...published, id: 'page-2', title: '第二页文档' }], page: 1, size: 20, totalElements: 21, totalPages: 2 } })
    const { wrapper } = await mountView('/knowledge', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, createKnowledgeApi({ browse }))
    await flushPromises()

    await wrapper.get('[data-directory="规则/导入"]').trigger('click')
    await flushPromises()
    expect(browse).toHaveBeenLastCalledWith(expect.objectContaining({ directory: '规则/导入', page: 0 }))

    // 目录查询仍有下一页时，页码必须作为新的服务端边界，而非追加旧列表。
    await wrapper.get('.knowledge-pagination .app-button:last-child').trigger('click')
    await flushPromises()
    expect(browse).toHaveBeenLastCalledWith(expect.objectContaining({ directory: '规则/导入', page: 1 }))
    expect(wrapper.text()).toContain('第二页文档')
    expect(wrapper.text()).not.toContain('目录文档')
  })

  /**
   * 业务目的：切换项目分支时必须立即退出旧分支列表，并以新分支重新查询，防止响应等待期间把 main 文档误标为 feature 文档。
   */
  it('does not mix documents while switching project branches', async () => {
    let resolveFeature!: (value: KnowledgeBrowseResult) => void
    const browse = vi.fn().mockImplementation((input: { branch?: string }) => input.branch === 'feature/import'
      ? new Promise<KnowledgeBrowseResult>(resolve => { resolveFeature = resolve })
      : Promise.resolve(browseResult([{ ...published, title: 'main 文档' }])))
    const { wrapper } = await mountView('/projects/network-designer', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, createKnowledgeApi({ browse }))
    await flushPromises()
    expect(wrapper.text()).toContain('main 文档')

    await wrapper.get('[data-testid="branch-selector"]').setValue('feature/import')
    expect(wrapper.text()).not.toContain('main 文档')
    await flushPromises()
    resolveFeature(browseResult([{ ...published, id: 'feature-1', title: 'feature 文档' }]))
    await flushPromises()

    expect(wrapper.text()).toContain('feature 文档')
    expect(browse).toHaveBeenLastCalledWith(expect.objectContaining({ branch: 'feature/import' }))
  })

  /**
   * 业务目的：管理员目录必须展示草稿和真实索引状态及写入口，而不是复用成员已发布视图隐藏待处理文档。
   */
  it('shows administrator lifecycle states and actions', async () => {
    const draft = { ...published, id: 'draft-1', title: '待发布规则', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const api = createKnowledgeApi({
      listAdmin: vi.fn().mockResolvedValue({ items: [draft], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
    })
    const { wrapper } = await mountView('/projects/network-designer', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    expect(wrapper.text()).toContain('待发布规则')
    expect(wrapper.text()).toContain('草稿')
    expect(wrapper.get('[data-testid="new-knowledge"]').text()).toContain('新建知识')
    expect(wrapper.get('[data-testid="import-knowledge"]').text()).toContain('导入资料')
    expect(wrapper.get('[data-testid="reindex-knowledge"]').text()).toContain('重新索引')
  })

  /**
   * 业务目的：跨范围详情被后端统一拒绝为 404 时必须隐藏正文并保留当前项目和分支上下文，防止泄露旧详情或让用户迷失范围。
   */
  it('keeps project context while hiding an out-of-scope detail', async () => {
    const api = createKnowledgeApi({
      browse: vi.fn().mockResolvedValue(browseResult([published])),
      getDocument: vi.fn().mockRejectedValue({ status: 404, code: 'DOCUMENT_NOT_FOUND' }),
    })
    const { wrapper } = await mountView('/projects/network-designer/knowledge/foreign-document', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, api)
    await flushPromises()

    expect(wrapper.text()).toContain('网络设计工具')
    expect(wrapper.text()).toContain('main')
    expect(wrapper.get('[data-testid="detail-error"]').text()).toContain('当前范围内找不到该文档')
    expect(wrapper.find('[data-testid="knowledge-detail"]').exists()).toBe(false)
  })

  /**
   * 业务目的：管理员提交重新索引后必须展示服务端任务 ID、有限轮询到失败终态并保留普通浏览结果，防止失败任务遮断旧活动索引。
   */
  it('shows a failed reindex job without interrupting document browsing', async () => {
    const submitIndexJob = vi.fn().mockResolvedValue({
      id: 'job-1', status: 'PENDING', progress: 0, startedAt: null, finishedAt: null, failureSummary: null,
    })
    const pollIndexJob = vi.fn().mockResolvedValue({
      id: 'job-1', status: 'FAILED', progress: 60, startedAt: '2026-07-30T00:00:00Z', finishedAt: '2026-07-30T00:01:00Z', failureSummary: '重建失败，请检查服务日志。',
    })
    const api = createKnowledgeApi({
      listAdmin: vi.fn().mockResolvedValue({ items: [published], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
      submitIndexJob,
      pollIndexJob,
    })
    const { wrapper } = await mountView('/knowledge', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()
    await wrapper.get('[data-testid="reindex-knowledge"]').trigger('click')
    await flushPromises()

    expect(submitIndexJob).toHaveBeenCalledOnce()
    expect(pollIndexJob).toHaveBeenCalledWith('job-1', expect.objectContaining({ maxAttempts: 20 }))
    expect(wrapper.get('[data-testid="index-job-panel"]').text()).toContain('job-1')
    expect(wrapper.get('[data-testid="index-job-panel"]').text()).toContain('重建失败，请检查服务日志。')
    expect(wrapper.text()).toContain('已发布业务规则')
  })

  /**
   * 业务目的：活动任务轮询期间必须禁用重复提交，并在页面离开时中止等待，防止后台页面持续请求或产生第二个前端轮询器。
   */
  it('disables duplicate reindex submissions and aborts polling on leave', async () => {
    let pollingSignal: AbortSignal | undefined
    const api = createKnowledgeApi({
      listAdmin: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      submitIndexJob: vi.fn().mockResolvedValue({ id: 'job-1', status: 'PENDING', progress: 0, startedAt: null, finishedAt: null, failureSummary: null }),
      pollIndexJob: vi.fn().mockImplementation((_jobId, options) => {
        pollingSignal = options?.signal
        return new Promise(() => undefined)
      }),
    })
    const { wrapper } = await mountView('/knowledge', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()
    await wrapper.get('[data-testid="reindex-knowledge"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="reindex-knowledge"]').attributes('disabled')).toBeDefined()
    expect(pollingSignal?.aborted).toBe(false)
    wrapper.unmount()
    expect(pollingSignal?.aborted).toBe(true)
  })
})
