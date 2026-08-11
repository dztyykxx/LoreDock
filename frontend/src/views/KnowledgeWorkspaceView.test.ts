import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import type { KnowledgeApi, KnowledgeBrowseResult, KnowledgeDocumentSummary } from '../api/knowledge'
import { knowledgeTaskApi } from '../api/knowledgeTasks'
import { ApiError } from '../api/http'
import type { ProjectApi } from '../api/projects'
import type { ProjectDetail, SessionView } from '../api/types'
import { knowledgeApiKey, projectApiKey, sessionKey } from '../appContext'
import type { SessionController } from '../session/useSession'
import KnowledgeWorkspaceView from './KnowledgeWorkspaceView.vue'

const published: KnowledgeDocumentSummary = {
  id: 51,
  format: 'MARKDOWN',
  title: '已发布业务规则',
  directory: '规则/导入',
  tags: ['场景包'],
  source: { type: 'UPLOAD', wikiUrl: null, originalFilename: 'rule.md', curationNote: null },
  scope: { type: 'PROJECT', projectId: 1, branchId: null },
  status: 'PUBLISHED',
  revision: 2,
  syncStatus: 'SYNCED',
  updatedAt: '2026-07-30T00:00:00Z',
}

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

function browseResult(items: KnowledgeDocumentSummary[] = []): KnowledgeBrowseResult {
  return {
    directories: items.length ? [{ path: '规则/导入', name: '导入', documentCount: items.length }] : [],
    documents: { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 },
  }
}

function createKnowledgeApi(overrides: Partial<KnowledgeApi> = {}): KnowledgeApi {
  return {
    browse: vi.fn().mockResolvedValue(browseResult()),
    browseAdmin: vi.fn().mockResolvedValue(browseResult()),
    getDocument: vi.fn(),
    listAdmin: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
    getAdminDocument: vi.fn(),
    createDocument: vi.fn(),
    updateDocument: vi.fn(),
    publishDocument: vi.fn(),
    batchPublishDocuments: vi.fn(),
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
    getProject: vi.fn().mockResolvedValue(project),
    getAdminProject: vi.fn(),
    createProject: vi.fn(),
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
      { path: '/projects/:identifier/drafts', name: 'project-drafts', component: KnowledgeWorkspaceView },
      { path: '/projects/:identifier/drafts/:documentId', name: 'project-draft-detail', component: KnowledgeWorkspaceView },
      { path: '/projects/:identifier/qa', name: 'project-qa', component: { template: '<div />' } },
      { path: '/projects/:identifier/knowledge/:documentId', name: 'project-knowledge-detail', component: KnowledgeWorkspaceView },
      { path: '/projects/:identifier/knowledge/new', name: 'project-knowledge-new', component: { template: '<div />' } },
      { path: '/projects/:identifier/knowledge/import', name: 'project-knowledge-import', component: { template: '<div />' } },
      { path: '/projects/:identifier/knowledge-tasks/:conversationId', name: 'project-knowledge-task', component: { template: '<div data-testid="task-target" />' } },
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
    const { wrapper } = await mountView('/knowledge/51', {
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
      .mockResolvedValueOnce({ ...browseResult([{ ...published, id: 53, title: '目录文档' }]), documents: { items: [{ ...published, id: 53, title: '目录文档' }], page: 0, size: 20, totalElements: 21, totalPages: 2 } })
      .mockResolvedValueOnce({ ...browseResult([{ ...published, id: 54, title: '第二页文档' }]), documents: { items: [{ ...published, id: 54, title: '第二页文档' }], page: 1, size: 20, totalElements: 21, totalPages: 2 } })
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
   * 业务目的：项目知识页必须只按项目范围查询，旧分支参数不能影响请求或重新出现选择控件。
   */
  it('ignores legacy branch queries and browses the project scope', async () => {
    const browse = vi.fn().mockResolvedValue(browseResult([{ ...published, title: '项目文档' }]))
    const projects = createProjectApi()
    const { wrapper } = await mountView('/projects/network-designer?branch=feature%2Fimport', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, createKnowledgeApi({ browse }), projects)
    await flushPromises()

    expect(projects.getProject).toHaveBeenCalledWith('network-designer')
    expect(wrapper.text()).toContain('项目文档')
    expect(wrapper.find('[data-testid="branch-selector"]').exists()).toBe(false)
    expect(browse).toHaveBeenLastCalledWith(expect.not.objectContaining({ branch: expect.anything() }))
  })

  /**
   * 业务目的：管理员目录必须展示草稿和真实索引状态及写入口，而不是复用成员已发布视图隐藏待处理文档。
   */
  it('shows administrator lifecycle states and actions', async () => {
    const draft = { ...published, id: 56, title: '待发布规则', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const api = createKnowledgeApi({
      browseAdmin: vi.fn().mockResolvedValue(browseResult([draft])),
    })
    const { wrapper } = await mountView('/projects/network-designer', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    expect(wrapper.text()).toContain('待发布规则')
    expect(wrapper.text()).toContain('草稿')
    expect(wrapper.get('[data-testid="new-knowledge"]').text()).toContain('新建知识')
    expect(wrapper.get('[data-testid="import-knowledge"]').text()).toContain('导入资料')
    expect(wrapper.get('[data-testid="reindex-knowledge"]').text()).toContain('刷新索引')
  })

  /**
   * 业务目的：管理员必须能在统一草稿入口看到父目录下的后代草稿并选择本页，
   * 选择后必须同时提供 AI 合并与直接批量发布入口，方便按材料成熟度选择处理方式。
   */
  it('browses and selects administrator draft subtrees with both processing actions', async () => {
    const first = { ...published, id: 56, title: '导入草稿一', directory: '测试资料/Atlas/source', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const second = { ...first, id: 57, title: '导入草稿二', directory: '测试资料/Atlas/runtime' }
    const directories = [
      { path: '测试资料', name: '测试资料', documentCount: 2 },
      { path: '测试资料/Atlas', name: 'Atlas', documentCount: 2 },
    ]
    const browseAdmin = vi.fn()
      .mockResolvedValueOnce({ directories, documents: { items: [first, second], page: 0, size: 20, totalElements: 2, totalPages: 1 } })
      .mockResolvedValueOnce({ directories, documents: { items: [first, second], page: 0, size: 20, totalElements: 2, totalPages: 1 } })
    const api = createKnowledgeApi({ browseAdmin })
    const { wrapper } = await mountView('/projects/network-designer/drafts', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    expect(wrapper.get('[data-directory="测试资料"]').text()).toContain('2')
    await wrapper.get('[data-directory="测试资料"]').trigger('click')
    await flushPromises()
    expect(browseAdmin).toHaveBeenLastCalledWith(expect.objectContaining({ directory: '测试资料', status: 'DRAFT', page: 0 }))

    await wrapper.get('[data-testid="select-all-drafts"]').setValue(true)
    expect(wrapper.get('[data-testid="merge-selected-drafts"]').text()).toContain('AI 合并整理 2')
    expect(wrapper.get('[data-testid="batch-publish"]').text()).toContain('批量发布 2')
  })

  /**
   * 业务目的：成熟草稿可以跳过 AI 整理并一次原子发布，发布前必须二次确认，
   * 防止误点或部分失败造成同一批草稿状态不一致。
   */
  it('confirms and publishes selected draft documents as one batch', async () => {
    const first = { ...published, id: 56, title: '已确认规则一', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const second = { ...first, id: 57, title: '已确认规则二' }
    const browseAdmin = vi.fn()
      .mockResolvedValueOnce(browseResult([first, second]))
      .mockResolvedValueOnce(browseResult())
    const batchPublishDocuments = vi.fn().mockResolvedValue({ requestedCount: 2, publishedCount: 2, alreadyPublishedCount: 0 })
    const api = createKnowledgeApi({ browseAdmin, batchPublishDocuments })
    const { wrapper } = await mountView('/projects/network-designer/drafts', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    await wrapper.get('[data-testid="select-all-drafts"]').setValue(true)
    await wrapper.get('[data-testid="batch-publish"]').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('跳过 AI 整理并直接发布选中的 2 篇草稿')

    await wrapper.get('[data-testid="confirm-dialog-submit"]').trigger('click')
    await flushPromises()

    expect(batchPublishDocuments).toHaveBeenCalledWith([56, 57])
    expect(wrapper.get('[data-testid="batch-publish-message"]').text()).toContain('已发布 2 篇')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  /**
   * 业务目的：管理员勾选多个草稿后才启动一次 AI 合并，并把所选 ID 和人工目标作为固定输入；
   * 防止上传动作直接调用模型，或为每份文件错误创建独立会话。
   */
  it('starts one merge task from selected draft documents', async () => {
    const first = { ...published, id: 56, title: '需求草稿', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const second = { ...first, id: 57, title: '实现草稿' }
    const api = createKnowledgeApi({ browseAdmin: vi.fn().mockResolvedValue(browseResult([first, second])) })
    const start = vi.spyOn(knowledgeTaskApi, 'start').mockResolvedValue({ conversationId: 88 } as any)
    const { wrapper, router } = await mountView('/projects/network-designer/drafts', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    await wrapper.get('[data-testid="select-all-drafts"]').setValue(true)
    await wrapper.get('[data-testid="merge-goal"]').setValue('   ')
    expect(wrapper.get('[data-testid="merge-selected-drafts"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="merge-goal"]').setValue('合并为一份发布规范，冲突留给人工判断')
    await wrapper.get('[data-testid="merge-selected-drafts"]').trigger('click')
    await flushPromises()

    expect(start).toHaveBeenCalledOnce()
    expect(start).toHaveBeenCalledWith('network-designer', [56, 57], '合并为一份发布规范，冲突留给人工判断')
    expect(router.currentRoute.value.fullPath).toBe('/projects/network-designer/knowledge-tasks/88')
    start.mockRestore()
  })

  /**
   * 业务目的：知识整理服务配置失败时必须展示服务端的稳定错误语义；
   * 防止把服务端 Skill 缺失误报为草稿勾选错误，误导管理员修改正确输入。
   */
  it('shows the stable service error when merge startup fails', async () => {
    const draft = { ...published, id: 56, title: '需求草稿', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const api = createKnowledgeApi({ browseAdmin: vi.fn().mockResolvedValue(browseResult([draft])) })
    const start = vi.spyOn(knowledgeTaskApi, 'start').mockRejectedValue(
      new ApiError(503, 'KNOWLEDGE_TASK_DEFINITION_UNAVAILABLE', '知识整理能力暂时不可用'),
    )
    const { wrapper } = await mountView('/projects/network-designer/drafts', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    await wrapper.get('[data-testid="select-all-drafts"]').setValue(true)
    await wrapper.get('[data-testid="merge-selected-drafts"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="merge-message"]').text()).toBe('知识整理能力暂时不可用')
    start.mockRestore()
  })

  /**
   * 业务目的：合并后的唯一“草稿”入口必须由服务端只返回项目草稿，并继续提供原有多选合并动作；
   * 防止变更知识、待审核草稿和整理报告继续成为不可进入的占位页。
   */
  it('opens one draft workspace backed by server-side draft filtering', async () => {
    const draft = { ...published, id: 56, title: '待整理草稿', status: 'DRAFT' as const, syncStatus: 'NOT_APPLICABLE' as const }
    const browseAdmin = vi.fn().mockResolvedValue(browseResult([draft]))
    const { wrapper } = await mountView('/projects/network-designer/drafts', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({ browseAdmin }))
    await flushPromises()

    expect(browseAdmin).toHaveBeenCalledWith(expect.objectContaining({ status: 'DRAFT' }))
    expect(wrapper.get('[data-tab="drafts"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-tab="drafts"]').text()).toContain('1')
    expect(wrapper.get('[data-testid="select-all-drafts"]').element).toBeInstanceOf(HTMLInputElement)
    expect(wrapper.text()).toContain('选择本页草稿')
    expect(wrapper.text()).toContain('待整理草稿')
    expect(wrapper.text()).not.toContain('变更知识')
    expect(wrapper.text()).not.toContain('整理报告')
  })

  /**
   * 业务目的：跨范围详情被后端统一拒绝为 404 时必须隐藏正文并保留当前项目上下文，防止泄露旧详情或让用户迷失范围。
   */
  it('keeps project context while hiding an out-of-scope detail', async () => {
    const api = createKnowledgeApi({
      browse: vi.fn().mockResolvedValue(browseResult([published])),
      getDocument: vi.fn().mockRejectedValue({ status: 404, code: 'DOCUMENT_NOT_FOUND' }),
    })
    const { wrapper } = await mountView('/projects/network-designer/knowledge/999', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, api)
    await flushPromises()

    expect(wrapper.text()).toContain('网络设计工具')
    expect(wrapper.text()).not.toContain('feature/import')
    expect(wrapper.get('[data-testid="detail-error"]').text()).toContain('当前范围内找不到该文档')
    expect(wrapper.find('[data-testid="knowledge-detail"]').exists()).toBe(false)
  })

  /**
   * 业务目的：管理员提交重新索引后必须展示服务端任务 ID、有限轮询到失败终态并保留普通浏览结果，防止失败任务遮断旧活动索引。
   */
  it('shows a failed reindex job without interrupting document browsing', async () => {
    const submitIndexJob = vi.fn().mockResolvedValue({
      id: 31, status: 'PENDING', progress: 0, startedAt: null, finishedAt: null, failureSummary: null,
    })
    const pollIndexJob = vi.fn().mockResolvedValue({
      id: 31, status: 'FAILED', progress: 60, startedAt: '2026-07-30T00:00:00Z', finishedAt: '2026-07-30T00:01:00Z', failureSummary: '重建失败，请检查服务日志。',
    })
    const api = createKnowledgeApi({
      browseAdmin: vi.fn().mockResolvedValue(browseResult([published])),
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
    expect(pollIndexJob).toHaveBeenCalledWith(31, expect.objectContaining({ maxAttempts: 20 }))
    expect(wrapper.get('[data-testid="index-job-panel"]').text()).toContain(31)
    expect(wrapper.get('[data-testid="index-job-panel"]').text()).toContain('重建失败，请检查服务日志。')
    expect(wrapper.text()).toContain('已发布业务规则')
  })

  /**
   * 业务目的：重新索引成功后必须立即重新读取目录，让新增文档数量和同步状态无需刷新浏览器即可更新。
   */
  it('refreshes document count and sync status after reindex succeeds', async () => {
    const pending = { ...published, syncStatus: 'PENDING' as const }
    const indexed = { ...published, syncStatus: 'SYNCED' as const }
    const imported = { ...indexed, id: 52, title: '新导入规则' }
    const browseAdmin = vi.fn()
      .mockResolvedValueOnce(browseResult([pending]))
      .mockResolvedValueOnce(browseResult([indexed, imported]))
    const api = createKnowledgeApi({
      browseAdmin,
      submitIndexJob: vi.fn().mockResolvedValue({ id: 31, status: 'PENDING', progress: 0, startedAt: null, finishedAt: null, failureSummary: null }),
      pollIndexJob: vi.fn().mockResolvedValue({ id: 31, status: 'SUCCEEDED', progress: 100, startedAt: '2026-07-30T00:00:00Z', finishedAt: '2026-07-30T00:01:00Z', failureSummary: null }),
    })
    const { wrapper } = await mountView('/knowledge', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, api)
    await flushPromises()

    expect(wrapper.get('.knowledge-panel-heading span').text()).toBe('1')
    expect(wrapper.text()).toContain('索引待同步')
    await wrapper.get('[data-testid="reindex-knowledge"]').trigger('click')
    await flushPromises()

    expect(browseAdmin).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.knowledge-panel-heading span').text()).toBe('2')
    expect(wrapper.text()).toContain('新导入规则')
    expect(wrapper.text()).toContain('索引已同步')
  })

  /**
   * 业务目的：活动任务轮询期间必须禁用重复提交，并在页面离开时中止等待，防止后台页面持续请求或产生第二个前端轮询器。
   */
  it('disables duplicate reindex submissions and aborts polling on leave', async () => {
    let pollingSignal: AbortSignal | undefined
    const api = createKnowledgeApi({
      browseAdmin: vi.fn().mockResolvedValue(browseResult()),
      submitIndexJob: vi.fn().mockResolvedValue({ id: 31, status: 'PENDING', progress: 0, startedAt: null, finishedAt: null, failureSummary: null }),
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
