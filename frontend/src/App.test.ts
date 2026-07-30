import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import App from './App.vue'

describe('App', () => {
  /**
   * 业务目的：应用根组件必须交由路由渲染业务页面，防止旧状态页继续遮挡登录和项目入口。
   */
  it('renders the active business route', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<h1>项目空间</h1>' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(wrapper.text()).toContain('项目空间')
  })
})
