import { ref, type Ref } from 'vue'
import type {
  QaApi,
  QaConversationSummary,
  QaEventStream,
  QaProcessEvent,
  QaQuestion,
  QaSseEvent,
  QaSseEventName,
} from '../api/qa'

export type QaConnectionState = 'idle' | 'connecting' | 'open' | 'interrupted'

export interface ProjectQaControllerOptions {
  createIdempotencyKey?: () => string
  reconnectDelayMs?: number
}

export interface ProjectQaController {
  conversations: Ref<QaConversationSummary[]>
  currentConversation: Ref<QaConversationSummary | null>
  rounds: Ref<QaQuestion[]>
  processEvents: Ref<QaProcessEvent[]>
  history: Ref<QaQuestion[]>
  nextCursor: Ref<string | null>
  current: Ref<QaQuestion | null>
  loading: Ref<boolean>
  submitting: Ref<boolean>
  loadError: Ref<string | null>
  submitError: Ref<string | null>
  connectionState: Ref<QaConnectionState>
  phase: Ref<string | null>
  partialText: Ref<string>
  pendingIdempotencyKey: Ref<string | null>
  lastSubmittedQuestion: Ref<string>
  loadHistory(cursor?: string): Promise<void>
  loadConversations(cursor?: string): Promise<void>
  selectConversation(conversationId: number): Promise<void>
  selectQuestion(questionId: number): Promise<void>
  submit(question: string): Promise<QaQuestion>
  retry(question?: string): Promise<QaQuestion>
  observe(snapshot: QaQuestion): Promise<void>
  dispose(): void
}

/**
 * 管理问答页的快照与流式连接；历史正文不会进入新问题请求。
 * identifier 为空时进入全局（全库）模式：创建、详情与事件流走 /api/qa 全局端点。
 */
export function createProjectQaController(
  api: QaApi,
  identifier: string | null,
  options: ProjectQaControllerOptions = {},
): ProjectQaController {
  const conversations = ref<QaConversationSummary[]>([])
  const currentConversation = ref<QaConversationSummary | null>(null)
  const rounds = ref<QaQuestion[]>([])
  const processEvents = ref<QaProcessEvent[]>([])
  const history = ref<QaQuestion[]>([])
  const nextCursor = ref<string | null>(null)
  const current = ref<QaQuestion | null>(null)
  const loading = ref(false)
  const submitting = ref(false)
  const loadError = ref<string | null>(null)
  const submitError = ref<string | null>(null)
  const connectionState = ref<QaConnectionState>('idle')
  const phase = ref<string | null>(null)
  const partialText = ref('')
  const pendingIdempotencyKey = ref<string | null>(null)
  const lastSubmittedQuestion = ref('')
  const makeKey = options.createIdempotencyKey ?? defaultIdempotencyKey
  const reconnectDelayMs = options.reconnectDelayMs ?? 500
  let pendingRequestSignature: string | null = null
  let stream: QaEventStream | null = null
  let connectionGeneration = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let lastSequence = 0

  async function loadHistory(cursor?: string): Promise<void> {
    loading.value = true
    loadError.value = null
    try {
      const page = identifier ? await api.history(identifier, cursor) : await api.historyGlobal(cursor)
      history.value = cursor ? [...history.value, ...page.items] : page.items
      nextCursor.value = page.nextCursor
    } catch (error) {
      loadError.value = errorMessage(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function loadConversations(cursor?: string): Promise<void> {
    loading.value = true
    loadError.value = null
    try {
      const page = identifier
        ? await api.conversations(identifier, cursor)
        : await api.conversationsGlobal(cursor)
      conversations.value = cursor ? [...conversations.value, ...page.items] : page.items
      nextCursor.value = page.nextCursor
    } catch (error) {
      loadError.value = errorMessage(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function selectConversation(conversationId: number): Promise<void> {
    closeStream()
    loading.value = true
    loadError.value = null
    try {
      const detail = identifier
        ? await api.conversation(identifier, conversationId)
        : await api.conversationGlobal(conversationId)
      currentConversation.value = detail.conversation
      rounds.value = [...detail.rounds].sort((left, right) => (
        left.createdAt.localeCompare(right.createdAt) || left.questionId - right.questionId
      ))
      const latest = rounds.value.at(-1) ?? null
      if (latest) await observe(latest)
      else {
        current.value = null
        processEvents.value = []
      }
    } catch (error) {
      currentConversation.value = null
      rounds.value = []
      current.value = null
      processEvents.value = []
      loadError.value = errorMessage(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function selectQuestion(questionId: number): Promise<void> {
    closeStream()
    loading.value = true
    loadError.value = null
    try {
      const snapshot = identifier
        ? await api.detail(identifier, questionId)
        : await api.detailGlobal(questionId)
      await observe(snapshot)
    } catch (error) {
      loadError.value = errorMessage(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function submit(rawQuestion: string): Promise<QaQuestion> {
    const question = rawQuestion.trim()
    if (!question || [...question].length > 2000) {
      throw new Error('问题需为 1～2000 个字符。')
    }
    const conversationId = currentConversation.value?.conversationId
    const signature = `${conversationId ?? 'new'}\n${question}`
    if (!pendingIdempotencyKey.value || pendingRequestSignature !== signature) {
      pendingIdempotencyKey.value = makeKey()
      pendingRequestSignature = signature
    }
    submitting.value = true
    submitError.value = null
    lastSubmittedQuestion.value = question
    try {
      const input = {
        idempotencyKey: pendingIdempotencyKey.value,
        ...(conversationId ? { conversationId } : {}),
        question,
      }
      const snapshot = identifier
        ? await api.createQuestion(identifier, input)
        : await api.createQuestionGlobal(input)
      pendingIdempotencyKey.value = null
      pendingRequestSignature = null
      history.value = [snapshot, ...history.value.filter(item => item.questionId !== snapshot.questionId)]
      if (conversationId) {
        rounds.value = [...rounds.value.filter(item => item.questionId !== snapshot.questionId), snapshot]
      } else {
        rounds.value = [snapshot]
      }
      rememberConversation(snapshot, question)
      await observe(snapshot)
      await refreshConversations(snapshot)
      return snapshot
    } catch (error) {
      submitError.value = errorMessage(error)
      throw error
    } finally {
      submitting.value = false
    }
  }

  async function retry(question = lastSubmittedQuestion.value): Promise<QaQuestion> {
    // 主动重试代表用户要求创建新运行，与“创建响应未知”的同请求重试不同。
    pendingIdempotencyKey.value = null
    pendingRequestSignature = null
    return submit(question)
  }

  async function observe(snapshot: QaQuestion): Promise<void> {
    closeStream()
    current.value = snapshot
    processEvents.value = [...snapshot.processEvents].sort((left, right) => left.sequence - right.sequence)
    partialText.value = snapshot.resultText ?? restoredPartialText(processEvents.value)
    phase.value = snapshot.status
    lastSequence = snapshot.lastEventSequence
    if (!isTerminal(snapshot.status)) {
      connect(snapshot.questionId)
    } else {
      connectionState.value = 'idle'
    }
  }

  function connect(questionId: number): void {
    const generation = ++connectionGeneration
    connectionState.value = 'connecting'
    stream = identifier
      ? api.openEventStream(identifier, questionId, lastSequence, streamHandlers(generation, questionId))
      : api.openEventStreamGlobal(questionId, lastSequence, streamHandlers(generation, questionId))
    connectionState.value = 'open'
  }

  function streamHandlers(generation: number, questionId: number): Parameters<QaApi['openEventStream']>[3] {
    return {
      onEvent(name, event) {
        if (generation !== connectionGeneration || current.value?.questionId !== questionId) return
        applyEvent(name, event, questionId)
      },
      onError() {
        if (generation !== connectionGeneration || current.value?.questionId !== questionId) return
        stream?.close()
        stream = null
        connectionState.value = 'interrupted'
        if (reconnectDelayMs === 0) {
          queueMicrotask(() => {
            if (generation === connectionGeneration && current.value?.questionId === questionId) {
              connect(questionId)
            }
          })
        } else {
          reconnectTimer = setTimeout(() => {
            reconnectTimer = null
            if (generation === connectionGeneration && current.value?.questionId === questionId) {
              connect(questionId)
            }
          }, reconnectDelayMs)
        }
      },
    }
  }

  function applyEvent(name: QaSseEventName, event: QaSseEvent, questionId: number): void {
    if (event.version !== 'v1' || event.sequence <= lastSequence) return
    lastSequence = event.sequence
    phase.value = event.phase
    const typed = toProcessEvent(name, event)
    if (typed) processEvents.value = [...processEvents.value, typed]
    if (name === 'answer.delta' && event.textDelta) partialText.value += event.textDelta
    if (name === 'answer.refusal' && event.textDelta) partialText.value = event.textDelta
    if (name === 'run.completed' || name === 'run.failed' || name === 'run.terminated') {
      closeStream()
      void refreshTerminal(questionId)
    }
  }

  async function refreshTerminal(questionId: number): Promise<void> {
    try {
      const snapshot = identifier
        ? await api.detail(identifier, questionId)
        : await api.detailGlobal(questionId)
      current.value = snapshot
      partialText.value = snapshot.resultText ?? ''
      phase.value = snapshot.status
      lastSequence = snapshot.lastEventSequence
      processEvents.value = [...snapshot.processEvents].sort((left, right) => left.sequence - right.sequence)
      history.value = history.value.map(item => item.questionId === questionId ? snapshot : item)
      rounds.value = rounds.value.map(item => item.questionId === questionId ? snapshot : item)
      rememberConversation(snapshot)
      await refreshConversations(snapshot)
      connectionState.value = 'idle'
    } catch (error) {
      loadError.value = errorMessage(error)
      connectionState.value = 'interrupted'
    }
  }

  async function refreshConversations(snapshot: QaQuestion): Promise<void> {
    try {
      const page = identifier
        ? await api.conversations(identifier, undefined)
        : await api.conversationsGlobal(undefined)
      conversations.value = page.items
      nextCursor.value = page.nextCursor
      const selected = page.items.find(item => item.conversationId === snapshot.conversationId) ?? null
      currentConversation.value = selected
      if (selected && rounds.value.every(item => item.conversationId !== selected.conversationId)) {
        rounds.value = [snapshot]
      }
    } catch {
      // 当前轮次已经受理或终结；侧栏刷新失败不覆盖问答主区的可观察事实。
    }
  }

  function rememberConversation(snapshot: QaQuestion, submittedQuestion?: string): void {
    const existing = currentConversation.value?.conversationId === snapshot.conversationId
      ? currentConversation.value
      : null
    const question = submittedQuestion
      ?? snapshot.messages.find(message => message.role === 'USER')?.content
      ?? existing?.lastQuestion
      ?? '未命名问题'
    const global = snapshot.scope.projectIdentifier === 'GLOBAL'
    currentConversation.value = {
      conversationId: snapshot.conversationId,
      projectIdentifier: snapshot.scope.projectIdentifier,
      projectName: existing?.projectName ?? null,
      scope: global ? 'GLOBAL' : 'PROJECT',
      title: existing?.title ?? [...question].slice(0, 200).join(''),
      lastQuestion: question,
      status: snapshot.status,
      createdAt: existing?.createdAt ?? snapshot.createdAt,
      updatedAt: snapshot.createdAt,
      lastQuestionAt: snapshot.createdAt,
    }
  }

  function closeStream(): void {
    connectionGeneration += 1
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    stream?.close()
    stream = null
  }

  function dispose(): void {
    closeStream()
    connectionState.value = 'idle'
  }

  return {
    conversations,
    currentConversation,
    rounds,
    processEvents,
    history,
    nextCursor,
    current,
    loading,
    submitting,
    loadError,
    submitError,
    connectionState,
    phase,
    partialText,
    pendingIdempotencyKey,
    lastSubmittedQuestion,
    loadHistory,
    loadConversations,
    selectConversation,
    selectQuestion,
    submit,
    retry,
    observe,
    dispose,
  }
}

function isTerminal(status: QaQuestion['status']): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'TERMINATED'
}

function restoredPartialText(events: QaProcessEvent[]): string {
  return events
    .filter(event => event.type === 'ANSWER_DELTA' && event.payload.textDelta)
    .map(event => event.payload.textDelta)
    .join('')
}

function toProcessEvent(name: QaSseEventName, event: QaSseEvent): QaProcessEvent | null {
  const fallbackType: Partial<Record<QaSseEventName, QaProcessEvent['type']>> = {
    'run.accepted': 'RUN_ACCEPTED',
    'run.started': 'RUN_STARTED',
    'agent.stage': 'AGENT_STAGE',
    'model.started': 'MODEL_STAGE',
    'tool.started': 'TOOL_STARTED',
    'tool.completed': 'TOOL_COMPLETED',
    'source.found': 'SOURCE_DISCOVERED',
    'citation.validation': 'CITATION_VALIDATION',
    'decision.summary': 'PUBLIC_DECISION_SUMMARY',
    'answer.delta': 'ANSWER_DELTA',
    'run.completed': 'RUN_COMPLETED',
    'run.failed': 'RUN_FAILED',
    'run.terminated': 'RUN_TERMINATED',
  }
  const type = event.eventType ?? fallbackType[name]
  if (!type) return null
  return {
    sequence: event.sequence,
    type,
    subjectType: event.subjectType ?? subjectFor(type),
    occurredAt: event.occurredAt,
    payload: {
      phase: event.phase ?? null,
      name: event.tool ?? null,
      purpose: event.purpose ?? null,
      parameterSummary: event.parameterSummary ?? null,
      resultSummary: event.resultSummary ?? null,
      count: event.count ?? null,
      durationMillis: event.durationMillis ?? null,
      status: event.status ?? null,
      sources: event.sources ?? [],
      summary: event.summary ?? null,
      textDelta: event.textDelta ?? null,
      resultType: event.resultType ?? null,
      errorCode: event.errorCode ?? null,
      modelGenerated: event.modelGenerated ?? false,
      truncated: event.truncated ?? false,
    },
  }
}

function subjectFor(type: QaProcessEvent['type']): QaProcessEvent['subjectType'] {
  if (type === 'MODEL_STARTED' || type === 'MODEL_STAGE' || type === 'PUBLIC_DECISION_SUMMARY') return 'MODEL'
  if (type === 'TOOL_STARTED' || type === 'TOOL_COMPLETED' || type === 'SOURCE_FOUND' || type === 'SOURCE_DISCOVERED') return 'TOOL'
  if (type === 'CITATION_VALIDATION') return 'VALIDATOR'
  return 'AGENT'
}

function defaultIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `qa-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}
