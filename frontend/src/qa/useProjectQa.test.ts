import { describe, expect, it, vi } from 'vitest'
import type {
  QaApi,
  QaEventStream,
  QaEventStreamHandlers,
  QaQuestion,
} from '../api/qa'
import { createProjectQaController } from './useProjectQa'

function question(overrides: Partial<QaQuestion> = {}): QaQuestion {
  return {
    questionId: 61,
    runId: 71,
    scope: { projectIdentifier: 'network-designer', branch: 'main', commit: 'abc1234', codeSnapshotAvailable: true },
    createdAt: '2026-07-31T08:00:00Z',
    status: 'COMPLETED',
    resultType: 'ANSWER',
    trustState: 'RELIABLE_ANSWER',
    answerBasis: 'BUSINESS_RULE',
    refusalReason: null,
    errorCode: null,
    failureMessage: null,
    resultText: '范围锁定避免跨项目召回。',
    stepCount: 2,
    modelCallCount: 1,
    lastEventSequence: 9,
    messages: [{ id: 81, role: 'USER', content: '为什么需要范围锁定？', resultType: null, refusalReason: null, createdAt: '2026-07-31T08:00:00Z' }],
    citations: [],
    ...overrides,
  }
}

function api(overrides: Partial<QaApi> = {}): QaApi {
  return {
    history: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    detail: vi.fn().mockResolvedValue(question()),
    createQuestion: vi.fn().mockResolvedValue(question()),
    createKnowledgeGap: vi.fn(),
    openEventStream: vi.fn().mockReturnValue({ close: vi.fn() }),
    ...overrides,
  }
}

describe('createProjectQaController', () => {
  /**
   * 业务目的：页面加载与选择历史必须始终从服务端快照恢复，防止刷新后依赖上一次内存中的部分回答。
   */
  it('loads history and then restores the selected detail snapshot', async () => {
    const selected = question({ questionId: 62 })
    const qa = api({
      history: vi.fn().mockResolvedValue({ items: [selected], nextCursor: 'next-page' }),
      detail: vi.fn().mockResolvedValue(selected),
    })
    const controller = createProjectQaController(qa, 'network-designer', () => 'main')

    await controller.loadHistory()
    await controller.selectQuestion(62)

    expect(controller.history.value).toEqual([selected])
    expect(controller.nextCursor.value).toBe('next-page')
    expect(controller.current.value?.questionId).toBe(62)
    expect(qa.detail).toHaveBeenCalledWith('network-designer', 62)
  })

  /**
   * 业务目的：创建响应是否到达不确定时必须复用同一幂等键，防止用户点击重试后重复运行模型。
   */
  it('keeps the creation key across an uncertain failure and clears it after acceptance', async () => {
    const createQuestion = vi.fn()
      .mockRejectedValueOnce(new TypeError('network failed'))
      .mockResolvedValueOnce(question({ status: 'ACCEPTED', trustState: 'IN_PROGRESS', resultType: null, answerBasis: null, resultText: null }))
    const qa = api({ createQuestion })
    const controller = createProjectQaController(qa, 'network-designer', () => 'feature/import', {
      createIdempotencyKey: () => 'stable-key',
    })

    await expect(controller.submit('当前实现在哪里？')).rejects.toThrow('network failed')
    expect(controller.pendingIdempotencyKey.value).toBe('stable-key')
    await controller.submit('当前实现在哪里？')

    expect(createQuestion).toHaveBeenNthCalledWith(1, 'network-designer', expect.objectContaining({ idempotencyKey: 'stable-key', branch: 'feature/import' }))
    expect(createQuestion).toHaveBeenNthCalledWith(2, 'network-designer', expect.objectContaining({ idempotencyKey: 'stable-key', branch: 'feature/import' }))
    expect(controller.pendingIdempotencyKey.value).toBeNull()
  })

  /**
   * 业务目的：SSE 只能按持久序号追加一次，断线重连从最后序号继续，防止重复文本和遗漏终态快照校正。
   */
  it('deduplicates stream events, reconnects from the cursor and refetches terminal detail', async () => {
    const handlers: QaEventStreamHandlers[] = []
    const streams: QaEventStream[] = []
    const terminal = question({ resultText: '服务端终态正文', lastEventSequence: 3 })
    const detail = vi.fn().mockResolvedValue(terminal)
    const qa = api({
      detail,
      openEventStream: vi.fn((_project, _questionId, _after, callbacks) => {
        handlers.push(callbacks)
        const stream = { close: vi.fn() }
        streams.push(stream)
        return stream
      }),
    })
    const controller = createProjectQaController(qa, 'network-designer', () => 'main', { reconnectDelayMs: 0 })

    await controller.observe(question({ status: 'RUNNING', trustState: 'IN_PROGRESS', resultType: null, answerBasis: null, resultText: '', lastEventSequence: 0 }))
    handlers[0].onEvent('answer.delta', { version: 'v1', sequence: 1, occurredAt: '2026-07-31T08:00:01Z', phase: 'COMPOSING', textDelta: '正在回答' })
    handlers[0].onEvent('answer.delta', { version: 'v1', sequence: 1, occurredAt: '2026-07-31T08:00:01Z', phase: 'COMPOSING', textDelta: '重复内容' })
    handlers[0].onError()
    await Promise.resolve()

    expect(controller.partialText.value).toBe('正在回答')
    expect(qa.openEventStream).toHaveBeenLastCalledWith('network-designer', 61, 1, expect.any(Object))
    expect(streams[0].close).toHaveBeenCalledOnce()

    handlers[1].onEvent('run.completed', { version: 'v1', sequence: 3, occurredAt: '2026-07-31T08:00:03Z', phase: 'COMPLETED', resultType: 'ANSWER' })
    await Promise.resolve()
    await Promise.resolve()

    expect(detail).toHaveBeenCalledWith('network-designer', 61)
    expect(controller.current.value?.resultText).toBe('服务端终态正文')
    expect(streams[1].close).toHaveBeenCalledOnce()
  })

  /**
   * 业务目的：运行终止后必须丢弃浏览器内存中的未校验文本并恢复服务端失败说明，防止把部分输出误认为回答。
   */
  it('clears partial text and restores the server failure message after termination', async () => {
    const handlers: QaEventStreamHandlers[] = []
    const terminal = question({ status: 'TERMINATED', resultType: null, trustState: 'FAILED', answerBasis: null, resultText: null, errorCode: 'AGENT_STEP_LIMIT_EXCEEDED', failureMessage: '本次检索已达到运行上限，尚未形成可信回答。', lastEventSequence: 2 })
    const qa = api({
      detail: vi.fn().mockResolvedValue(terminal),
      openEventStream: vi.fn((_project, _questionId, _after, callbacks) => {
        handlers.push(callbacks)
        return { close: vi.fn() }
      }),
    })
    const controller = createProjectQaController(qa, 'network-designer', () => 'main')

    await controller.observe(question({ status: 'RUNNING', resultType: null, trustState: 'IN_PROGRESS', answerBasis: null, resultText: null, lastEventSequence: 0 }))
    handlers[0].onEvent('answer.delta', { version: 'v1', sequence: 1, occurredAt: '2026-07-31T08:00:01Z', phase: 'COMPOSING', textDelta: '未校验内容' })
    handlers[0].onEvent('run.terminated', { version: 'v1', sequence: 2, occurredAt: '2026-07-31T08:00:02Z', phase: 'TERMINATED', errorCode: 'AGENT_STEP_LIMIT_EXCEEDED' })
    await Promise.resolve()
    await Promise.resolve()

    expect(controller.partialText.value).toBe('')
    expect(controller.current.value?.failureMessage).toContain('运行上限')
    console.info(`测试证据：场景=终态快照恢复，状态=${controller.current.value?.status}，部分文本长度=${controller.partialText.value.length}，错误=${controller.current.value?.errorCode}`)
  })

  /**
   * 业务目的：切换问答记录必须关闭旧流，且用户主动重试失败运行时必须生成新键和新运行。
   */
  it('closes the old stream when switching and generates a new key for an explicit retry', async () => {
    const firstStream = { close: vi.fn() }
    const secondStream = { close: vi.fn() }
    const openEventStream = vi.fn()
      .mockReturnValueOnce(firstStream)
      .mockReturnValueOnce(secondStream)
    const createQuestion = vi.fn().mockResolvedValue(question({ questionId: 63, status: 'ACCEPTED', trustState: 'IN_PROGRESS', resultType: null, answerBasis: null, resultText: null }))
    const qa = api({ openEventStream, createQuestion })
    const keys = ['retry-key']
    const controller = createProjectQaController(qa, 'network-designer', () => 'main', {
      createIdempotencyKey: () => keys.shift() ?? 'unexpected-key',
    })

    await controller.observe(question({ status: 'RUNNING', trustState: 'IN_PROGRESS', resultType: null, answerBasis: null, resultText: null }))
    await controller.observe(question({ questionId: 64, runId: 72, status: 'RUNNING', trustState: 'IN_PROGRESS', resultType: null, answerBasis: null, resultText: null }))
    expect(firstStream.close).toHaveBeenCalledOnce()

    controller.pendingIdempotencyKey.value = 'first-key'
    await controller.retry('再次确认当前实现')
    expect(createQuestion).toHaveBeenCalledWith('network-designer', expect.objectContaining({ idempotencyKey: 'retry-key' }))
  })
})
