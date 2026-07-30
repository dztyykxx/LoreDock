import { ref, type Ref } from 'vue'
import { authApi, type AuthApi, type LoginInput } from '../api/auth'
import { ApiError } from '../api/http'
import type { SessionView } from '../api/types'

export type SessionStatus = 'checking' | 'authenticated' | 'anonymous'

export interface SessionController {
  status: Readonly<Ref<SessionStatus>>
  identity: Readonly<Ref<SessionView | null>>
  restore(): Promise<void>
  login(input: LoginInput): Promise<SessionView>
  logout(): Promise<void>
  clear(): void
}

export function createSessionController(api: AuthApi = authApi): SessionController {
  const status = ref<SessionStatus>('checking')
  const identity = ref<SessionView | null>(null)
  let restored = false

  const clear = () => {
    identity.value = null
    status.value = 'anonymous'
    restored = true
  }

  return {
    status,
    identity,
    async restore() {
      if (restored) {
        return
      }
      status.value = 'checking'
      try {
        identity.value = await api.getSession()
        status.value = 'authenticated'
        restored = true
      } catch (error) {
        clear()
        if (!(error instanceof ApiError && error.status === 401)) {
          throw error
        }
      }
    },
    async login(input) {
      const current = await api.login(input)
      identity.value = current
      status.value = 'authenticated'
      restored = true
      return current
    },
    async logout() {
      try {
        await api.logout()
      } finally {
        // 即使网络中断也清除页面身份；服务端 Cookie 是否仍有效会在下一次登录或刷新时重新判定。
        clear()
      }
    },
    clear,
  }
}
