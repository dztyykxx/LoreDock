import { afterEach, describe, expect, it, vi } from 'vitest'
import { codeSnapshotApi } from './codeSnapshots'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('codeSnapshotApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * 业务目的：代码快照必须把服务端项目、分支和 Commit 边界与 ZIP 一起提交，且由浏览器生成 multipart 边界。
   */
  it('uploads a zip with the locked project branch and commit', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      snapshotId: 21,
      jobId: 31,
      status: 'PENDING',
      progress: 0,
    }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['zip'], 'nanobot.zip', { type: 'application/zip' })

    await codeSnapshotApi.upload({
      projectId: 1,
      branchId: 11,
      commit: 'a41e9c7',
      file,
    })

    const [path, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    const form = request.body as FormData
    expect(path).toBe('/api/admin/code-snapshots')
    expect(request.method).toBe('POST')
    expect(new Headers(request.headers).has('Content-Type')).toBe(false)
    expect(form.get('projectId')).toBe('1')
    expect(form.get('branchId')).toBe('11')
    expect(form.get('commit')).toBe('a41e9c7')
    expect(form.get('file')).toBe(file)
  })
})
