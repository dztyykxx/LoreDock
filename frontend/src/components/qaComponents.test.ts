import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { QaApi, QaQuestion } from '../api/qa'
import KnowledgeGapDialog from './KnowledgeGapDialog.vue'
import QaAnswerPanel from './QaAnswerPanel.vue'
import QaCitationDrawer from './QaCitationDrawer.vue'
import QaQuestionComposer from './QaQuestionComposer.vue'
import QaRecentSidebar from './QaRecentSidebar.vue'
import QaTrustBadge from './QaTrustBadge.vue'

function question(overrides: Partial<QaQuestion> = {}): QaQuestion {
  return {
    questionId: 61,
    conversationId: 51,
    runId: 71,
    scope: { projectIdentifier: 'network-designer', branch: 'main', commit: null, codeSnapshotAvailable: false },
    createdAt: '2026-07-31T08:00:00Z',
    status: 'COMPLETED',
    resultType: 'ANSWER',
    trustState: 'RELIABLE_ANSWER',
    answerBasis: 'BUSINESS_RULE',
    refusalReason: null,
    errorCode: null,
    failureMessage: null,
    resultText: '范围必须在检索前锁定。',
    stepCount: 3,
    modelCallCount: 1,
    lastEventSequence: 9,
    processEvents: [],
    messages: [{ id: 81, role: 'USER', content: '为什么锁定范围？', resultType: null, refusalReason: null, createdAt: '2026-07-31T08:00:00Z' }],
    citations: [],
    ...overrides,
  }
}

describe('project QA components', () => {
  afterEach(() => vi.restoreAllMocks())

  /**
   * 业务目的：可信、冲突、证据不足与运行中状态必须有可读文字，防止仅靠颜色让用户误判回答可信度。
   */
  it.each([
    ['RELIABLE_ANSWER', '有可靠依据'],
    ['SOURCE_CONFLICT', '来源存在冲突'],
    ['INSUFFICIENT_EVIDENCE', '当前知识库没有足够依据'],
    ['IN_PROGRESS', '正在核验来源'],
    ['FAILED', '本次问答未完成'],
  ] as const)('renders %s trust state as %s', (state, label) => {
    const wrapper = mount(QaTrustBadge, { props: { state } })
    expect(wrapper.text()).toContain(label)
    expect(wrapper.attributes('aria-label')).toContain(label)
  })

  /**
   * 业务目的：来源抽屉只能展示已发布文档，并且只把已校验 HTTP(S) Wiki 地址变成外链。
   */
  it('renders safe source metadata without interpreting untrusted fields', async () => {
    const snapshot = question({
      citations: [
        { order: 1, sourceType: 'KNOWLEDGE', projectIdentifier: 'network-designer', branch: 'main', commit: null, repositoryPath: null, title: '<img onerror=alert(1)>', sourceUpdatedAt: '2026-07-30T08:00:00Z', scopeType: 'PROJECT', knowledgeSourceType: 'WIKI', wikiUrl: 'https://wiki.example/rule', originalFilename: null },
        { order: 2, sourceType: 'KNOWLEDGE', projectIdentifier: 'network-designer', branch: 'main', commit: null, repositoryPath: null, title: '危险链接', sourceUpdatedAt: null, scopeType: 'BRANCH', knowledgeSourceType: 'WIKI', wikiUrl: 'javascript:alert(1)', originalFilename: null },
      ],
    })
    const trigger = document.createElement('button')
    document.body.append(trigger)
    const wrapper = mount(QaCitationDrawer, { props: { snapshot, returnFocusTo: trigger } })

    expect(wrapper.html()).not.toContain('<img onerror')
    expect(wrapper.text()).toContain('<img onerror=alert(1)>')
    expect(wrapper.text()).toContain('仅使用已发布文档')
    expect(wrapper.text()).not.toContain('当前代码快照')
    const links = wrapper.findAll('a')
    expect(links).toHaveLength(1)
    expect(links[0].attributes()).toMatchObject({ href: 'https://wiki.example/rule', target: '_blank', rel: 'noopener noreferrer' })
    expect(wrapper.text()).toContain('javascript:alert(1)')

    await wrapper.get('[data-testid="close-citations"]').trigger('click')
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(document.activeElement).toBe(trigger)
    trigger.remove()
  })

  /**
   * 业务目的：问题输入必须支持键盘直接提交并保留换行手段，标签和错误关联让辅助技术能识别输入约束。
   */
  it('submits with Enter while Shift Enter keeps editing and exposes validation', async () => {
    const wrapper = mount(QaQuestionComposer, { props: { busy: false, error: '问题不能为空' } })
    const textarea = wrapper.get('textarea')
    await textarea.setValue('为什么锁定范围？')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('submit')).toBeUndefined()
    await textarea.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('submit')?.[0]).toEqual(['为什么锁定范围？'])
    expect(textarea.attributes('aria-describedby')).toContain('qa-question-error')
    expect(wrapper.get('label').text()).toContain('向当前项目提问')
  })

  /**
   * 业务目的：文档证据不足、模型故障和流中断必须显示不同恢复动作，任何部分内容都不能被标成可信完成。
   */
  it('renders explicit refusal and failure recovery states', async () => {
    const insufficient = mount(QaAnswerPanel, {
      props: {
        snapshot: question({ trustState: 'INSUFFICIENT_EVIDENCE', resultType: 'REFUSAL', answerBasis: null, resultText: '当前知识库没有足够依据，请核对本地最新代码。', refusalReason: 'INSUFFICIENT_EVIDENCE', scope: { projectIdentifier: 'network-designer', branch: 'feature/import', commit: null, codeSnapshotAvailable: false } }),
        partialText: '',
        connectionState: 'idle',
      },
    })
    expect(insufficient.text()).toContain('当前知识库没有足够依据')
    expect(insufficient.text()).toContain('核对本地最新代码')
    expect(insufficient.text()).not.toContain('代码尚未索引')

    const unavailable = mount(QaAnswerPanel, {
      props: {
        snapshot: question({ status: 'FAILED', trustState: 'FAILED', resultType: null, answerBasis: null, resultText: null, errorCode: 'AGENT_MODEL_UNAVAILABLE', failureMessage: '问答模型暂时无法连接，请稍后使用新运行重试。' }),
        partialText: '未完成的部分回答',
        connectionState: 'interrupted',
      },
    })
    expect(unavailable.text()).toContain('模型暂时不可用')
    expect(unavailable.text()).toContain('问答模型暂时无法连接，请稍后使用新运行重试。')
    expect(unavailable.text()).toContain('AGENT_MODEL_UNAVAILABLE')
    expect(unavailable.text()).toContain('连接已中断')
    expect(unavailable.text()).not.toContain('未完成的部分回答')
    expect(unavailable.text()).not.toContain('有可靠依据')
    expect(unavailable.get('[data-testid="browse-knowledge"]').attributes('href')).toBe('/projects/network-designer')
    await unavailable.get('[data-testid="retry-answer"]').trigger('click')
    expect(unavailable.emitted('retry')).toHaveLength(1)

    const limited = mount(QaAnswerPanel, {
      props: {
        snapshot: question({ status: 'TERMINATED', trustState: 'FAILED', resultType: null, answerBasis: null, resultText: null, errorCode: 'AGENT_STEP_LIMIT_EXCEEDED', failureMessage: '本次检索已达到运行上限，尚未形成可信回答。请缩小问题范围或使用新运行重试。' }),
        partialText: '不应展示的中间文本',
        connectionState: 'idle',
      },
    })
    expect(limited.text()).toContain('本次检索已达到运行上限，尚未形成可信回答')
    expect(limited.text()).toContain('AGENT_STEP_LIMIT_EXCEEDED')
    expect(limited.text()).not.toContain('不应展示的中间文本')
  })

  /**
   * 业务目的：处理过程默认折叠，展开后按真实主体展示 Tool、来源和引用校验；模型摘要必须带公开摘要标签。
   */
  it('renders a keyboard-accessible collapsed process timeline with typed facts', async () => {
    const snapshot = question({
      processEvents: [
        {
          sequence: 2, type: 'TOOL_COMPLETED', subjectType: 'TOOL', occurredAt: '2026-07-31T08:00:01Z',
          payload: { phase: 'RETRIEVING', name: 'knowledge_search', purpose: '搜索已发布知识', parameterSummary: 'queryLength=4', resultSummary: '命中 1 篇文档', count: 1, durationMillis: 18, status: 'COMPLETED', sources: [], summary: null, textDelta: null, resultType: null, errorCode: null, modelGenerated: false, truncated: false },
        },
        {
          sequence: 3, type: 'PUBLIC_DECISION_SUMMARY', subjectType: 'MODEL', occurredAt: '2026-07-31T08:00:02Z',
          payload: { phase: 'REASONING', name: null, purpose: null, parameterSummary: null, resultSummary: null, count: null, durationMillis: null, status: null, sources: [], summary: '继续检索以核实限制', textDelta: null, resultType: null, errorCode: null, modelGenerated: true, truncated: false },
        },
      ],
    })
    const wrapper = mount(QaAnswerPanel, {
      props: { snapshot, partialText: '', connectionState: 'idle' },
    })

    const toggle = wrapper.get('[data-testid="toggle-process"]')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-testid="qa-process-timeline"]').exists()).toBe(false)
    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    const timelineText = wrapper.get('[data-testid="qa-process-timeline"]').text()
    expect(timelineText).toContain('knowledge_search')
    expect(timelineText).toContain('命中 1 篇文档')
    expect(timelineText).toContain('公开决策摘要')
    expect(timelineText).toContain('继续检索以核实限制')
    expect(wrapper.text()).not.toContain('原始思维链')
  })

  /**
   * 业务目的：空历史与最近问题必须来自真实快照，选择记录时只传记录 ID，不能把旧问题内容带入下一次提问。
   */
  it('shows an empty recent list and selects a real question by id', async () => {
    const empty = mount(QaRecentSidebar, { props: { items: [], selectedId: null } })
    expect(empty.text()).toContain('还没有提问记录')

    const wrapper = mount(QaRecentSidebar, { props: { items: [question()], selectedId: null } })
    expect(wrapper.text()).toContain('为什么锁定范围？')
    await wrapper.get('[data-testid="recent-question-61"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([61])
  })

  /**
   * 业务目的：最近会话长标题必须单行截断并通过 title 暴露完整值，键盘点击只提交会话 ID。
   */
  it('shows a truncated conversation title with an accessible full value', async () => {
    const longTitle = '这是一个非常长的会话标题，用于验证侧栏不会被撑开并仍可查看完整值'.repeat(3)
    const item = {
      conversationId: 51, projectIdentifier: 'network-designer', title: longTitle,
      lastQuestion: '最后一轮问题', status: 'COMPLETED', createdAt: '2026-07-31T08:00:00Z',
      updatedAt: '2026-07-31T08:00:00Z', lastQuestionAt: '2026-07-31T08:00:00Z',
    }
    const wrapper = mount(QaRecentSidebar, { props: { items: [item] as never[], selectedId: 51 } })

    const button = wrapper.get('[data-testid="recent-conversation-51"]')
    expect(button.get('span').attributes('title')).toBe(longTitle)
    expect(button.classes()).toContain('qa-recent__item--active')
    await button.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([51])
  })

  /**
   * 业务目的：反馈默认类型应随可信状态推荐；网络结果不确定时保留用户说明并复用同一键，成功后明确确认且阻止重复提交。
   */
  it('reuses the feedback key after a network failure and confirms success', async () => {
    const createKnowledgeGap = vi.fn()
      .mockRejectedValueOnce(new TypeError('network failed'))
      .mockResolvedValueOnce({ feedbackId: 91, status: 'OPEN' })
    const api = { createKnowledgeGap } as unknown as QaApi
    const wrapper = mount(KnowledgeGapDialog, {
      props: {
        api,
        snapshot: question({ trustState: 'INSUFFICIENT_EVIDENCE', resultType: 'REFUSAL', answerBasis: null, refusalReason: 'INSUFFICIENT_EVIDENCE' }),
        createIdempotencyKey: () => 'gap-key-1',
      },
    })

    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('NO_ANSWER')
    await wrapper.get('textarea').setValue('请补充范围隔离的决策背景。')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('提交结果未知')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('请补充范围隔离的决策背景。')
    await wrapper.get('form').trigger('submit')

    expect(createKnowledgeGap).toHaveBeenCalledTimes(2)
    expect(createKnowledgeGap.mock.calls[0][1].idempotencyKey).toBe('gap-key-1')
    expect(createKnowledgeGap.mock.calls[1][1].idempotencyKey).toBe('gap-key-1')
    expect(wrapper.text()).toContain('知识缺口已记录')
    expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
  })
})
