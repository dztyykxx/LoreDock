import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './http'
import { qaApi } from './qa'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('qaApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * 业务目的：项目、分支与幂等键必须原样进入问答契约，防止页面在重试时意外创建另一个范围的运行。
   */
  it('encodes the project path and sends a scoped idempotent question', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ questionId: 'question-1' }, 202))
    vi.stubGlobal('fetch', fetchMock)

    await qaApi.createQuestion('network/designer', {
      idempotencyKey: 'qa-key-1',
      branch: 'feature/导入',
      question: '为什么需要范围锁定？',
    })

    const [path, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/projects/network%2Fdesigner/qa/questions')
    expect(request.method).toBe('POST')
    expect(JSON.parse(String(request.body))).toEqual({
      idempotencyKey: 'qa-key-1',
      branch: 'feature/导入',
      question: '为什么需要范围锁定？',
    })
  })

  /**
   * 业务目的：SSE 断线续读必须携带最后已消费序号，防止重复显示文本增量或重新触发运行。
   */
  it('opens an authenticated event stream after the consumed sequence', () => {
    const source = { addEventListener: vi.fn(), close: vi.fn() }
    const factory = vi.fn().mockReturnValue(source)

    const connection = qaApi.openEventStream(
      'network/designer',
      'question-1',
      8,
      { onEvent: vi.fn(), onError: vi.fn() },
      factory,
    )

    expect(factory).toHaveBeenCalledWith(
      '/api/projects/network%2Fdesigner/qa/questions/question-1/events?afterSequence=8',
      { withCredentials: true },
    )
    expect(source.addEventListener).toHaveBeenCalledWith('answer.delta', expect.any(Function))
    expect(source.addEventListener).toHaveBeenCalledWith('run.completed', expect.any(Function))
    connection.close()
    expect(source.close).toHaveBeenCalledOnce()
  })

  /**
   * 业务目的：稳定后端错误码必须保留到页面状态，防止幂等冲突被误报成普通网络中断并无限重试。
   */
  it('preserves structured question creation failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      code: 'AGENT_RUN_IDEMPOTENCY_CONFLICT',
      message: '幂等键对应的请求不同',
      fieldErrors: [],
    }, 409)))

    await expect(qaApi.createQuestion('network-designer', {
      idempotencyKey: 'reused-key',
      branch: 'main',
      question: '新问题',
    })).rejects.toMatchObject({
      status: 409,
      code: 'AGENT_RUN_IDEMPOTENCY_CONFLICT',
    } satisfies Partial<ApiError>)
  })
})
