import { requestJson, resolveApiUrl } from './http'

export type QaRunStatus = 'ACCEPTED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TERMINATED'
export type QaResultType = 'ANSWER' | 'REFUSAL'
export type QaTrustState = 'IN_PROGRESS' | 'RELIABLE_ANSWER' | 'SOURCE_CONFLICT' | 'INSUFFICIENT_EVIDENCE' | 'FAILED'
export type QaAnswerBasis = 'BUSINESS_RULE' | 'CURRENT_IMPLEMENTATION' | 'MIXED'
export type QaMessageRole = 'USER' | 'ASSISTANT'
export type QaSourceType = 'KNOWLEDGE' | 'CODE'
export type KnowledgeGapType = 'NO_ANSWER' | 'WRONG_ANSWER' | 'OUTDATED_KNOWLEDGE'
export type KnowledgeGapStatus = 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED'

export type QaRefusalReason =
  | 'INSUFFICIENT_EVIDENCE'
  | 'CODE_SNAPSHOT_NOT_INDEXED'
  | 'OUT_OF_SCOPE'
  | 'SOURCE_CONFLICT'
  | 'AGENT_CITATION_INVALID'
  | 'OUTPUT_POLICY_VIOLATION'

export type QaErrorCode =
  | 'AGENT_RUN_IDEMPOTENCY_CONFLICT'
  | 'AGENT_SKILL_UNAVAILABLE'
  | 'AGENT_DISABLED'
  | 'AGENT_RUNTIME_UNAVAILABLE'
  | 'AGENT_RUNTIME_BUSY'
  | 'AGENT_MODEL_UNAVAILABLE'
  | 'AGENT_MODEL_RESPONSE_INVALID'
  | 'AGENT_TOOL_NOT_ALLOWED'
  | 'AGENT_TOOL_SCOPE_VIOLATION'
  | 'AGENT_EVIDENCE_VERSION_CHANGED'
  | 'AGENT_STEP_LIMIT_EXCEEDED'
  | 'AGENT_MODEL_CALL_LIMIT_EXCEEDED'
  | 'AGENT_RUN_TIMEOUT'
  | 'AGENT_RUN_INTERRUPTED'
  | 'AGENT_CITATION_INVALID'
  | 'AGENT_INTERNAL_ERROR'

export interface QaScope {
  projectIdentifier: string
  branch: string
  commit: string | null
  codeSnapshotAvailable: boolean
}

export interface QaMessage {
  id: string
  role: QaMessageRole
  content: string
  resultType: QaResultType | null
  refusalReason: QaRefusalReason | null
  createdAt: string
}

export interface QaCitation {
  order: number
  sourceType: QaSourceType
  projectIdentifier: string | null
  branch: string | null
  commit: string | null
  repositoryPath: string | null
  title: string | null
  sourceUpdatedAt: string | null
  scopeType: string | null
  knowledgeSourceType: string | null
  wikiUrl: string | null
  originalFilename: string | null
}

export interface QaQuestion {
  questionId: string
  runId: string
  scope: QaScope
  createdAt: string
  status: QaRunStatus
  resultType: QaResultType | null
  trustState: QaTrustState
  answerBasis: QaAnswerBasis | null
  refusalReason: QaRefusalReason | null
  errorCode: QaErrorCode | null
  resultText: string | null
  stepCount: number
  modelCallCount: number
  lastEventSequence: number
  messages: QaMessage[]
  citations: QaCitation[]
}

export interface QaQuestionPage {
  items: QaQuestion[]
  nextCursor: string | null
}

export interface CreateQaQuestionInput {
  idempotencyKey: string
  branch?: string
  question: string
}

export interface QaSseEvent {
  version: 'v1'
  sequence: number
  occurredAt: string
  phase: string
  tool?: string | null
  count?: number | null
  textDelta?: string | null
  resultType?: QaResultType | null
  errorCode?: QaErrorCode | null
}

export type QaSseEventName =
  | 'run.accepted'
  | 'run.started'
  | 'skill.pinned'
  | 'model.started'
  | 'tool.started'
  | 'tool.completed'
  | 'source.found'
  | 'answer.delta'
  | 'answer.refusal'
  | 'run.completed'
  | 'run.failed'
  | 'run.terminated'

export interface QaEventStreamHandlers {
  onEvent(name: QaSseEventName, event: QaSseEvent): void
  onError(): void
}

export interface QaEventStream {
  close(): void
}

export interface EventSourceLike {
  addEventListener(type: string, listener: EventListener): void
  close(): void
}

export type EventSourceFactory = (url: string, init: EventSourceInit) => EventSourceLike

export interface CreateKnowledgeGapInput {
  idempotencyKey: string
  branch: string
  type: KnowledgeGapType
  questionId: string
  note?: string
}

export interface KnowledgeGapFeedback {
  feedbackId: string
  projectIdentifier: string
  branch: string
  type: KnowledgeGapType
  status: KnowledgeGapStatus
  question: string
  note: string | null
  questionId: string | null
  runId: string | null
  resultType: QaResultType | null
  refusalReason: QaRefusalReason | null
  errorCode: QaErrorCode | null
  citationEvidenceIds: string[]
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

export interface QaApi {
  history(identifier: string, cursor?: string, limit?: number): Promise<QaQuestionPage>
  detail(identifier: string, questionId: string): Promise<QaQuestion>
  createQuestion(identifier: string, input: CreateQaQuestionInput): Promise<QaQuestion>
  createKnowledgeGap(identifier: string, input: CreateKnowledgeGapInput): Promise<KnowledgeGapFeedback>
  openEventStream(
    identifier: string,
    questionId: string,
    afterSequence: number,
    handlers: QaEventStreamHandlers,
    factory?: EventSourceFactory,
  ): QaEventStream
}

const eventNames: QaSseEventName[] = [
  'run.accepted',
  'run.started',
  'skill.pinned',
  'model.started',
  'tool.started',
  'tool.completed',
  'source.found',
  'answer.delta',
  'answer.refusal',
  'run.completed',
  'run.failed',
  'run.terminated',
]

const browserEventSourceFactory: EventSourceFactory = (url, init) => new EventSource(url, init)

export const qaApi: QaApi = {
  history(identifier, cursor, limit = 20) {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor) query.set('cursor', cursor)
    return requestJson<QaQuestionPage>(`${questionsPath(identifier)}?${query}`)
  },
  detail: (identifier, questionId) => requestJson<QaQuestion>(
    `${questionsPath(identifier)}/${encodeURIComponent(questionId)}`,
  ),
  createQuestion: (identifier, input) => requestJson<QaQuestion>(questionsPath(identifier), {
    method: 'POST',
    body: JSON.stringify(input),
  }),
  createKnowledgeGap: (identifier, input) => requestJson<KnowledgeGapFeedback>(
    `/api/projects/${encodeURIComponent(identifier)}/knowledge-gaps`,
    { method: 'POST', body: JSON.stringify(input) },
  ),
  openEventStream(identifier, questionId, afterSequence, handlers, factory = browserEventSourceFactory) {
    const path = `${questionsPath(identifier)}/${encodeURIComponent(questionId)}/events?afterSequence=${afterSequence}`
    const source = factory(resolveApiUrl(path), { withCredentials: true })
    for (const name of eventNames) {
      source.addEventListener(name, ((raw: MessageEvent<string>) => {
        try {
          handlers.onEvent(name, JSON.parse(raw.data) as QaSseEvent)
        } catch {
          // 无法解析的流事件不能进入可信状态；交给统一断线恢复重新读取服务端快照。
          handlers.onError()
        }
      }) as EventListener)
    }
    source.addEventListener('error', handlers.onError)
    return { close: () => source.close() }
  },
}

function questionsPath(identifier: string): string {
  return `/api/projects/${encodeURIComponent(identifier)}/qa/questions`
}
