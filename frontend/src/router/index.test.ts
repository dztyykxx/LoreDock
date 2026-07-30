import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import type { SessionController, SessionStatus } from '../session/useSession'
import type { SessionView } from '../api/types'
import { createLoreDockRouter, installSessionGuards, resolveSafeRedirect } from './index'

function createSession(identity: SessionView | null): SessionController {
  const status = ref<SessionStatus>(identity ? 'authenticated' : 'anonymous')
  const currentIdentity = ref(identity)
  return {
    status,
    identity: currentIdentity,
    restore: vi.fn().mockResolvedValue(undefined),
    login: vi.fn(),
    logout: vi.fn(),
    clear: vi.fn(),
  }
}

function createTestRouter(session: SessionController) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/projects', name: 'projects', component: { template: '<div />' }, meta: { requiresAuth: true } },
      {
        path: '/projects/:projectId/settings',
        name: 'project-settings',
        component: { template: '<div />' },
        meta: { requiresAuth: true, adminOnly: true },
      },
      {
        path: '/knowledge/new',
        name: 'knowledge-global-new',
        component: { template: '<div />' },
        meta: { requiresAuth: true, adminOnly: true, memberFallback: '/knowledge' },
      },
    ],
  })
  installSessionGuards(router, session)
  return router
}

describe('router session guards', () => {
  /**
   * 业务目的：登录后只允许跳转到应用内部目标，防止 redirect 参数被利用为开放重定向或登录循环。
   */
  it('accepts only safe internal redirects', () => {
    expect(resolveSafeRedirect('/projects/demo')).toBe('/projects/demo')
    expect(resolveSafeRedirect('https://evil.example')).toBe('/projects')
    expect(resolveSafeRedirect('//evil.example')).toBe('/projects')
    expect(resolveSafeRedirect('/login?redirect=/login')).toBe('/projects')
  })

  /**
   * 业务目的：未登录访问业务页面必须回到登录页并保留安全目标，防止受保护内容在会话恢复前短暂暴露。
   */
  it('redirects anonymous users to login with the original target', async () => {
    const router = createTestRouter(createSession(null))

    await router.push('/projects')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/projects')
  })

  /**
   * 业务目的：只读成员即使手工输入管理地址也必须降级到普通项目入口，避免前端误展示管理操作。
   */
  it('degrades members away from administrator routes', async () => {
    const member: SessionView = { username: 'member', displayName: '组内成员', role: 'MEMBER' }
    const router = createTestRouter(createSession(member))

    await router.push('/projects/8c6883fc-a928-4ef8-a6f7-0dd5d32b88d8/settings')

    expect(router.currentRoute.value.name).toBe('projects')
  })

  /**
   * 业务目的：管理员应能进入项目管理路由，防止角色守卫把合法写入口一并阻断。
   */
  it('allows administrators to open administrator routes', async () => {
    const administrator: SessionView = { username: 'admin', displayName: '管理员', role: 'ADMIN' }
    const router = createTestRouter(createSession(administrator))

    await router.push('/projects/8c6883fc-a928-4ef8-a6f7-0dd5d32b88d8/settings')

    expect(router.currentRoute.value.name).toBe('project-settings')
  })

  /**
   * 业务目的：知识目录、详情、新建、导入和编辑都必须有稳定路由，防止刷新后丢失范围或退回静态项目设置页。
   */
  it('declares global and project knowledge routes without replacing settings', () => {
    const administrator: SessionView = { username: 'admin', displayName: '管理员', role: 'ADMIN' }
    const router = createLoreDockRouter(createSession(administrator))

    expect(router.resolve('/knowledge').name).toBe('knowledge-global')
    expect(router.resolve('/knowledge/new').name).toBe('knowledge-global-new')
    expect(router.resolve('/knowledge/import').name).toBe('knowledge-global-import')
    expect(router.resolve('/knowledge/document-1').name).toBe('knowledge-global-detail')
    expect(router.resolve('/knowledge/document-1/edit').name).toBe('knowledge-global-edit')
    expect(router.resolve('/projects/network-designer').name).toBe('project-knowledge')
    expect(router.resolve('/projects/network-designer/knowledge/new').name).toBe('project-knowledge-new')
    expect(router.resolve('/projects/network-designer/knowledge/import').name).toBe('project-knowledge-import')
    expect(router.resolve('/projects/network-designer/knowledge/document-1').name).toBe('project-knowledge-detail')
    expect(router.resolve('/projects/network-designer/knowledge/document-1/edit').name).toBe('project-knowledge-edit')
    expect(router.resolve('/projects/project-1/settings').name).toBe('project-settings')
  })

  /**
   * 业务目的：成员访问管理员知识路由时应退回同一知识上下文，防止越权入口把用户带到无关项目列表并丢失浏览位置。
   */
  it('degrades members to the configured knowledge context', async () => {
    const member: SessionView = { username: 'member', displayName: '组内成员', role: 'MEMBER' }
    const router = createTestRouter(createSession(member))

    await router.push('/knowledge/new')

    expect(router.currentRoute.value.fullPath).toBe('/knowledge')
  })
})
