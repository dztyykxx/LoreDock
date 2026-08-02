import { mount } from '@vue/test-utils'
import type { Component } from 'vue'
import { describe, expect, it } from 'vitest'

const task = {
  conversationId: 41,
  projectIdentifier: 'atlas',
  goal: '整理项目约束',
  currentDraftId: 51,
  currentDraftRevision: 3,
  messages: [
    { messageId: 1, runId: null, role: 'SYSTEM_TRIGGER', subjectName: null, content: '每周整理触发', createdAt: '2026-08-02T00:00:00Z' },
    { messageId: 2, runId: 61, role: 'TOOL', subjectName: 'draft_update', content: '生成修订 3', createdAt: '2026-08-02T00:01:00Z' },
  ],
  runs: [{
    runId: 61, conversationId: 41, threadId: 'knowledge-task-41-run-61', status: 'RUNNING',
    checkpointSavedAt: '2026-08-02T00:00:30Z', stepCount: 4, modelCallCount: 2, toolCallCount: 2,
    definition: { skillName: 'knowledge_curator', skillDigest: 'abc', agentSpecDigest: 'def', modelName: 'deepseek-v4-flash', toolNames: ['draft_read', 'draft_update'] },
  }],
}

const revisions = [
  { revision: 1, changeSummary: '建立背景', createdAt: '2026-08-02T00:00:20Z' },
  { revision: 2, changeSummary: '补充来源', createdAt: '2026-08-02T00:00:40Z' },
  { revision: 3, changeSummary: '收敛建议', createdAt: '2026-08-02T00:01:00Z' },
]

const diff = {
  draftId: 51,
  fromRevision: null,
  toRevision: 3,
  unifiedDiff: '@@ -0,0 +1,2 @@\n+# Atlas 约束\n+新增范围隔离规则',
  additions: 2,
  deletions: 0,
  truncated: true,
}

async function workspace(): Promise<Component> {
  const componentPath = './KnowledgeTaskWorkspace.vue'
  try {
    return (await import(/* @vite-ignore */ componentPath)).default
  } catch {
    // 红阶段允许测试先于组件落地；占位组件只让所有业务断言落到明确缺失的可观察行为。
    return { template: '<div data-testid="knowledge-task-workspace-missing" />' }
  }
}

async function mountWorkspace(overrides: Record<string, unknown> = {}) {
  return mount(await workspace() as any, {
    props: { task, revisions, diff, ...overrides },
  })
}

describe('KnowledgeTaskWorkspace', () => {
  /**
   * 业务目的：知识任务必须把对话过程与版本化草稿产物分栏展示，并默认折叠详细过程；
   * 防止把 Agent 最终消息误认为已经保存的待审核草稿。
   */
  it('separates conversation events from the versioned draft artifact', async () => {
    const wrapper = await mountWorkspace()

    expect(wrapper.get('[data-testid="knowledge-task-conversation"]').text()).toContain('每周整理触发')
    expect(wrapper.get('[data-testid="knowledge-task-artifact"]').text()).toContain('当前修订 3')
    expect(wrapper.get('[data-testid="knowledge-task-process"]').attributes('open')).toBeUndefined()
    expect(wrapper.text()).toContain('对话消息不等于草稿产物')
  })

  /**
   * 业务目的：运行中点击暂停只能先显示“当前步骤完成后暂停”，进入真实等待状态前不得展示指导恢复表单。
   */
  it('projects pause requested separately from waiting for guidance', async () => {
    const wrapper = await mountWorkspace()

    await wrapper.get('[data-testid="request-task-pause"]').trigger('click')

    expect(wrapper.emitted('request-pause')).toEqual([[61]])
    expect(wrapper.text()).toContain('将在当前步骤完成后暂停')
    expect(wrapper.find('[data-testid="resume-task-guidance"]').exists()).toBe(false)
  })

  /**
   * 业务目的：只有 WAITING_FOR_USER 才允许提交指导并恢复同一 run，同时显示最近 Checkpoint；
   * 防止前端把消息注入正在执行的模型或 Tool 调用。
   */
  it('offers guidance only for a checkpoint-backed waiting run', async () => {
    const waiting = { ...task, runs: [{ ...task.runs[0], status: 'WAITING_FOR_USER' }] }
    const wrapper = await mountWorkspace({ task: waiting })

    await wrapper.get('[data-testid="resume-task-guidance"]').setValue('优先核对适用版本')
    await wrapper.get('[data-testid="resume-task"]').trigger('click')

    expect(wrapper.text()).toContain('最近暂停点')
    expect(wrapper.emitted('resume')).toEqual([[{ runId: 61, guidance: '优先核对适用版本' }]])
  })

  /**
   * 业务目的：一轮完成后输入框仍须可用，追加意见触发新 run 而不是覆盖已完成运行和历史消息。
   */
  it('keeps the composer enabled after a run completes', async () => {
    const completed = { ...task, runs: [{ ...task.runs[0], status: 'COMPLETED' }] }
    const wrapper = await mountWorkspace({ task: completed })

    const composer = wrapper.get('[data-testid="continue-task-guidance"]')
    expect(composer.attributes('disabled')).toBeUndefined()
    await composer.setValue('删除没有双来源支持的建议')
    await wrapper.get('[data-testid="continue-task"]').trigger('click')

    expect(wrapper.emitted('continue-task')).toEqual([['删除没有双来源支持的建议']])
  })

  /**
   * 业务目的：审批必须展示服务端修订间 Markdown Diff、截断状态和明确修订；
   * 发布前若当前修订变化，页面必须阻止继续确认并要求重新审核。
   */
  it('renders revision diff and blocks publication after a revision conflict', async () => {
    const wrapper = await mountWorkspace({ publicationConflict: true })

    expect(wrapper.get('[data-testid="draft-revision-list"]').text()).toContain('修订 1')
    expect(wrapper.get('[data-testid="draft-revision-list"]').text()).toContain('修订 3')
    expect(wrapper.get('[data-testid="draft-markdown-diff"]').text()).toContain('+2')
    expect(wrapper.get('[data-testid="draft-markdown-diff"]').text()).toContain('# Atlas 约束')
    expect(wrapper.text()).toContain('Diff 已截断')
    expect(wrapper.text()).toContain('草稿已产生新修订，请重新查看 Diff')
    expect(wrapper.get('[data-testid="publish-reviewed-revision"]').attributes('disabled')).toBeDefined()
  })
})
