import { requestJson } from './http'

export interface KnowledgeTaskRun {
  runId: number
  conversationId: number
  threadId: string
  status: 'ACCEPTED' | 'RUNNING' | 'PAUSE_REQUESTED' | 'WAITING_FOR_USER' | 'COMPLETED' | 'FAILED' | 'TERMINATED' | 'CANCELLED'
  checkpointSavedAt: string | null
  stepCount: number
  modelCallCount: number
  toolCallCount: number
}

export interface KnowledgeTask {
  conversationId: number
  projectIdentifier: string
  goal: string
  currentDraftId: number | null
  currentDraftRevision: number | null
  messages: Array<{ messageId: number; runId: number | null; role: string; subjectName: string | null; content: string; createdAt: string }>
  runs: KnowledgeTaskRun[]
  events: Array<{ sequence: number; type: string; payload: { name?: string; purpose?: string; resultSummary?: string; status?: string } }>
}

export interface DraftRevision {
  draftId: number
  revision: number
  title: string
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
  detail: (identifier: string, conversationId: number) => requestJson<KnowledgeTask>(base(identifier, conversationId)),
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
}
