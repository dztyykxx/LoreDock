import { requestJson } from './http'

export type MemoryScope = 'GLOBAL' | 'PROJECT'
export type MemoryCategory = 'FORMAT' | 'TEMPLATE' | 'CONTENT' | 'STYLE' | 'PROCESS' | 'OTHER'
export type MemoryStatus = 'ACTIVE' | 'DISABLED'
export type MemorySourceType = 'KNOWLEDGE_CURATION' | 'MANUAL'

export interface MemoryView {
  id: number
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
}

export interface MemoryApi {
  list(query?: MemoryListQuery): Promise<MemoryPageResponse>
  create(input: MemoryCreateInput): Promise<MemoryView>
  update(memoryId: number, input: MemoryUpdateInput): Promise<MemoryView>
  changeStatus(memoryId: number, status: MemoryStatus): Promise<MemoryView>
  delete(memoryId: number): Promise<void>
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
  changeStatus: (memoryId, status) => requestJson<MemoryView>(`/api/admin/memories/${memoryId}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  }),
  delete: memoryId => requestJson<void>(`/api/admin/memories/${memoryId}`, { method: 'DELETE' }),
}
