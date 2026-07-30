import { ref, type Ref } from 'vue'
import type { QaApi, QaEventStream, QaQuestion, QaSseEvent, QaSseEventName } from '../api/qa'

export type QaConnectionState = 'idle' | 'connecting' | 'open' | 'interrupted'

export interface ProjectQaControllerOptions {
  createIdempotencyKey?: () => string
  reconnectDelayMs?: number
}

export interface ProjectQaController {
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
  selectQuestion(questionId: string): Promise<void>
  submit(question: string): Promise<QaQuestion>
  retry(question?: string): Promise<QaQuestion>
  observe(snapshot: QaQuestion): Promise<void>
  dispose(): void
}

/**
 * 管理单个项目问答页的快照与流式连接；历史正文不会进入新问题请求。
 */
export function createProjectQaController(
  api: QaApi,
  identifier: string,
  selectedBranch: () => string,
  options: ProjectQaControllerOptions = {},
): ProjectQaController {
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
      const page = await api.history(identifier, cursor)
      history.value = cursor ? [...history.value, ...page.items] : page.items
      nextCursor.value = page.nextCursor
    } catch (error) {
      loadError.value = errorMessage(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function selectQuestion(questionId: string): Promise<void> {
    closeStream()
    loading.value = true
    loadError.value = null
    try {
      await observe(await api.detail(identifier, questionId))
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
    const branch = selectedBranch()
    const signature = `${branch}\n${question}`
    if (!pendingIdempotencyKey.value || pendingRequestSignature !== signature) {
      pendingIdempotencyKey.value = makeKey()
      pendingRequestSignature = signature
    }
    submitting.value = true
    submitError.value = null
    lastSubmittedQuestion.value = question
    try {
      const snapshot = await api.createQuestion(identifier, {
        idempotencyKey: pendingIdempotencyKey.value,
        branch,
        question,
      })
      pendingIdempotencyKey.value = null
      pendingRequestSignature = null
      history.value = [snapshot, ...history.value.filter(item => item.questionId !== snapshot.questionId)]
      await observe(snapshot)
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
    partialText.value = snapshot.resultText ?? ''
    phase.value = snapshot.status
    lastSequence = snapshot.lastEventSequence
    if (!isTerminal(snapshot.status)) {
      connect(snapshot.questionId)
    } else {
      connectionState.value = 'idle'
    }
  }

  function connect(questionId: string): void {
    const generation = ++connectionGeneration
    connectionState.value = 'connecting'
    stream = api.openEventStream(identifier, questionId, lastSequence, {
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
    })
    connectionState.value = 'open'
  }

  function applyEvent(name: QaSseEventName, event: QaSseEvent, questionId: string): void {
    if (event.version !== 'v1' || event.sequence <= lastSequence) return
    lastSequence = event.sequence
    phase.value = event.phase
    if (name === 'answer.delta' && event.textDelta) partialText.value += event.textDelta
    if (name === 'answer.refusal' && event.textDelta) partialText.value = event.textDelta
    if (name === 'run.completed' || name === 'run.failed' || name === 'run.terminated') {
      closeStream()
      void refreshTerminal(questionId)
    }
  }

  async function refreshTerminal(questionId: string): Promise<void> {
    try {
      const snapshot = await api.detail(identifier, questionId)
      current.value = snapshot
      partialText.value = snapshot.resultText ?? ''
      phase.value = snapshot.status
      lastSequence = snapshot.lastEventSequence
      history.value = history.value.map(item => item.questionId === questionId ? snapshot : item)
      connectionState.value = 'idle'
    } catch (error) {
      loadError.value = errorMessage(error)
      connectionState.value = 'interrupted'
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

function defaultIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `qa-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}
