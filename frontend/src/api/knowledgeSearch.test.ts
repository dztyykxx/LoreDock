import { afterEach, describe, expect, it, vi } from 'vitest'
import { knowledgeSearchApi } from './knowledgeSearch'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('knowledgeSearchApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  /**
   * 业务目的：全局搜索必须固定使用通用知识范围和混合检索模式，
   * 防止前端误带项目参数或退化为单一路径检索。
   */
  it('requests global knowledge with the fixed hybrid contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      context: { type: 'GLOBAL', projectIdentifier: null, branch: null },
      mode: 'HYBRID',
      generationId: 91,
      warnings: [],
      results: [],
    }))
    vi.stubGlobal('fetch', fetchMock)

    await knowledgeSearchApi.searchGlobal('密码 重置')

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/knowledge-search?query=%E5%AF%86%E7%A0%81+%E9%87%8D%E7%BD%AE&context=GLOBAL&mode=HYBRID&limit=10')
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).credentials).toBe('include')
  })
})
