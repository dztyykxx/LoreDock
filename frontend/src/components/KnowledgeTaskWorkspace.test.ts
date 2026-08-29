import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import KnowledgeTaskWorkspace from './KnowledgeTaskWorkspace.vue'

const task = {
  conversationId: 41,
  projectIdentifier: 'atlas',
  triggerType: 'MANUAL' as const,
  targetSkill: 'knowledge-curator',
  goal: '整理项目约束',
  status: 'PROCESSING' as const,
  selectedDrafts: [
    { documentId: 71, title: '需求约束', directory: '待整理', originalFilename: 'requirement.md', curationStatus: 'PROCESSING' as const },
    { documentId: 72, title: '实现经验', directory: '待整理', originalFilename: 'experience.md', curationStatus: 'PROCESSING' as const },
  ],
  currentDraftId: 51,
  currentDraftRevision: 3,
  workspaceDocuments: [
    { draftId: 51, operation: 'MODIFY' as const, baselineDocumentId: 11, baselineRevision: 4, title: '发布约束', directory: '项目规范', currentRevision: 3, lastChangedRunId: 61 },
    { draftId: 52, operation: 'ADD' as const, baselineDocumentId: null, baselineRevision: null, title: '复盘流程', directory: '团队协作', currentRevision: 1, lastChangedRunId: 61 },
  ],
  messages: [
    { messageId: 1, runId: null, role: 'SYSTEM_TRIGGER', subjectName: null, content: '管理员启动任务', createdAt: '2026-08-02T00:00:00Z' },
    { messageId: 2, runId: 61, role: 'COORDINATOR_AGENT', subjectName: '公开行动摘要', content: '我会先核对两份输入与现有发布规则，再分别修订冲突文档。', createdAt: '2026-08-02T00:00:10Z' },
    { messageId: 3, runId: 61, role: 'COORDINATOR_AGENT', subjectName: 'public_message:decision-1', content: '现有正式文档已经覆盖发布权限，因此我会修改它，而不是新增重复文档。', createdAt: '2026-08-02T00:00:15Z' },
    { messageId: 4, runId: 61, role: 'COORDINATOR_AGENT', subjectName: 'knowledge-curator', content: '**本轮完成**\n\n- 修订发布约束\n- 新增复盘流程', createdAt: '2026-08-02T00:00:25Z' },
  ],
  runs: [{
    runId: 61, conversationId: 41, threadId: 'knowledge-task-41-run-61', status: 'RUNNING' as const,
    checkpointSavedAt: null, stepCount: 4, modelCallCount: 2, toolCallCount: 1, errorCode: null,
    acceptedAt: '2026-08-02T00:00:05Z', startedAt: '2026-08-02T00:00:06Z', finishedAt: null,
    definition: { skillName: 'knowledge-curator', skillDigest: 'abc', agentSpecDigest: 'def', modelName: 'deepseek-v4-flash', toolNames: ['draft_read', 'draft_update'] },
  }],
  events: [
    { eventId: 101, runId: 61, sequence: 1, type: 'AGENT_STAGE' as const, subjectType: 'AGENT' as const,
      payload: { phase: 'START', name: 'coordinator', purpose: null, parameterSummary: null, resultSummary: null, count: null, durationMillis: null, status: 'COMPLETED', summary: '我会先核对两份输入与现有发布规则，再分别修订冲突文档。', textDelta: null, resultType: null, errorCode: null, modelGenerated: false, truncated: false },
      createdAt: '2026-08-02T00:00:08Z' },
    { eventId: 102, runId: 61, sequence: 2, type: 'AGENT_STAGE' as const, subjectType: 'AGENT' as const,
      payload: { phase: 'RETRIEVE', name: 'retriever', purpose: null, parameterSummary: null, resultSummary: null, count: null, durationMillis: null, status: 'COMPLETED', summary: '检索到发布权限相关事实。', textDelta: null, resultType: null, errorCode: null, modelGenerated: false, truncated: false },
      createdAt: '2026-08-02T00:00:30Z' },
    { eventId: 103, runId: 61, sequence: 3, type: 'AGENT_STAGE' as const, subjectType: 'AGENT' as const,
      payload: { phase: 'DECIDE', name: 'coordinator', purpose: null, parameterSummary: null, resultSummary: null, count: null, durationMillis: null, status: 'COMPLETED', summary: '因此我会修改它，而不是新增重复文档。', textDelta: null, resultType: null, errorCode: null, modelGenerated: false, truncated: false },
      createdAt: '2026-08-02T00:00:32Z' },
  ],
  toolInvocations: [{
    invocationId: 91, runId: 61, toolCallId: 'call-1', sequence: 1, toolName: 'knowledge_search',
    purpose: '检索相关业务知识', arguments: '{"query":"发布权限"}', result: '找到 3 份相关知识', resultSummary: '已完成近似文档检索', error: null,
    status: 'COMPLETED' as const, argumentsTruncated: false, resultTruncated: false,
    startedAt: '2026-08-02T00:00:20Z', finishedAt: '2026-08-02T00:00:21Z', durationMillis: 900,
  }],
  patchSets: [{
    runId: 61, additions: 18, deletions: 5,
    documents: [
      { draftId: 51, operation: 'MODIFY' as const, title: '发布约束', fromRevision: 2, toRevision: 3, additions: 8, deletions: 5 },
      { draftId: 52, operation: 'ADD' as const, title: '复盘流程', fromRevision: 0, toRevision: 1, additions: 10, deletions: 0 },
    ],
  }],
  lastEventSequence: 12,
}

const diff = {
  draftId: 51, fromRevision: null, toRevision: 3,
  unifiedDiff: '@@ -1,1 +1,2 @@\n-允许自动发布\n+必须由管理员审核\n+所有文档原子发布',
  additions: 2, deletions: 1, truncated: false,
}

describe('KnowledgeTaskWorkspace', () => {
  /** 业务目的：MVP 主页面必须以单列连续对话和累计审核条取代固定右侧草稿面板。 */
  it('uses a centered conversation with a cumulative review bar', () => {
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task } })
    expect(wrapper.get('[data-testid="workspace-review-bar"]').text()).toContain('2 份待审核文档')
    expect(wrapper.get('[data-testid="knowledge-task-conversation"] h2').text()).toBe('任务对话')
    expect(wrapper.find('[data-testid="knowledge-task-artifact"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="selected-draft-inputs"]').text()).toContain('需求约束')
  })

  /** 业务目的：公开决策消息与真实 Tool Invocation 必须在同一轮时间线中出现，详细参数默认折叠。 */
  it('shows public decisions and expandable tool facts without hidden reasoning', () => {
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task } })
    expect(wrapper.text()).toContain('我会先核对两份输入')
    expect(wrapper.text()).toContain('因此我会修改它，而不是新增重复文档')
    expect(wrapper.text()).not.toContain('public_message:decision-1')
    const process = wrapper.get('[data-testid="run-process-group"]')
    expect(process.attributes('open')).toBeDefined()
    const group = wrapper.get('[data-testid="tool-invocation-group"]')
    expect(group.find('[data-testid="tool-invocation"]').text()).toContain('检索相关业务知识')
    const tool = wrapper.get('[data-testid="tool-invocation"]')
    expect(tool.text()).toContain('检索相关业务知识')
    expect(tool.attributes('data-density')).toBe('compact')
    expect(tool.get('.tool-card__meta').text()).toContain('knowledge_search · 已完成')
    expect(tool.attributes('open')).toBeUndefined()
    expect(wrapper.text()).toContain('不展示内部思维')
  })

  /** 业务目的：终态运行默认只露出模型最终回复；执行过程整体可展开，最终回复安全渲染 Markdown。 */
  it('collapses a completed run process and previews the final answer as safe markdown', () => {
    const completed = {
      ...task,
      messages: task.messages.map(message => message.messageId === 4
        ? { ...message, content: '**本轮完成**\n\n- 修订发布约束\n- 新增复盘流程\n\n<script>alert(1)</script>' }
        : message),
      runs: [{ ...task.runs[0], status: 'COMPLETED' as const, finishedAt: '2026-08-02T00:02:00Z' }],
    }

    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task: completed } })
    const process = wrapper.get('[data-testid="run-process-group"]')
    const finalAnswer = wrapper.get('[data-testid="run-final-answer"]')

    expect(process.attributes('open')).toBeUndefined()
    expect(process.find('[data-testid="run-final-answer"]').exists()).toBe(false)
    expect(finalAnswer.get('strong').text()).toBe('调度 Agent')
    expect(finalAnswer.get('.markdown-preview strong').text()).toBe('本轮完成')
    expect(finalAnswer.findAll('.markdown-preview li').map(item => item.text())).toEqual(['修订发布约束', '新增复盘流程'])
    expect(finalAnswer.find('script').exists()).toBe(false)
    expect(finalAnswer.text()).toContain('<script>alert(1)</script>')
  })

  /** 业务目的：每轮多文档 Patch Set 必须保留在时间线，并可从具体文档打开 Diff。 */
  it('emits the selected document from a run patch set', async () => {
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task } })
    expect(wrapper.get('[data-testid="run-patch-set"]').text()).toContain('发布约束')
    expect(wrapper.get('[data-testid="run-patch-set"]').text()).toContain('复盘流程')
    await wrapper.get('[data-testid="run-patch-set"] button').trigger('click')
    expect(wrapper.emitted('review-document')).toEqual([[51]])
  })

  /** 业务目的：运行中禁止注入新消息，只允许停止本轮，并保留已经提交的修订。 */
  it('locks input while active and emits stop', async () => {
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task } })
    expect(wrapper.get('[data-testid="continue-task-guidance"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="stop-task-run"]').trigger('click')
    expect(wrapper.emitted('stop')).toEqual([[61]])
  })

  /** 业务目的：一轮终态后用户指导创建新 run，不恢复同一 run 或 Checkpoint。 */
  it('starts a new run from terminal guidance', async () => {
    const completed = { ...task, runs: [{ ...task.runs[0], status: 'COMPLETED' as const, finishedAt: '2026-08-02T00:02:00Z' }] }
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task: completed } })
    await wrapper.get('[data-testid="continue-task-guidance"]').setValue('保留人工发布并补充冲突说明')
    await wrapper.get('[data-testid="continue-task"]').trigger('click')
    expect(wrapper.emitted('continue-task')).toEqual([['保留人工发布并补充冲突说明']])
  })

  /** 业务目的：Diff 仅按需覆盖打开，并使用最小行级颜色区分增删。 */
  it('renders an on-demand overlay diff drawer', () => {
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task, selectedDraftId: 51, selectedDiff: diff } })
    expect(wrapper.get('[data-testid="diff-drawer"]').text()).toContain('正式知识 v4')
    expect(wrapper.get('.diff-lines .added').text()).toContain('必须由管理员审核')
    expect(wrapper.get('.diff-lines .deleted').text()).toContain('允许自动发布')
  })

  /** 业务目的：发布后的任务只能审计查看，不能再发消息或重复发布。 */
  it('makes a published task read only', () => {
    const published = { ...task, status: 'PUBLISHED' as const, runs: [{ ...task.runs[0], status: 'COMPLETED' as const }] }
    const wrapper = mount(KnowledgeTaskWorkspace, { props: { task: published } })
    expect(wrapper.text()).toContain('任务已结束，只读保留')
    expect(wrapper.find('[data-testid="continue-task-guidance"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="publish-workspace"]').attributes('disabled')).toBeDefined()
  })
})
