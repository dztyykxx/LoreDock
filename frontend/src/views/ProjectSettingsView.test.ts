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
    addBranch: vi.fn(),
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
   * 业务目的：管理员设置页必须展示管理 API 的真实字段和只读基本信息，同时保留设计稿允许的未来快照样例。
   */
  it('renders real administrator details with read-only fields and design samples', async () => {
    const api = createProjectApi()
    const { wrapper } = await mountSettings('ADMIN', api)

    expect(api.getAdminProject).toHaveBeenCalledWith(adminProject.id)
    expect(wrapper.text()).toContain('真实网络设计项目')
    expect(wrapper.text()).toContain('network-designer-api')
    expect(wrapper.text()).toContain('feature/import-export')
    expect(wrapper.text()).toContain('活动快照 a41e9c7')
    expect(wrapper.get('#project-name').attributes('readonly')).toBeDefined()
    expect(wrapper.find('[data-testid="open-add-branch"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-project-status"]').exists()).toBe(true)
  })

  /**
   * 业务目的：成员必须通过普通详情接口只读查看和切换分支，不能看到任何创建分支或启停操作。
   */
  it('degrades members to read-only detail and reloads the selected branch', async () => {
    const getProject = vi.fn()
      .mockResolvedValueOnce(memberProject)
      .mockResolvedValueOnce({ ...memberProject, selectedBranch: 'feature/import-export' })
    const api = createProjectApi({ getProject })
    const { wrapper } = await mountSettings('MEMBER', api)

    await wrapper.get('[data-testid="branch-selector"]').setValue('feature/import-export')
    await flushPromises()

    expect(getProject).toHaveBeenNthCalledWith(1, memberProject.identifier, undefined)
    expect(getProject).toHaveBeenNthCalledWith(2, memberProject.identifier, 'feature/import-export')
    expect(wrapper.find('[data-testid="open-add-branch"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-project-status"]').exists()).toBe(false)
  })

  /**
   * 业务目的：管理员添加分支后必须重新读取管理详情，确保并发冲突和服务端规范化结果不会被客户端猜测覆盖。
   */
  it('adds a branch and refreshes from the administrator endpoint', async () => {
    const addBranch = vi.fn().mockResolvedValue(branches[1])
    const getAdminProject = vi.fn().mockResolvedValue(adminProject)
    const { wrapper } = await mountSettings('ADMIN', createProjectApi({ addBranch, getAdminProject }))

    await wrapper.get('[data-testid="open-add-branch"]').trigger('click')
    await wrapper.get('#branch-name').setValue('feature/new-flow')
    await wrapper.get('[data-testid="add-branch-form"]').trigger('submit')
    await flushPromises()

    expect(addBranch).toHaveBeenCalledWith(adminProject.id, 'feature/new-flow')
    expect(getAdminProject).toHaveBeenCalledTimes(2)
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
   * 业务目的：添加分支对话框必须让键盘焦点进入首字段并支持 Escape 关闭，避免管理员在遮罩层后继续误操作页面。
   */
  it('provides an initial focus target and escape close for the branch dialog', async () => {
    const { wrapper } = await mountSettings('ADMIN', createProjectApi())

    await wrapper.get('[data-testid="open-add-branch"]').trigger('click')
    const branchField = wrapper.get('#branch-name')

    expect(branchField.attributes('autofocus')).toBeDefined()
    await branchField.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
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
