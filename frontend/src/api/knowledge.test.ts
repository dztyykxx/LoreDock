import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './http'
import { knowledgeApi, type KnowledgeImportOptions } from './knowledge'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('knowledgeApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * 业务目的：项目和目录属于前端可选检索边界，查询参数必须逐项编码且不能携带分支。
   */
  it('encodes every browse boundary in the query string', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      directories: [],
      documents: { items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await knowledgeApi.browse({
      context: 'PROJECT',
      project: 'network/designer',
      directory: '规则/核心',
      page: 1,
      size: 20,
    })

    const [path, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/knowledge-documents?context=PROJECT&project=network%2Fdesigner&directory=%E8%A7%84%E5%88%99%2F%E6%A0%B8%E5%BF%83&page=1&size=20')
    expect(options.credentials).toBe('include')
  })

  /**
   * 业务目的：知识工作区必须显式请求后代目录模式，管理员浏览和批量发布使用独立契约以保留旧精确筛选兼容性。
   */
  it('uses dedicated subtree browse and batch publish contracts', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ directories: [], documents: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }))
      .mockResolvedValueOnce(jsonResponse({ requestedCount: 2, publishedCount: 2, alreadyPublishedCount: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await knowledgeApi.browseAdmin({ context: 'PROJECT', project: 'atlas', directory: '测试资料', status: 'DRAFT', page: 0, size: 20 })
    await knowledgeApi.batchPublishDocuments([16, 17])

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/admin/knowledge-documents/browse?context=PROJECT&project=atlas&directory=%E6%B5%8B%E8%AF%95%E8%B5%84%E6%96%99&status=DRAFT&page=0&size=20')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/admin/knowledge-documents/batch-publish')
    expect(JSON.parse(String((fetchMock.mock.calls[1]?.[1] as RequestInit).body))).toEqual({ documentIds: [16, 17] })
  })

  /**
   * 业务目的：导入范围和来源默认值必须作为独立 JSON part 发送，且浏览器负责 multipart 边界，防止代理收到不可解析的伪 multipart 请求。
   */
  it('sends a file and JSON options as multipart form data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 41, items: [] }, 201))
    vi.stubGlobal('fetch', fetchMock)
    const options: KnowledgeImportOptions = {
      scope: { type: 'PROJECT', project: 'network-designer', branch: null },
      directoryPrefix: '导入资料',
      tags: ['原始资料'],
      sourceDefaults: { type: 'UPLOAD', originalFilename: null, curationNote: '人工导入' },
    }

    await knowledgeApi.importDocuments(new File(['# 知识'], '规则.md', { type: 'text/markdown' }), options)

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    const form = request.body as FormData
    expect(request.method).toBe('POST')
    expect(new Headers(request.headers).has('Content-Type')).toBe(false)
    expect((form.get('file') as File).name).toBe('规则.md')
    expect(JSON.parse(await (form.get('options') as Blob).text())).toEqual(options)
  })

  /**
   * 业务目的：批次级校验失败必须保留后端稳定错误码和字段错误，防止界面把 413/415/422 误报成条目级部分成功。
   */
  it('maps import validation failures to ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      code: 'DOCUMENT_IMPORT_ARCHIVE_INVALID',
      message: '压缩包无效',
      fieldErrors: [{ field: 'file', message: '不可读取' }],
    }, 422)))

    await expect(knowledgeApi.importDocuments(new File(['bad'], 'bad.zip'), {
      scope: { type: 'GLOBAL' },
      directoryPrefix: '',
      tags: [],
      sourceDefaults: { type: 'UPLOAD' },
    })).rejects.toMatchObject({
      status: 422,
      code: 'DOCUMENT_IMPORT_ARCHIVE_INVALID',
      fieldErrors: [{ field: 'file', message: '不可读取' }],
    } satisfies Partial<ApiError>)
  })

  /**
   * 业务目的：服务端正文和原始文件名是不可信文本，API 层必须原样保留而不尝试解释或执行其中的 HTML。
   */
  it('keeps untrusted document body and filename as plain strings', async () => {
    const body = '<img src=x onerror=alert(1)>'
    const filename = '<script>steal()</script>.md'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      id: 51,
      body,
      source: { type: 'UPLOAD', originalFilename: filename },
    })))

    const result = await knowledgeApi.getDocument(51, { context: 'GLOBAL' })

    expect(result.body).toBe(body)
    expect(result.source.originalFilename).toBe(filename)
  })

  /**
   * 业务目的：重新索引轮询只在持久终态停止，防止首次 PENDING/RUNNING 就误报完成或终态后继续制造请求。
   */
  it.each(['SUCCEEDED', 'FAILED'] as const)('polls until the %s terminal state', async terminalStatus => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ id: 31, status: 'PENDING', progress: 0 }))
      .mockResolvedValueOnce(jsonResponse({ id: 31, status: 'RUNNING', progress: 50 }))
      .mockResolvedValueOnce(jsonResponse({ id: 31, status: terminalStatus, progress: 100 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await knowledgeApi.pollIndexJob(31, { maxAttempts: 5, intervalMs: 0 })

    expect(result.status).toBe(terminalStatus)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
