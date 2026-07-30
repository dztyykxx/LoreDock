import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, requestJson, setUnauthorizedHandler } from './http'

describe('requestJson', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * 业务目的：所有业务 API 必须携带浏览器会话 Cookie，防止页面看似登录但后续请求始终被当作匿名访问。
   */
  it('includes browser credentials and JSON headers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await requestJson('/api/example', { method: 'POST', body: JSON.stringify({ value: 1 }) })

    expect(fetchMock).toHaveBeenCalledOnce()
    const [path, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/example')
    expect(options.credentials).toBe('include')
    expect(new Headers(options.headers).get('Content-Type')).toBe('application/json')
  })

  /**
   * 业务目的：会话失效必须统一清理客户端身份，防止页面继续展示管理员控件并不断发送无效请求。
   */
  it('clears session and exposes stable error semantics on 401', async () => {
    const clearSession = vi.fn()
    setUnauthorizedHandler(clearSession)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'AUTH_LOGIN_REQUIRED',
      message: '请先登录',
      timestamp: '2026-07-30T00:00:00Z',
      traceId: 'trace-1',
      fieldErrors: [],
    }), { status: 401, headers: { 'Content-Type': 'application/json' } })))

    await expect(requestJson('/api/projects')).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_LOGIN_REQUIRED',
    } satisfies Partial<ApiError>)
    expect(clearSession).toHaveBeenCalledOnce()
  })
})
