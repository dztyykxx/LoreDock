import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref, type Ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectApi } from '../api/projects'
import type { AdminProjectDetail, BranchView, ProjectDetail, SessionView } from '../api/types'
import { projectApiKey, sessionKey } from '../appContext'
import type { SessionController, SessionStatus } from '../session/useSession'
import ProjectSettingsView from './ProjectSettingsView.vue'

const branches: BranchView[] = [
  {
    id: 11,
    name: 'main',
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:00Z',
    createdBy: 'admin',
    updatedBy: 'admin',
  },
  {
    id: 12,
    name: 'feature/import-export',
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:00Z',
    createdBy: 'admin',
    updatedBy: 'admin',
  },
]

const adminProject: AdminProjectDetail = {
  id: 1,
  identifier: 'network-designer-api',
  name: '真实网络设计项目',
  description: '服务端真实简介',
  technologyStack: 'Java 21 + Vue 3',
  status: 'ENABLED',
  defaultBranch: 'main',
  branches,
  createdAt: '2026-07-30T00:00:00Z',
  updatedAt: '2026-07-30T00:00:00Z',
  createdBy: 'admin',
  updatedBy: 'admin',
}

const memberProject: ProjectDetail = {
  id: adminProject.id,
  identifier: adminProject.identifier,
  name: adminProject.name,
  description: adminProject.description,
  technologyStack: adminProject.technologyStack,
  defaultBranch: 'main',
  selectedBranch: 'main',
  branches,
}

function createSession(role: SessionView['role']): SessionController {
  const identity: SessionView = {
    username: role === 'ADMIN' ? 'admin' : 'member',
    displayName: role === 'ADMIN' ? '管理员' : '组内成员',
    role,
  }
  return {
    status: ref<SessionStatus>('authenticated'),
    identity: ref(identity),
    restore: vi.fn().mockResolvedValue(undefined),
    login: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    clear: vi.fn(),
  }
}

function createProjectApi(overrides: Partial<ProjectApi> = {}): ProjectApi {
  return {
    listProjects: vi.fn(),
    getProject: vi.fn().mockResolvedValue(memberProject),
    getAdminProject: vi.fn().mockResolvedValue(adminProject),
    createProject: vi.fn(),
    changeStatus: vi.fn(),
    ...overrides,
  }
}

async function mountSettings(role: SessionView['role'], api: ProjectApi, session = createSession(role)) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: { template: '<div />' } },
      { path: '/projects', name: 'projects', component: { template: '<div />' } },
      { path: '/projects/:projectId/settings', name: 'project-settings', component: ProjectSettingsView },
      { path: '/projects/:identifier', name: 'project-detail', component: ProjectSettingsView },
    ],
  })
  await router.push(role === 'ADMIN'
    ? `/projects/${adminProject.id}/settings`
    : `/projects/${memberProject.identifier}`)
  await router.isReady()
  const wrapper = mount(ProjectSettingsView, {
    global: {
      plugins: [router],
      provide: {
        [sessionKey as symbol]: session,
        [projectApiKey as symbol]: api,
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ProjectSettingsView', () => {
  beforeEach(() => vi.restoreAllMocks())

  /**
   * 业务目的：管理员设置页只展示 MVP 仍开放的项目字段与状态操作，不暴露后端保留的分支数据。
   */
  it('renders project details without branch management controls', async () => {
    const api = createProjectApi()
    const { wrapper } = await mountSettings('ADMIN', api)

    expect(api.getAdminProject).toHaveBeenCalledWith(adminProject.id)
    expect(wrapper.text()).toContain('真实网络设计项目')
    expect(wrapper.text()).toContain('network-designer-api')
    expect(wrapper.text()).not.toContain('feature/import-export')
    expect(wrapper.get('#project-name').attributes('readonly')).toBeDefined()
    expect(wrapper.find('[data-testid="branch-selector"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-add-branch"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-project-status"]').exists()).toBe(true)
  })

  /**
   * 业务目的：成员通过普通详情接口只读查看项目时，前端不得传递查询分支或显示分支操作。
   */
  it('loads member details through the default project scope', async () => {
    const getProject = vi.fn().mockResolvedValue(memberProject)
    const api = createProjectApi({ getProject })
    const { wrapper } = await mountSettings('MEMBER', api)

    expect(getProject).toHaveBeenCalledOnce()
    expect(getProject).toHaveBeenCalledWith(memberProject.identifier)
    expect(wrapper.find('[data-testid="branch-selector"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-add-branch"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-project-status"]').exists()).toBe(false)
  })

  /**
   * 业务目的：停用项目前必须明确说明普通查询退出但数据保留，确认后才调用幂等状态接口并使用返回结果刷新。
   */
  it('confirms preservation semantics before disabling a project', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const changeStatus = vi.fn().mockResolvedValue({ ...adminProject, status: 'DISABLED' })
    const { wrapper } = await mountSettings('ADMIN', createProjectApi({ changeStatus }))

    await wrapper.get('[data-testid="change-project-status"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('退出普通查询'))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('数据仍会保留'))
    expect(changeStatus).toHaveBeenCalledWith(adminProject.id, 'DISABLED')
    expect(wrapper.text()).toContain('停用')
  })

  /**
   * 业务目的：详情查询失败必须隐藏旧详情并提供安全重试，防止用户基于过期状态执行管理操作。
   */
  it('recovers from a safe retry state after a detail failure', async () => {
    const getAdminProject = vi.fn()
      .mockRejectedValueOnce(new Error('internal database address'))
      .mockResolvedValueOnce(adminProject)
    const { wrapper } = await mountSettings('ADMIN', createProjectApi({ getAdminProject }))

    expect(wrapper.get('[role="alert"]').text()).toContain('项目详情加载失败')
    expect(wrapper.text()).not.toContain('internal database address')
    await wrapper.get('[data-testid="retry-project-detail"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('真实网络设计项目')
  })

  /**
   * 业务目的：退出或 401 清除会话后、路由卸载前的短暂窗口不得读取空身份，防止真实浏览器出现渲染异常或残留越权界面。
   */
  it('renders safely while a cleared session is waiting for route replacement', async () => {
    const session = createSession('ADMIN')
    const { wrapper } = await mountSettings('ADMIN', createProjectApi(), session)

    const writableIdentity = session.identity as Ref<SessionView | null>
    writableIdentity.value = null

    await expect(nextTick()).resolves.toBeUndefined()
    expect(wrapper.find('.settings-content').exists()).toBe(false)
  })
})
