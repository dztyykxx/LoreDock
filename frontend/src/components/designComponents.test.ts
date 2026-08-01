import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import AppButton from './AppButton.vue'
import FormField from './FormField.vue'
import ProjectCard from './ProjectCard.vue'
import ProjectTabs from './ProjectTabs.vue'
import AppSidebar from './AppSidebar.vue'
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
   * 业务目的：项目卡片中的项目与分支事实必须以 API 响应为准，设计样例只能填补后端尚未提供的知识数量。
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
    expect(wrapper.text()).toContain('5 个分支')
    expect(wrapper.text()).toContain('默认 develop')
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
   * 业务目的：项目页签必须保留当前分支并把知识与设置导向真实路由，同时不再展示代码快照入口。
   */
  it('renders real project tab navigation with role-aware settings', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects/:identifier', name: 'project-knowledge', component: { template: '<div />' } },
        { path: '/projects/:projectId/settings', name: 'project-settings', component: { template: '<div />' } },
      ],
    })
    const wrapper = mount(ProjectTabs, {
      props: {
        active: 'knowledge',
        role: 'ADMIN',
        projectIdentifier: 'api-project',
        projectId: project.id,
        branch: 'feature/import',
      },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-tab="knowledge"]').attributes('href')).toContain('branch=feature/import')
    expect(wrapper.get('[data-tab="settings"]').attributes('href')).toBe(`/projects/${project.id}/settings`)
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
        { path: '/projects/:identifier', name: 'project-knowledge', component: { template: '<div />' } },
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
    expect(wrapper.get('[data-testid="current-project-link"]').attributes('href')).toBe('/projects/api-project')
  })
})
