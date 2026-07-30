import { requestJson } from './http'
import type { SessionView } from './types'

export interface LoginInput {
  username: string
  password: string
}

export interface AuthApi {
  login(input: LoginInput): Promise<SessionView>
  getSession(): Promise<SessionView>
  logout(): Promise<void>
}

export const authApi: AuthApi = {
  login(input) {
    return requestJson<SessionView>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(input),
    })
  },
  getSession() {
    return requestJson<SessionView>('/api/auth/session')
  },
  logout() {
    return requestJson<void>('/api/auth/logout', { method: 'POST' })
  },
}
