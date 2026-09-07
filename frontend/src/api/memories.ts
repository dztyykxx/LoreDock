import { requestJson } from './http'

export type MemoryScope = 'GLOBAL' | 'PROJECT'
export type MemoryCategory = 'FORMAT' | 'TEMPLATE' | 'CONTENT' | 'STYLE' | 'PROCESS' | 'OTHER'
export type MemoryStatus = 'ACTIVE' | 'DISABLED'
export type MemorySourceType = 'KNOWLEDGE_CURATION' | 'MANUAL'

export interface MemoryView {
  id: number
  revision?: number
  scope: MemoryScope
  projectId: number | null
  projectIdentifier: string | null
  category: MemoryCategory
  title: string
  summary: string
  content: string
  status: MemoryStatus
  sourceType: MemorySourceType
  sourceRunId: number | null
  sourceConversationId: number | null
  useCount: number
  lastUsedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface MemoryRevisionView {
  id: number | null
  memoryId: number | null
  revision: number
  snapshot: string
  operation: string
  relation: string | null
  reason: string | null
  sourceRunId: number | null
  sourceConversationId: number | null
  sourceMessageId: number | null
  operatorId: string | null
  createdAt: string
}

export interface MemoryRevisionPageResponse {
  total: number
  page: number
  size: number
  items: MemoryRevisionView[]
}

export interface MemoryPageResponse {
  total: number
  page: number
  size: number
  items: MemoryView[]
}

export interface MemoryListQuery {
  scope?: MemoryScope
  category?: MemoryCategory
  status?: MemoryStatus
  keyword?: string
  page?: number
  size?: number
}

export interface MemoryCreateInput {
  scope: MemoryScope
  projectId: number | null
  category: MemoryCategory
  title: string
  summary: string
  content: string
}

export interface MemoryUpdateInput {
  category: MemoryCategory
  title: string
  summary: string
  content: string
  status?: MemoryStatus
  expectedRevision?: number
}

export interface MemoryApi {
  list(query?: MemoryListQuery): Promise<MemoryPageResponse>
  create(input: MemoryCreateInput): Promise<MemoryView>
  update(memoryId: number, input: MemoryUpdateInput): Promise<MemoryView>
  changeStatus(memoryId: number, status: MemoryStatus, expectedRevision?: number): Promise<MemoryView>
  delete(memoryId: number, expectedRevision?: number): Promise<void>
  revisions(memoryId: number, page?: number, size?: number): Promise<MemoryRevisionPageResponse>
}

function queryString(query: MemoryListQuery = {}): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  const encoded = params.toString()
  return encoded ? `?${encoded}` : ''
}

export const memoryApi: MemoryApi = {
  list: query => requestJson<MemoryPageResponse>(`/api/memories${queryString(query)}`),
  create: input => requestJson<MemoryView>('/api/admin/memories', {
    method: 'POST',
    body: JSON.stringify(input),
  }),
  update: (memoryId, input) => requestJson<MemoryView>(`/api/admin/memories/${memoryId}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  }),
  changeStatus: (memoryId, status, expectedRevision) => requestJson<MemoryView>(`/api/admin/memories/${memoryId}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status, expectedRevision }),
  }),
  delete: (memoryId, expectedRevision) => requestJson<void>(
    `/api/admin/memories/${memoryId}${expectedRevision ? `?expectedRevision=${expectedRevision}` : ''}`, { method: 'DELETE' }),
  revisions: (memoryId, page = 1, size = 20) => requestJson<MemoryRevisionPageResponse>(
    `/api/admin/memories/${memoryId}/revisions?page=${page}&size=${size}`,
  ),
}
