import { createRouter, createWebHistory, type Router } from 'vue-router'
import type { SessionController } from '../session/useSession'
import LoginView from '../views/LoginView.vue'
import ProjectListView from '../views/ProjectListView.vue'
import ProjectSettingsView from '../views/ProjectSettingsView.vue'
import KnowledgeWorkspaceView from '../views/KnowledgeWorkspaceView.vue'
import KnowledgeEditorView from '../views/KnowledgeEditorView.vue'

export function createLoreDockRouter(session: SessionController): Router {
  const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes: [
      { path: '/', redirect: '/projects' },
      { path: '/login', name: 'login', component: LoginView },
      {
        path: '/projects',
        name: 'projects',
        component: ProjectListView,
        meta: { requiresAuth: true },
      },
      {
        path: '/knowledge',
        name: 'knowledge-global',
        component: KnowledgeWorkspaceView,
        meta: { requiresAuth: true },
      },
      {
        path: '/knowledge/new',
        name: 'knowledge-global-new',
        component: KnowledgeEditorView,
        meta: { requiresAuth: true, adminOnly: true, memberFallback: '/knowledge' },
      },
      {
        path: '/knowledge/import',
        name: 'knowledge-global-import',
        component: KnowledgeEditorView,
        meta: { requiresAuth: true, adminOnly: true, memberFallback: '/knowledge' },
      },
      {
        path: '/knowledge/:documentId/edit',
        name: 'knowledge-global-edit',
        component: KnowledgeEditorView,
        meta: { requiresAuth: true, adminOnly: true, memberFallback: '/knowledge' },
      },
      {
        path: '/knowledge/:documentId',
        name: 'knowledge-global-detail',
        component: KnowledgeWorkspaceView,
        meta: { requiresAuth: true },
      },
      {
        path: '/projects/:projectId/settings',
        name: 'project-settings',
        component: ProjectSettingsView,
        meta: { requiresAuth: true, adminOnly: true },
      },
      {
        path: '/projects/:identifier/knowledge/new',
        name: 'project-knowledge-new',
        component: KnowledgeEditorView,
        meta: { requiresAuth: true, adminOnly: true, memberFallback: 'project-knowledge' },
      },
      {
        path: '/projects/:identifier/knowledge/import',
        name: 'project-knowledge-import',
        component: KnowledgeEditorView,
        meta: { requiresAuth: true, adminOnly: true, memberFallback: 'project-knowledge' },
      },
      {
        path: '/projects/:identifier/knowledge/:documentId/edit',
        name: 'project-knowledge-edit',
        component: KnowledgeEditorView,
        meta: { requiresAuth: true, adminOnly: true, memberFallback: 'project-knowledge' },
      },
      {
        path: '/projects/:identifier/knowledge/:documentId',
        name: 'project-knowledge-detail',
        component: KnowledgeWorkspaceView,
        meta: { requiresAuth: true },
      },
      {
        path: '/projects/:identifier',
        name: 'project-knowledge',
        component: KnowledgeWorkspaceView,
        meta: { requiresAuth: true },
      },
      { path: '/:pathMatch(.*)*', redirect: '/projects' },
    ],
  })
  installSessionGuards(router, session)
  return router
}

export function resolveSafeRedirect(_value: unknown): string {
  if (typeof _value !== 'string' || !_value.startsWith('/') || _value.startsWith('//')) {
    return '/projects'
  }
  const targetPath = _value.split(/[?#]/, 1)[0]
  if (targetPath === '/login') {
    return '/projects'
  }
  return _value
}

export function installSessionGuards(router: Router, session: SessionController): void {
  router.beforeEach(async to => {
    try {
      await session.restore()
    } catch {
      // 会话恢复的网络故障不能放行受保护页面；登录页会提供安全、可重试的入口。
      session.clear()
    }

    if (to.name === 'login') {
      return session.status.value === 'authenticated'
        ? resolveSafeRedirect(to.query.redirect)
        : true
    }

    if (to.meta.requiresAuth && session.status.value !== 'authenticated') {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (to.meta.adminOnly && session.identity.value?.role !== 'ADMIN') {
      if (to.meta.memberFallback === 'project-knowledge') {
        return { name: 'project-knowledge', params: { identifier: to.params.identifier }, query: to.query }
      }
      if (typeof to.meta.memberFallback === 'string') {
        return to.meta.memberFallback
      }
      return { name: 'projects' }
    }

    return true
  })
}
