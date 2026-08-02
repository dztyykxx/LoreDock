import { requestJson } from './http'

export type KnowledgeTaskRunStatus = 'ACCEPTED' | 'RUNNING' | 'PAUSE_REQUESTED' | 'WAITING_FOR_USER' | 'COMPLETED' | 'FAILED' | 'TERMINATED' | 'CANCELLED'
export type KnowledgeTaskStatus = 'PROCESSING' | 'PUBLISHED' | 'CLOSED_NO_CHANGE' | 'ABANDONED'

export interface KnowledgeTaskRun {
  runId: number
  conversationId: number
  threadId: string
  status: KnowledgeTaskRunStatus
  checkpointSavedAt: string | null
  stepCount: number
  modelCallCount: number
  toolCallCount: number
  errorCode: string | null
  acceptedAt: string
  startedAt: string | null
  finishedAt: string | null
  definition: {
    skillName: string
    skillDigest: string
    agentSpecDigest: string
    modelName: string
    toolNames: string[]
  }
}

export interface KnowledgeTask {
  conversationId: number
  projectIdentifier: string
  triggerType: 'MANUAL' | 'SYSTEM'
  targetSkill: string
  goal: string
  status: KnowledgeTaskStatus
  selectedDrafts: Array<{ documentId: number; title: string; directory: string; originalFilename: string | null; curationStatus: 'PENDING' | 'PROCESSING' | 'CURATED' }>
  currentDraftId: number | null
  currentDraftRevision: number | null
  messages: Array<{ messageId: number; runId: number | null; role: string; subjectName: string | null; content: string; createdAt: string }>
  runs: KnowledgeTaskRun[]
  events: Array<{ sequence: number; type: string; payload: { name?: string; purpose?: string; resultSummary?: string; status?: string } }>
  workspaceDocuments: WorkspaceDocument[]
  toolInvocations: ToolInvocation[]
  patchSets: RunPatchSet[]
  lastEventSequence: number
}

export interface WorkspaceDocument {
  draftId: number
  operation: 'ADD' | 'MODIFY'
  baselineDocumentId: number | null
  baselineRevision: number | null
  title: string
  directory: string
  currentRevision: number
  lastChangedRunId: number | null
}

export interface ToolInvocation {
  invocationId: number
  runId: number
  toolCallId: string
  sequence: number
  toolName: string
  purpose: string
  arguments: string
  result: string | null
  resultSummary: string | null
  error: string | null
  status: 'STARTED' | 'COMPLETED' | 'FAILED'
  argumentsTruncated: boolean
  resultTruncated: boolean
  startedAt: string
  finishedAt: string | null
  durationMillis: number | null
}

export interface RunPatchSet {
  runId: number
  documents: Array<{ draftId: number; operation: 'ADD' | 'MODIFY'; title: string; fromRevision: number; toRevision: number; additions: number; deletions: number }>
  additions: number
  deletions: number
}

export interface KnowledgeTaskSummary {
  conversationId: number
  projectIdentifier: string
  triggerType: 'MANUAL' | 'SYSTEM'
  goal: string
  status: KnowledgeTaskStatus
  selectedDraftCount: number
  currentDraftId: number | null
  workspaceDocumentCount: number
  runCount: number
  latestRunId: number | null
  latestRunStatus: KnowledgeTaskRunStatus | null
  latestErrorCode: string | null
  createdAt: string
  updatedAt: string
}

export interface DraftRevision {
  draftId: number
  revision: number
  title: string
  operation: 'ADD' | 'MODIFY'
  baselineDocumentId: number | null
  baselineRevision: number | null
  directory: string
  markdown: string
  changeSummary: string
  createdAt: string
}

export interface DraftDiff {
  draftId: number
  fromRevision: number | null
  toRevision: number
  unifiedDiff: string
  additions: number
  deletions: number
  truncated: boolean
}

function base(identifier: string, conversationId: number): string {
  return `/api/admin/projects/${encodeURIComponent(identifier)}/knowledge-tasks/${conversationId}`
}

export const knowledgeTaskApi = {
  list: (identifier: string) => requestJson<KnowledgeTaskSummary[]>(
    `/api/admin/projects/${encodeURIComponent(identifier)}/knowledge-tasks`),
  start: (identifier: string, selectedDraftIds: number[], goal: string) => requestJson<KnowledgeTask>(
    `/api/admin/projects/${encodeURIComponent(identifier)}/knowledge-tasks`, {
      method: 'POST',
      body: JSON.stringify({
        idempotencyKey: crypto.randomUUID(),
        selectedDraftIds,
        triggerReason: '管理员在待处理草稿列表勾选并启动合并整理',
        goal,
      }),
    }),
  detail: (identifier: string, conversationId: number) => requestJson<KnowledgeTask>(base(identifier, conversationId)),
  stop: (identifier: string, conversationId: number, runId: number) =>
    requestJson<KnowledgeTaskRun>(`${base(identifier, conversationId)}/runs/${runId}/stop`, { method: 'POST' }),
  pause: (identifier: string, conversationId: number, runId: number) =>
    requestJson<KnowledgeTaskRun>(`${base(identifier, conversationId)}/runs/${runId}/pause`, { method: 'POST' }),
  resume: (identifier: string, conversationId: number, runId: number, guidance: string) =>
    requestJson<KnowledgeTaskRun>(`${base(identifier, conversationId)}/runs/${runId}/resume`, {
      method: 'POST', body: JSON.stringify({ guidance }),
    }),
  continueTask: (identifier: string, conversationId: number, guidance: string) =>
    requestJson<KnowledgeTaskRun>(`${base(identifier, conversationId)}/continue`, {
      method: 'POST', body: JSON.stringify({ idempotencyKey: crypto.randomUUID(), guidance }),
    }),
  revision: (identifier: string, conversationId: number, draftId: number, revision: number) =>
    requestJson<DraftRevision>(`${base(identifier, conversationId)}/drafts/${draftId}/revisions/${revision}`),
  revisions: (identifier: string, conversationId: number, draftId: number) =>
    requestJson<DraftRevision[]>(`${base(identifier, conversationId)}/drafts/${draftId}/revisions`),
  diff: (identifier: string, conversationId: number, draftId: number, fromRevision: number | null, toRevision: number) =>
    requestJson<DraftDiff>(`${base(identifier, conversationId)}/drafts/${draftId}/diff`, {
      method: 'POST', body: JSON.stringify({ fromRevision, toRevision }),
    }),
  publish: (identifier: string, conversationId: number, draftId: number, reviewedRevision: number) =>
    requestJson(`${base(identifier, conversationId)}/drafts/${draftId}/publish`, {
      method: 'POST', body: JSON.stringify({ reviewedRevision }),
    }),
  publishWorkspace: (identifier: string, conversationId: number, reviewedDrafts: Array<{ draftId: number; reviewedRevision: number }>) =>
    requestJson(`${base(identifier, conversationId)}/publish`, {
      method: 'POST', body: JSON.stringify({ idempotencyKey: crypto.randomUUID(), reviewedDrafts }),
    }),
  closeNoChange: (identifier: string, conversationId: number, reason: string) =>
    requestJson<KnowledgeTask>(`${base(identifier, conversationId)}/close-no-change`, {
      method: 'POST', body: JSON.stringify({ reason }),
    }),
  abandon: (identifier: string, conversationId: number, reason: string) =>
    requestJson<KnowledgeTask>(`${base(identifier, conversationId)}/abandon`, {
      method: 'POST', body: JSON.stringify({ reason }),
    }),
  eventUrl: (identifier: string, conversationId: number, after: number) =>
    `${base(identifier, conversationId)}/events?after=${after}`,
}
