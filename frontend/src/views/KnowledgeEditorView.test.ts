import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import type { AdminKnowledgeDocumentView, KnowledgeApi, KnowledgeDocumentSummary, KnowledgeImportBatch } from '../api/knowledge'
import type { ProjectApi } from '../api/projects'
import type { ProjectDetail, SessionView } from '../api/types'
import { knowledgeApiKey, projectApiKey, sessionKey } from '../appContext'
import type { SessionController } from '../session/useSession'
import KnowledgeEditorView from './KnowledgeEditorView.vue'

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

function adminDocument(overrides: Partial<AdminKnowledgeDocumentView> = {}): AdminKnowledgeDocumentView {
  return {
    id: 'document-1',
    format: 'MARKDOWN',
    title: '场景包规则',
    body: '# 规则正文',
    directory: '业务规则/导入',
    tags: ['场景包'],
    source: { type: 'MANUAL', wikiUrl: null, originalFilename: null, curationNote: '人工整理' },
    scope: { type: 'PROJECT', projectId: 'project-1', branchId: null },
    status: 'DRAFT',
    revision: 1,
    syncStatus: 'NOT_APPLICABLE',
    publishedAt: null,
    publishedBy: null,
    archivedAt: null,
    archivedBy: null,
    replacement: { replacesDocumentId: null, replacedByDocumentId: null },
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:00Z',
    createdBy: 'admin',
    updatedBy: 'admin',
    ...overrides,
  }
}

function summary(id: string, title: string): KnowledgeDocumentSummary {
  const document = adminDocument({ id, title, status: 'PUBLISHED', syncStatus: 'SYNCED' })
  return document
}

function createKnowledgeApi(overrides: Partial<KnowledgeApi> = {}): KnowledgeApi {
  return {
    browse: vi.fn(),
    getDocument: vi.fn(),
    listAdmin: vi.fn().mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
    getAdminDocument: vi.fn().mockResolvedValue(adminDocument()),
    createDocument: vi.fn().mockResolvedValue(adminDocument()),
    updateDocument: vi.fn().mockResolvedValue(adminDocument()),
    publishDocument: vi.fn().mockResolvedValue(adminDocument({ status: 'PUBLISHED', syncStatus: 'PENDING' })),
    archiveDocument: vi.fn().mockResolvedValue(adminDocument({ status: 'ARCHIVED' })),
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
    listProjects: vi.fn().mockResolvedValue([{
      id: project.id,
      identifier: project.identifier,
      name: project.name,
      description: project.description,
      technologyStack: project.technologyStack,
      defaultBranch: project.defaultBranch,
      branchCount: project.branches.length,
    }]),
    getProject: vi.fn().mockResolvedValue(project),
    getAdminProject: vi.fn(),
    createProject: vi.fn(),
    addBranch: vi.fn(),
    changeStatus: vi.fn(),
  }
}

async function mountView(path: string, identity: SessionView, api: KnowledgeApi) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', name: 'projects', component: { template: '<div />' } },
      { path: '/knowledge', name: 'knowledge-global', component: { template: '<div />' } },
      { path: '/knowledge/new', name: 'knowledge-global-new', component: KnowledgeEditorView },
      { path: '/knowledge/import', name: 'knowledge-global-import', component: KnowledgeEditorView },
      { path: '/knowledge/:documentId/edit', name: 'knowledge-global-edit', component: KnowledgeEditorView },
      { path: '/projects/:identifier', name: 'project-knowledge', component: { template: '<div />' } },
      { path: '/projects/:identifier/knowledge/new', name: 'project-knowledge-new', component: KnowledgeEditorView },
      { path: '/projects/:identifier/knowledge/import', name: 'project-knowledge-import', component: KnowledgeEditorView },
      { path: '/projects/:identifier/knowledge/:documentId/edit', name: 'project-knowledge-edit', component: KnowledgeEditorView },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const session: SessionController = {
    status: ref('authenticated'), identity: ref(identity), restore: vi.fn(), login: vi.fn(), logout: vi.fn(), clear: vi.fn(),
  }
  await router.push(path)
  await router.isReady()
  return mount(KnowledgeEditorView, {
    global: {
      plugins: [router],
      provide: {
        [sessionKey as symbol]: session,
        [projectApiKey as symbol]: createProjectApi(),
        [knowledgeApiKey as symbol]: api,
      },
    },
  })
}

describe('KnowledgeEditorView', () => {
  /**
   * 业务目的：新建页必须完整提交格式、三级范围、目录、标签和来源，并在保存期间拒绝重复点击，防止生成多份草稿。
   */
  it('creates one plain-text draft with all metadata while busy', async () => {
    let resolveCreate!: (value: AdminKnowledgeDocumentView) => void
    const createDocument = vi.fn().mockReturnValue(new Promise(resolve => { resolveCreate = resolve }))
    const wrapper = await mountView('/knowledge/new', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({ createDocument }))
    await flushPromises()

    await wrapper.get('[data-testid="document-format"]').setValue('PLAIN_TEXT')
    await wrapper.get('[data-testid="document-title"]').setValue('通用发布规范')
    await wrapper.get('[data-testid="document-body"]').setValue('只保存纯文本。')
    await wrapper.get('[data-testid="document-directory"]').setValue('流程/发布')
    await wrapper.get('.tag-input input').setValue('发布')
    await wrapper.get('.tag-input input').trigger('keydown.enter')
    await wrapper.get('[data-testid="source-type"]').setValue('WIKI')
    await wrapper.get('[data-testid="source-wiki-url"]').setValue('https://wiki.example/rule')
    await wrapper.get('[data-testid="save-document"]').trigger('click')
    await wrapper.get('[data-testid="save-document"]').trigger('click')

    expect(createDocument).toHaveBeenCalledOnce()
    expect(createDocument).toHaveBeenCalledWith(expect.objectContaining({
      format: 'PLAIN_TEXT',
      title: '通用发布规范',
      body: '只保存纯文本。',
      directory: '流程/发布',
      tags: ['发布'],
      scope: expect.objectContaining({ type: 'GLOBAL' }),
      source: expect.objectContaining({ type: 'WIKI', wikiUrl: 'https://wiki.example/rule' }),
    }))
    expect(wrapper.get('[data-testid="save-document"]').attributes('disabled')).toBeDefined()
    resolveCreate(adminDocument({ title: '通用发布规范', format: 'PLAIN_TEXT' }))
    await flushPromises()
    expect(wrapper.text()).toContain('编辑知识')
  })

  /**
   * 业务目的：编辑页即使字段未变化也必须提交完整目标值，让后端幂等规则决定修订；归档文档则必须彻底只读。
   */
  it('submits unchanged values and makes archived documents readonly', async () => {
    const updateDocument = vi.fn().mockResolvedValue(adminDocument())
    const wrapper = await mountView('/projects/network-designer/knowledge/document-1/edit', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({ updateDocument }))
    await flushPromises()
    await wrapper.get('[data-testid="save-document"]').trigger('click')
    await flushPromises()
    expect(updateDocument).toHaveBeenCalledOnce()

    const archived = await mountView('/projects/network-designer/knowledge/document-1/edit', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({ getAdminDocument: vi.fn().mockResolvedValue(adminDocument({ status: 'ARCHIVED' })) }))
    await flushPromises()
    expect(archived.get('[data-testid="document-body"]').attributes('disabled')).toBeDefined()
    expect(archived.find('[data-testid="save-document"]').exists()).toBe(false)
    expect(archived.text()).toContain('归档文档只读')
  })

  /**
   * 业务目的：新建页的项目与分支范围必须联动并提交业务标识，防止把可见的分支选择遗留成 GLOBAL 或项目级写入。
   */
  it('creates a branch-scoped draft from linked scope fields', async () => {
    const createDocument = vi.fn().mockResolvedValue(adminDocument())
    const wrapper = await mountView('/knowledge/new', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({ createDocument }))
    await flushPromises()

    await wrapper.get('[data-testid="document-title"]').setValue('分支规则')
    await wrapper.get('[data-testid="document-body"]').setValue('仅适用于导入分支')
    await wrapper.get('[data-testid="scope-type"]').setValue('BRANCH')
    await wrapper.get('[data-testid="scope-project"]').setValue('network-designer')
    await wrapper.get('[data-testid="scope-branch"]').setValue('feature/import')
    await wrapper.get('[data-testid="save-document"]').trigger('click')
    await flushPromises()

    expect(createDocument).toHaveBeenCalledWith(expect.objectContaining({
      scope: { type: 'BRANCH', project: 'network-designer', branch: 'feature/import' },
    }))
  })

  /**
   * 业务目的：字段错误必须落到可见表单反馈，发布替代和归档必须二次确认并以服务端返回状态刷新，防止本地乐观伪造生命周期。
   */
  it('shows field errors and confirms replacement publication and archive', async () => {
    const updateDocument = vi.fn()
      .mockRejectedValueOnce(new ApiError(400, 'VALIDATION_FAILED', '字段无效', [{ field: 'title', message: '标题不能为空' }]))
      .mockResolvedValueOnce(adminDocument())
    const publishDocument = vi.fn().mockResolvedValue(adminDocument({ status: 'PUBLISHED', syncStatus: 'PENDING' }))
    const archiveDocument = vi.fn().mockResolvedValue(adminDocument({ status: 'ARCHIVED' }))
    const wrapper = await mountView('/projects/network-designer/knowledge/document-1/edit', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({
      updateDocument,
      publishDocument,
      archiveDocument,
      listAdmin: vi.fn().mockResolvedValue({ items: [summary('old-1', '旧版规则')], page: 0, size: 100, totalElements: 1, totalPages: 1 }),
    }))
    await flushPromises()

    await wrapper.get('[data-testid="save-document"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="field-error-title"]').text()).toContain('标题不能为空')

    await wrapper.get('[data-testid="replacement-select"]').setValue('old-1')
    await wrapper.get('[data-testid="publish-document"]').trigger('click')
    await wrapper.get('[data-testid="confirm-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(publishDocument).toHaveBeenCalledWith('document-1', 'old-1')
    expect(wrapper.text()).toContain('索引待同步')

    await wrapper.get('[data-testid="archive-document"]').trigger('click')
    await wrapper.get('[data-testid="confirm-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(archiveDocument).toHaveBeenCalledWith('document-1')
    expect(wrapper.text()).toContain('归档文档只读')
  })

  /**
   * 业务目的：导入页必须明确格式和上限、把文件与 options 一起提交，并在部分成功时保留成功、失败和忽略入口。
   */
  it('uploads one batch and preserves every partial outcome', async () => {
    const batch: KnowledgeImportBatch = {
      id: 'batch-1',
      originalFilename: '<script>bad()</script>.zip',
      scope: { type: 'PROJECT', projectId: project.id, branchId: null },
      directoryPrefix: '导入',
      status: 'PARTIAL',
      succeededCount: 1,
      failedCount: 1,
      ignoredCount: 1,
      items: [
        { ordinal: 0, entryName: 'ok.md', status: 'SUCCEEDED', reason: 'IMPORTED', message: '已导入', documentId: 'new-1' },
        { ordinal: 1, entryName: 'bad.md', status: 'FAILED', reason: 'INVALID_TEXT_ENCODING', message: '编码无效', documentId: null },
        { ordinal: 2, entryName: 'pic.png', status: 'IGNORED', reason: 'UNSUPPORTED_FILE_TYPE', message: '格式不支持', documentId: null },
      ],
      createdAt: '2026-07-30T00:00:00Z',
      createdBy: 'admin',
    }
    const importDocuments = vi.fn().mockResolvedValue(batch)
    const wrapper = await mountView('/projects/network-designer/knowledge/import', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({ importDocuments }))
    await flushPromises()
    expect(wrapper.text()).toContain('Markdown、纯文本或 ZIP')
    expect(wrapper.text()).toContain('20 MiB')
    const file = new File(['zip'], '<script>bad()</script>.zip', { type: 'application/zip' })
    const input = wrapper.get('[data-testid="import-file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await wrapper.get('[data-testid="import-submit"]').trigger('click')
    await flushPromises()

    expect(importDocuments).toHaveBeenCalledOnce()
    expect(importDocuments).toHaveBeenCalledWith(file, expect.objectContaining({
      scope: { type: 'PROJECT', project: 'network-designer', branch: null },
      sourceDefaults: expect.objectContaining({ type: 'UPLOAD', originalFilename: '<script>bad()</script>.zip' }),
    }))
    expect(wrapper.text()).toContain('成功 1')
    expect(wrapper.text()).toContain('失败 1')
    expect(wrapper.text()).toContain('忽略 1')
    expect(wrapper.find('script').exists()).toBe(false)
  })

  /**
   * 业务目的：413/415/422 是整批失败，页面不得伪造任何条目成功；普通成员即使绕过路由也不能获得导入控件。
   */
  it('keeps batch-level failures separate and hides imports from members', async () => {
    const wrapper = await mountView('/knowledge/import', {
      username: 'admin', displayName: '管理员', role: 'ADMIN',
    }, createKnowledgeApi({
      importDocuments: vi.fn().mockRejectedValue(new ApiError(413, 'DOCUMENT_IMPORT_TOO_LARGE', '文件过大')),
    }))
    await flushPromises()
    const file = new File(['large'], 'large.zip', { type: 'application/zip' })
    const input = wrapper.get('[data-testid="import-file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await wrapper.get('[data-testid="import-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="import-error"]').text()).toContain('文件超过导入上限')
    expect(wrapper.find('.import-result-panel').exists()).toBe(false)

    const member = await mountView('/knowledge/import', {
      username: 'member', displayName: '组内成员', role: 'MEMBER',
    }, createKnowledgeApi())
    await flushPromises()
    expect(member.find('[data-testid="import-submit"]').exists()).toBe(false)
    expect(member.text()).toContain('仅管理员可以维护知识')
  })
})
