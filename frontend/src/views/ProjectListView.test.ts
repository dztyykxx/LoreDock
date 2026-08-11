import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectApi } from '../api/projects'
import type { AdminProjectDetail, ProjectSummary, SessionView } from '../api/types'
import { projectApiKey, sessionKey } from '../appContext'
import type { SessionController, SessionStatus } from '../session/useSession'
import ProjectListView from './ProjectListView.vue'

const projects: ProjectSummary[] = [
  {
    id: 1,
    identifier: 'network-designer-api',
    name: '真实网络设计项目',
    description: '服务端返回的网络工具',
    technologyStack: 'Java 21 + Vue 3',
    defaultBranch: 'main',
    branchCount: 3,
  },
  {
    id: 2,
    identifier: 'lightweight-comparison',
    name: '轻量对照项目',
    description: '检索隔离验收',
    technologyStack: 'Spring Boot',
    defaultBranch: 'develop',
    branchCount: 1,
  },
]

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
    listProjects: vi.fn().mockResolvedValue(projects),
    getProject: vi.fn(),
    getAdminProject: vi.fn(),
    createProject: vi.fn(),
    changeStatus: vi.fn(),
    ...overrides,
  }
}

async function mountList(role: SessionView['role'], api: ProjectApi) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: { template: '<div />' } },
      { path: '/projects', component: ProjectListView },
      { path: '/projects/:projectId/settings', component: { template: '<div />' } },
      { path: '/projects/:identifier', component: { template: '<div />' } },
    ],
  })
  await router.push('/projects')
  await router.isReady()
  const wrapper = mount(ProjectListView, {
    global: {
      plugins: [router],
      provide: {
        [sessionKey as symbol]: createSession(role),
        [projectApiKey as symbol]: api,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('ProjectListView', () => {
  beforeEach(() => vi.restoreAllMocks())

  /**
   * 业务目的：项目卡片只展示真实项目字段，不展示伪造的知识数量，也不暴露后端保留的默认分支和分支数量。
   */
  it('renders real project fields without fabricated counts', async () => {
    const wrapper = await mountList('MEMBER', createProjectApi())

    expect(wrapper.text()).toContain('2 个项目')
    expect(wrapper.text()).toContain('真实网络设计项目')
    expect(wrapper.text()).toContain('network-designer-api')
    expect(wrapper.text()).not.toContain('3 个分支')
    expect(wrapper.text()).not.toContain('默认 main')
    expect(wrapper.text()).not.toContain('篇知识')
    expect(wrapper.text()).not.toContain('sample-service')
  })

  /**
   * 业务目的：筛选只能在已授权且已加载的项目集合内按名称或标识执行，防止额外请求扩大查询范围。
   */
  it('filters the loaded project collection locally', async () => {
    const api = createProjectApi()
    const wrapper = await mountList('MEMBER', api)

    await wrapper.get('[data-testid="project-filter"]').setValue('lightweight')

    expect(wrapper.text()).not.toContain('真实网络设计项目')
    expect(wrapper.text()).toContain('轻量对照项目')
    expect(api.listProjects).toHaveBeenCalledOnce()
  })

  /**
   * 业务目的：列表失败时不能把过期内容当成功结果，必须提供安全提示和明确重试入口。
   */
  it('shows a retryable safe error without stale projects', async () => {
    const listProjects = vi.fn()
      .mockRejectedValueOnce(new Error('database host leaked'))
      .mockResolvedValueOnce(projects)
    const wrapper = await mountList('MEMBER', createProjectApi({ listProjects }))

    expect(wrapper.get('[role="alert"]').text()).toContain('项目列表加载失败')
    expect(wrapper.text()).not.toContain('database host leaked')
    await wrapper.get('[data-testid="retry-projects"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('真实网络设计项目')
  })

  /**
   * 业务目的：创建入口只向管理员显示，创建成功后必须重新以服务端列表为准，防止成员写入或客户端伪造项目。
   */
  it('allows administrators to create and refresh projects while hiding the action from members', async () => {
    const created = {
      ...projects[0],
      status: 'ENABLED',
      branches: [],
      createdAt: '2026-07-30T00:00:00Z',
      updatedAt: '2026-07-30T00:00:00Z',
      createdBy: 'admin',
      updatedBy: 'admin',
    } satisfies AdminProjectDetail
    const createProject = vi.fn().mockResolvedValue(created)
    const listProjects = vi.fn().mockResolvedValue(projects)
    const adminWrapper = await mountList('ADMIN', createProjectApi({ createProject, listProjects }))

    await adminWrapper.get('[data-testid="open-create-project"]').trigger('click')
    await adminWrapper.get('#project-name').setValue('新项目')
    await adminWrapper.get('#project-identifier').setValue('new-project')
    await adminWrapper.get('#project-description').setValue('项目简介')
    await adminWrapper.get('#project-stack').setValue('Java 21')
    await adminWrapper.get('[data-testid="create-project-form"]').trigger('submit')
    await flushPromises()

    expect(createProject).toHaveBeenCalledWith({
      name: '新项目',
      identifier: 'new-project',
      description: '项目简介',
      technologyStack: 'Java 21',
    })
    expect(listProjects).toHaveBeenCalledTimes(2)

    const memberWrapper = await mountList('MEMBER', createProjectApi())
    expect(memberWrapper.find('[data-testid="open-create-project"]').exists()).toBe(false)
  })

  /**
   * 业务目的：电脑端创建对话框必须为键盘用户标明首个输入点，并允许用 Escape 退出，防止焦点滞留在被遮挡的背景页。
   */
  it('provides an initial focus target and escape close for the create dialog', async () => {
    const wrapper = await mountList('ADMIN', createProjectApi())

    await wrapper.get('[data-testid="open-create-project"]').trigger('click')
    const firstField = wrapper.get('#project-name')

    expect(firstField.attributes('autofocus')).toBeDefined()
    await firstField.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})
