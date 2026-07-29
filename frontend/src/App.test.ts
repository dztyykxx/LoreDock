import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

describe('App', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  /**
   * 业务目的：运行状态页必须明确展示后端已经可用，防止完整栈启动后只能凭空白页面猜测服务状态。
   */
  it('后端状态正常时展示可用状态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ service: 'loredock', status: 'UP' }),
    }))

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('后端服务可用')
  })

  /**
   * 业务目的：后端不可连接时页面必须给出可诊断提示，防止把基础设施故障误认为前端空白或构建失败。
   */
  it('后端不可连接时展示不可用状态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connection refused')))

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('后端服务暂不可用')
  })
})
