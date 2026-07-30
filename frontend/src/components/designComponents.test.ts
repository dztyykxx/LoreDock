import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import AppButton from './AppButton.vue'
import FormField from './FormField.vue'
import ProjectCard from './ProjectCard.vue'
import ProjectTabs from './ProjectTabs.vue'
import type { ProjectSummary } from '../api/types'

const project: ProjectSummary = {
  id: 'a4f0a282-4911-4d36-84cd-135302001687',
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
   * 业务目的：尚无接口的未来标签允许展示设计样例，但点击不得制造网络请求或伪造已实现路由。
   */
  it('renders future sample tabs as non-executable controls', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ProjectTabs, { props: { active: 'settings' } })

    expect(wrapper.text()).toContain('知识文档')
    expect(wrapper.text()).toContain('26')
    await wrapper.get('[data-tab="knowledge"]').trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('[data-tab="settings"]').attributes('aria-current')).toBe('page')
    vi.unstubAllGlobals()
  })
})
