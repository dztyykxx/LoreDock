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
   * 业务目的：项目、分支和目录属于强检索边界，查询参数必须逐项编码，防止斜杠或中文把一次查询改写到其他范围。
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
      branch: 'feature/导入',
      directory: '规则/核心',
      page: 1,
      size: 20,
    })

    const [path, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/knowledge-documents?context=PROJECT&project=network%2Fdesigner&branch=feature%2F%E5%AF%BC%E5%85%A5&directory=%E8%A7%84%E5%88%99%2F%E6%A0%B8%E5%BF%83&page=1&size=20')
    expect(options.credentials).toBe('include')
  })

  /**
   * 业务目的：导入范围和来源默认值必须作为独立 JSON part 发送，且浏览器负责 multipart 边界，防止代理收到不可解析的伪 multipart 请求。
   */
  it('sends a file and JSON options as multipart form data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 'batch-1', items: [] }, 201))
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
      id: 'document-1',
      body,
      source: { type: 'UPLOAD', originalFilename: filename },
    })))

    const result = await knowledgeApi.getDocument('document-1', { context: 'GLOBAL' })

    expect(result.body).toBe(body)
    expect(result.source.originalFilename).toBe(filename)
  })

  /**
   * 业务目的：重新索引轮询只在持久终态停止，防止首次 PENDING/RUNNING 就误报完成或终态后继续制造请求。
   */
  it.each(['SUCCEEDED', 'FAILED'] as const)('polls until the %s terminal state', async terminalStatus => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ id: 'job-1', status: 'PENDING', progress: 0 }))
      .mockResolvedValueOnce(jsonResponse({ id: 'job-1', status: 'RUNNING', progress: 50 }))
      .mockResolvedValueOnce(jsonResponse({ id: 'job-1', status: terminalStatus, progress: 100 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await knowledgeApi.pollIndexJob('job-1', { maxAttempts: 5, intervalMs: 0 })

    expect(result.status).toBe(terminalStatus)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
