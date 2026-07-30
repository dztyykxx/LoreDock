import { describe, expect, it, vi } from 'vitest'
import type { AuthApi } from '../api/auth'
import type { SessionView } from '../api/types'
import { createSessionController } from './useSession'

const administrator: SessionView = {
  username: 'admin',
  displayName: '管理员',
  role: 'ADMIN',
}

function createAuthApi(overrides: Partial<AuthApi> = {}): AuthApi {
  return {
    login: vi.fn().mockResolvedValue(administrator),
    getSession: vi.fn().mockResolvedValue(administrator),
    logout: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

describe('createSessionController', () => {
  /**
   * 业务目的：刷新页面后必须通过服务端恢复有效身份，防止用户被无意义地要求重复输入密码。
   */
  it('restores an authenticated browser session', async () => {
    const session = createSessionController(createAuthApi())

    await session.restore()

    expect(session.status.value).toBe('authenticated')
    expect(session.identity.value).toEqual(administrator)
  })

  /**
   * 业务目的：登录结果必须以服务端返回角色为准，防止客户端自行赋予管理员权限。
   */
  it('uses the identity returned by the login endpoint', async () => {
    const member: SessionView = { username: 'member', displayName: '组内成员', role: 'MEMBER' }
    const session = createSessionController(createAuthApi({ login: vi.fn().mockResolvedValue(member) }))

    await session.login({ username: 'member', password: 'secret' })

    expect(session.identity.value).toEqual(member)
    expect(session.status.value).toBe('authenticated')
  })
})
