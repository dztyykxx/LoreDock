import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import AppButton from './AppButton.vue'
import FormField from './FormField.vue'
import ProjectCard from './ProjectCard.vue'
import ProjectTabs from './ProjectTabs.vue'
import AppSidebar from './AppSidebar.vue'
import AppTopBar from './AppTopBar.vue'
import { qaApiKey } from '../appContext'
import type { ProjectSummary } from '../api/types'

const project: ProjectSummary = {
  id: 1,
  identifier: 'api-project',
  name: '接口返回项目',
  description: '来自服务端的项目简介',
  technologyStack: 'Java 21 + Vue 3',
  defaultBranch: 'develop',
  branchCount: 5,
}

describe('design components', () => {
  /**
   * 业务目的：提交中的按钮必须阻止重复操作并向辅助技术暴露忙碌状态，防止创建项目或登录被重复发送。
   */
  it('disables busy buttons and exposes their state', async () => {
    const wrapper = mount(AppButton, {
      props: { busy: true, busyLabel: '正在提交' },
      slots: { default: '提交' },
    })

    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toContain('正在提交')
  })

  /**
   * 业务目的：账号、密码和项目字段必须具有真实 label/help 关联，防止键盘和辅助技术用户无法判断输入含义。
   */
  it('associates field labels and help text with the control', () => {
    const wrapper = mount(FormField, {
      props: {
        id: 'username',
        label: '账号',
        help: '请输入管理员或组内账号',
        modelValue: '',
      },
    })

    expect(wrapper.get('label').attributes('for')).toBe('username')
    expect(wrapper.get('input').attributes('aria-describedby')).toBe('username-help')
    expect(wrapper.get('#username-help').text()).toContain('管理员或组内账号')
  })

  /**
   * 业务目的：项目卡片只展示 MVP 需要的项目事实，后端保留的分支字段不得成为前端能力。
   */
  it('keeps real project fields while rendering the design knowledge sample', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:identifier', component: { template: '<div />' } }],
    })
    const wrapper = mount(ProjectCard, {
      props: { project, role: 'MEMBER', sampleKnowledgeCount: 26 },
      global: { plugins: [router] },
    })

    expect(wrapper.text()).toContain('接口返回项目')
    expect(wrapper.text()).toContain('api-project')
    expect(wrapper.text()).not.toContain('5 个分支')
    expect(wrapper.text()).not.toContain('默认 develop')
    expect(wrapper.text()).toContain('26 篇知识')
    expect(wrapper.text()).not.toContain('network-designer')
  })

  /**
   * 业务目的：项目卡片对管理员和成员都应进入真实项目知识页，防止管理员被错误送往设置页而无法浏览知识。
   */
  it.each(['ADMIN', 'MEMBER'] as const)('opens project knowledge from a %s project card', role => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:identifier', name: 'project-knowledge', component: { template: '<div />' } }],
    })
    const wrapper = mount(ProjectCard, {
      props: { project, role, sampleKnowledgeCount: 26 },
      global: { plugins: [router] },
    })

    expect(wrapper.get('a').attributes('href')).toBe('/projects/api-project')
  })

  /**
   * 业务目的：项目页签必须使用无分支参数的真实路由，同时不再展示代码快照入口。
   */
  it('renders real project tab navigation with role-aware settings', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects/:identifier', name: 'project-knowledge', component: { template: '<div />' } },
        { path: '/projects/:identifier/qa', name: 'project-qa', component: { template: '<div />' } },
        { path: '/projects/:identifier/drafts', name: 'project-drafts', component: { template: '<div />' } },
        { path: '/projects/:identifier/knowledge-tasks', name: 'project-knowledge-tasks', component: { template: '<div />' } },
        { path: '/projects/:projectId/settings', name: 'project-settings', component: { template: '<div />' } },
      ],
    })
    const wrapper = mount(ProjectTabs, {
      props: {
        active: 'knowledge',
        role: 'ADMIN',
        projectIdentifier: 'api-project',
        projectId: project.id,
      },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-tab="knowledge"]').attributes('href')).toBe('/projects/api-project')
    expect(wrapper.get('[data-tab="drafts"]').attributes('href')).toBe('/projects/api-project/drafts')
    expect(wrapper.get('[data-tab="tasks"]').attributes('href')).toBe('/projects/api-project/knowledge-tasks')
    expect(wrapper.get('[data-tab="settings"]').attributes('href')).toBe(`/projects/${project.id}/settings`)
    expect(wrapper.find('[data-tab="changes"]').exists()).toBe(false)
    expect(wrapper.find('[data-tab="reports"]').exists()).toBe(false)
    expect(wrapper.find('[data-tab="code-snapshots"]').exists()).toBe(false)
  })

  /**
   * 业务目的：侧栏的通用知识和当前项目必须是可键盘访问的真实入口，防止继续以禁用样例控件冒充导航。
   */
  it('links the sidebar to global and current-project knowledge', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects', component: { template: '<div />' } },
        { path: '/knowledge', name: 'knowledge-global', component: { template: '<div />' } },
        { path: '/search', name: 'global-search', component: { template: '<div />' } },
        { path: '/projects/:identifier', name: 'project-knowledge', component: { template: '<div />' } },
        { path: '/projects/:identifier/qa', name: 'project-qa', component: { template: '<div />' } },
      ],
    })
    const wrapper = mount(AppSidebar, {
      props: {
        displayName: '管理员',
        role: 'ADMIN',
        currentProject: { name: '接口返回项目', identifier: 'api-project' },
      },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-testid="global-knowledge-link"]').attributes('href')).toBe('/knowledge')
    expect(wrapper.get('[data-testid="global-search-link"]').attributes('href')).toBe('/search')
    expect(wrapper.get('[data-testid="current-project-link"]').attributes('href')).toBe('/projects/api-project')
    expect(wrapper.get('[data-testid="sidebar-qa-link"]').attributes('href')).toBe('/projects/api-project/qa')
    expect(wrapper.get('[data-testid="sidebar-new-question-link"]').attributes('href')).toBe('/projects/api-project/qa?new=1')
  })

  /**
   * 业务目的：项目顶部工作空间必须是返回项目列表的真实链接，不能继续以不可点击文本制造导航死路。
   */
  it('links the project breadcrumb workspace back to projects', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects', name: 'projects', component: { template: '<div />' } }],
    })
    const wrapper = mount(AppTopBar, {
      props: { projectName: '接口返回项目' },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-testid="workspace-link"]').attributes('href')).toBe('/projects')
  })

  /**
   * 业务目的：没有当前项目时问答入口应进入全局（全库）问答页，检索范围包含通用与各项目，
   * 不再引导回项目列表。
   */
  it('routes generic sidebar questions to global qa page', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects', name: 'projects', component: { template: '<div />' } }],
    })
    const wrapper = mount(AppSidebar, {
      props: { displayName: '管理员', role: 'ADMIN' },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-testid="sidebar-qa-link"]').attributes('href')).toBe('/qa')
    expect(wrapper.get('[data-testid="sidebar-new-question-link"]').attributes('href')).toBe('/qa?new=1')
  })

  /**
   * 业务目的：无项目侧栏的最近问答必须来自跨项目会话接口并标注检索范围（全局/项目：名称），
   * 支持游标加载更早记录；防止继续以静态样例冒充真实历史。
   */
  it('renders cross-project recent conversations with scope labels', async () => {
    const conversationsGlobal = vi.fn()
      .mockResolvedValueOnce({
        items: [
          { conversationId: 1, projectIdentifier: 'GLOBAL', projectName: null, scope: 'GLOBAL', title: '全局问题', lastQuestion: '全局问题', status: 'COMPLETED', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', lastQuestionAt: '2026-08-01T00:00:00Z' },
          { conversationId: 2, projectIdentifier: 'api-project', projectName: '接口返回项目', scope: 'PROJECT', title: '项目问题', lastQuestion: '项目问题', status: 'COMPLETED', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', lastQuestionAt: '2026-08-01T00:00:00Z' },
        ],
        nextCursor: 'cursor-1',
      })
      .mockResolvedValueOnce({ items: [], nextCursor: null })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects', component: { template: '<div />' } },
        { path: '/qa', name: 'global-qa', component: { template: '<div />' } },
      ],
    })
    const wrapper = mount(AppSidebar, {
      props: { displayName: '管理员', role: 'ADMIN' },
      global: {
        plugins: [router],
        provide: { [qaApiKey]: { conversationsGlobal } },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('全局问题')
    expect(wrapper.text()).toContain('项目：接口返回项目')
    expect(wrapper.get('.qa-load-more').text()).toContain('加载更早记录')
    await wrapper.get('.qa-load-more').trigger('click')
    await flushPromises()
    expect(conversationsGlobal).toHaveBeenCalledWith('cursor-1', 10)
  })
})
