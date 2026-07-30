import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import type { SessionView } from '../api/types'
import { sessionKey } from '../appContext'
import type { SessionController, SessionStatus } from '../session/useSession'
import LoginView from './LoginView.vue'

const administrator: SessionView = { username: 'admin', displayName: '管理员', role: 'ADMIN' }

function createSession(login = vi.fn().mockResolvedValue(administrator)): SessionController {
  return {
    status: ref<SessionStatus>('anonymous'),
    identity: ref(null),
    restore: vi.fn().mockResolvedValue(undefined),
    login,
    logout: vi.fn().mockResolvedValue(undefined),
    clear: vi.fn(),
  }
}

async function createRouterForLogin(redirect?: string): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/projects', name: 'projects', component: { template: '<div>项目列表</div>' } },
      { path: '/projects/:identifier', name: 'project-detail', component: { template: '<div>项目详情</div>' } },
    ],
  })
  await router.push({ path: '/login', query: redirect ? { redirect } : undefined })
  await router.isReady()
  return router
}

describe('LoginView', () => {
  beforeEach(() => vi.restoreAllMocks())

  /**
   * 业务目的：登录页必须保留设计基线中的品牌、权限说明和明确字段，防止用户无法判断系统用途或账号权限。
   */
  it('renders the designed login content and accessible fields', async () => {
    const router = await createRouterForLogin()
    const wrapper = mount(LoginView, {
      global: { plugins: [router], provide: { [sessionKey as symbol]: createSession() } },
    })

    expect(wrapper.text()).toContain('让每个答案，都能回到证据。')
    expect(wrapper.text()).toContain('欢迎回来')
    expect(wrapper.text()).toContain('管理员可维护与发布')
    expect(wrapper.get('label[for="username"]').text()).toBe('账号')
    expect(wrapper.get('label[for="password"]').text()).toBe('密码')
  })

  /**
   * 业务目的：登录请求进行中必须禁止重复提交，防止慢网络下产生并发会话建立请求。
   */
  it('prevents duplicate submissions while login is pending', async () => {
    let resolveLogin!: (value: SessionView) => void
    const login = vi.fn().mockImplementation(() => new Promise<SessionView>(resolve => { resolveLogin = resolve }))
    const router = await createRouterForLogin()
    const wrapper = mount(LoginView, {
      global: { plugins: [router], provide: { [sessionKey as symbol]: createSession(login) } },
    })
    await wrapper.get('#username').setValue('admin')
    await wrapper.get('#password').setValue('secret')

    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(login).toHaveBeenCalledOnce()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('正在登录')
    resolveLogin(administrator)
    await flushPromises()
  })

  /**
   * 业务目的：错误凭据不得泄露账号是否存在，同时要保留账号、清空密码，让用户可以安全重试。
   */
  it('keeps username and clears password after a failed login', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError(401, 'AUTH_INVALID_CREDENTIALS', '认证失败'))
    const router = await createRouterForLogin()
    const wrapper = mount(LoginView, {
      global: { plugins: [router], provide: { [sessionKey as symbol]: createSession(login) } },
    })
    await wrapper.get('#username').setValue('unknown')
    await wrapper.get('#password').setValue('wrong-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect((wrapper.get('#username').element as HTMLInputElement).value).toBe('unknown')
    expect((wrapper.get('#password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号或密码不正确')
  })

  /**
   * 业务目的：登录成功后应回到经过校验的内部目标，防止丢失用户上下文或跳转到外部站点。
   */
  it('returns to a safe internal target after successful login', async () => {
    const router = await createRouterForLogin('/projects/api-project')
    const wrapper = mount(LoginView, {
      global: { plugins: [router], provide: { [sessionKey as symbol]: createSession() } },
    })
    await wrapper.get('#username').setValue('admin')
    await wrapper.get('#password').setValue('secret')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/projects/api-project')
  })
})
