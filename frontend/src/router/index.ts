import { createRouter, createWebHistory, type Router } from 'vue-router'
import type { SessionController } from '../session/useSession'
import LoginView from '../views/LoginView.vue'
import ProjectListView from '../views/ProjectListView.vue'
import ProjectSettingsView from '../views/ProjectSettingsView.vue'

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
        path: '/projects/:projectId/settings',
        name: 'project-settings',
        component: ProjectSettingsView,
        meta: { requiresAuth: true, adminOnly: true },
      },
      {
        path: '/projects/:identifier',
        name: 'project-detail',
        component: ProjectSettingsView,
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
      return { name: 'projects' }
    }

    return true
  })
}
