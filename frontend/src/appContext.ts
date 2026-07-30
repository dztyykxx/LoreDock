import { inject, type InjectionKey } from 'vue'
import type { ProjectApi } from './api/projects'
import type { KnowledgeApi } from './api/knowledge'
import type { SessionController } from './session/useSession'
import type { QaApi } from './api/qa'

export const sessionKey: InjectionKey<SessionController> = Symbol('loredock-session')
export const projectApiKey: InjectionKey<ProjectApi> = Symbol('loredock-project-api')
export const knowledgeApiKey: InjectionKey<KnowledgeApi> = Symbol('loredock-knowledge-api')
export const qaApiKey: InjectionKey<QaApi> = Symbol('loredock-qa-api')

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

export function useKnowledgeApi(): KnowledgeApi {
  const api = inject(knowledgeApiKey)
  if (!api) {
    throw new Error('LoreDock knowledge API is not provided')
  }
  return api
}

export function useQaApi(): QaApi {
  const api = inject(qaApiKey)
  if (!api) {
    throw new Error('LoreDock QA API is not provided')
  }
  return api
}
