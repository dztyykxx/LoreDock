import { inject, type InjectionKey } from 'vue'
import type { ProjectApi } from './api/projects'
import type { SessionController } from './session/useSession'

export const sessionKey: InjectionKey<SessionController> = Symbol('loredock-session')
export const projectApiKey: InjectionKey<ProjectApi> = Symbol('loredock-project-api')

export function useSession(): SessionController {
  const session = inject(sessionKey)
  if (!session) {
    throw new Error('LoreDock session is not provided')
  }
  return session
}

export function useProjectApi(): ProjectApi {
  const api = inject(projectApiKey)
  if (!api) {
    throw new Error('LoreDock project API is not provided')
  }
  return api
}
