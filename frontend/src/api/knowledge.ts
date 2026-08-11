import { requestJson, type ApiError } from './http'

export type DocumentFormat = 'MARKDOWN' | 'PLAIN_TEXT'
export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type KnowledgeScopeType = 'GLOBAL' | 'PROJECT' | 'BRANCH'
export type KnowledgeIndexSyncStatus = 'NOT_APPLICABLE' | 'NEVER_INDEXED' | 'PENDING' | 'STALE' | 'SYNCED'
export type DocumentSourceType = 'MANUAL' | 'WIKI' | 'UPLOAD'
export type KnowledgeImportBatchStatus = 'COMPLETED' | 'PARTIAL' | 'FAILED'
export type KnowledgeImportItemStatus = 'SUCCEEDED' | 'FAILED' | 'IGNORED'
export type KnowledgeIndexJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface KnowledgeScopeView {
  type: KnowledgeScopeType
  projectId: number | null
  branchId: number | null
}

export type KnowledgeScopeInput =
  | { type: 'GLOBAL'; project?: null; branch?: null }
  | { type: 'PROJECT'; project: string | null; branch?: null }

export interface DocumentSourceView {
  type: DocumentSourceType
  wikiUrl: string | null
  originalFilename: string | null
  curationNote: string | null
}

export interface DocumentSourceInput {
  type: DocumentSourceType
  wikiUrl?: string | null
  originalFilename?: string | null
  curationNote?: string | null
}

export interface KnowledgeDocumentSummary {
  id: number
  format: DocumentFormat
  title: string
  directory: string
  tags: string[]
  source: DocumentSourceView
  scope: KnowledgeScopeView
  status: DocumentStatus
  revision: number
  syncStatus: KnowledgeIndexSyncStatus
  updatedAt: string
}

export interface KnowledgeDirectoryNode {
  path: string
  name: string
  documentCount: number
}

export interface KnowledgeDocumentView extends KnowledgeDocumentSummary {
  body: string
  publishedAt: string | null
}

export interface AdminKnowledgeDocumentView extends KnowledgeDocumentView {
  publishedBy: string | null
  archivedAt: string | null
  archivedBy: string | null
  replacement: {
    replacesDocumentId: number | null
    replacedByDocumentId: number | null
  }
  createdAt: string
  createdBy: string
  updatedBy: string
}

export interface KnowledgeBrowseResult {
  directories: KnowledgeDirectoryNode[]
  documents: PageResult<KnowledgeDocumentSummary>
}

export interface BrowseKnowledgeInput {
  context: 'GLOBAL' | 'PROJECT'
  project?: string
  directory?: string
  includeDescendants?: boolean
  /** 项目列表是否排除通用知识文档；项目页文档列表与草稿列表使用 */
  excludeGlobal?: boolean
  page?: number
  size?: number
}

export type AdminBrowseKnowledgeInput = Omit<BrowseKnowledgeInput, 'includeDescendants'> & {
  status?: DocumentStatus
}

export interface BatchPublishKnowledgeResult {
  requestedCount: number
  publishedCount: number
  alreadyPublishedCount: number
}

export interface AdminKnowledgeFilter {
  scopeType?: KnowledgeScopeType
  projectId?: number
  branchId?: number
  directory?: string
  status?: DocumentStatus
  tag?: string
  page?: number
  size?: number
}

export interface KnowledgeDocumentWriteInput {
  format: DocumentFormat
  title: string
  body: string
  directory: string
  tags: string[]
  source: DocumentSourceInput
  scope: KnowledgeScopeInput
}

export interface KnowledgeImportOptions {
  scope: KnowledgeScopeInput
  directoryPrefix: string
  tags: string[]
  sourceDefaults: DocumentSourceInput
}

export interface KnowledgeImportItem {
  ordinal: number
  entryName: string
  status: KnowledgeImportItemStatus
  reason: string
  message: string
  documentId: number | null
}

export interface KnowledgeImportBatch {
  id: number
  originalFilename: string
  scope: KnowledgeScopeView
  directoryPrefix: string
  status: KnowledgeImportBatchStatus
  succeededCount: number
  failedCount: number
  ignoredCount: number
  items: KnowledgeImportItem[]
  createdAt: string
  createdBy: string
}

export interface KnowledgeIndexJob {
  id: number
  status: KnowledgeIndexJobStatus
  progress: number
  startedAt: string | null
  finishedAt: string | null
  failureSummary: string | null
}

export interface KnowledgeIndexPollOptions {
  maxAttempts?: number
  intervalMs?: number
  signal?: AbortSignal
}

export interface KnowledgeApi {
  browse(input: BrowseKnowledgeInput): Promise<KnowledgeBrowseResult>
  browseAdmin(input: AdminBrowseKnowledgeInput): Promise<KnowledgeBrowseResult>
  getDocument(documentId: number, input: Pick<BrowseKnowledgeInput, 'context' | 'project' | 'excludeGlobal'>): Promise<KnowledgeDocumentView>
  listAdmin(input?: AdminKnowledgeFilter): Promise<PageResult<KnowledgeDocumentSummary>>
  getAdminDocument(documentId: number): Promise<AdminKnowledgeDocumentView>
  createDocument(input: KnowledgeDocumentWriteInput): Promise<AdminKnowledgeDocumentView>
  updateDocument(documentId: number, input: KnowledgeDocumentWriteInput): Promise<AdminKnowledgeDocumentView>
  publishDocument(documentId: number, replacesDocumentId?: number): Promise<AdminKnowledgeDocumentView>
  batchPublishDocuments(documentIds: number[]): Promise<BatchPublishKnowledgeResult>
  archiveDocument(documentId: number): Promise<AdminKnowledgeDocumentView>
  importDocuments(file: File, options: KnowledgeImportOptions): Promise<KnowledgeImportBatch>
  getImportBatch(batchId: number): Promise<KnowledgeImportBatch>
  submitIndexJob(): Promise<KnowledgeIndexJob>
  getIndexJob(jobId: number): Promise<KnowledgeIndexJob>
  pollIndexJob(jobId: number, options?: KnowledgeIndexPollOptions): Promise<KnowledgeIndexJob>
}

function appendQuery(params: URLSearchParams, key: string, value: string | number | undefined): void {
  if (value !== undefined) {
    params.set(key, String(value))
  }
}

export const knowledgeApi: KnowledgeApi = {
  browse(input) {
    const query = new URLSearchParams()
    appendQuery(query, 'context', input.context)
    appendQuery(query, 'project', input.project)
    appendQuery(query, 'directory', input.directory)
    appendQuery(query, 'includeDescendants', input.includeDescendants === undefined ? undefined : String(input.includeDescendants))
    appendQuery(query, 'excludeGlobal', input.excludeGlobal === undefined ? undefined : String(input.excludeGlobal))
    appendQuery(query, 'page', input.page)
    appendQuery(query, 'size', input.size)
    return requestJson<KnowledgeBrowseResult>(`/api/knowledge-documents?${query}`)
  },
  browseAdmin(input) {
    const query = new URLSearchParams()
    appendQuery(query, 'context', input.context)
    appendQuery(query, 'project', input.project)
    appendQuery(query, 'directory', input.directory)
    appendQuery(query, 'status', input.status)
    appendQuery(query, 'excludeGlobal', input.excludeGlobal === undefined ? undefined : String(input.excludeGlobal))
    appendQuery(query, 'page', input.page)
    appendQuery(query, 'size', input.size)
    return requestJson<KnowledgeBrowseResult>(`/api/admin/knowledge-documents/browse?${query}`)
  },
  getDocument(documentId, input) {
    const query = new URLSearchParams()
    appendQuery(query, 'context', input.context)
    appendQuery(query, 'project', input.project)
    appendQuery(query, 'excludeGlobal', input.excludeGlobal === undefined ? undefined : String(input.excludeGlobal))
    return requestJson<KnowledgeDocumentView>(
      `/api/knowledge-documents/${encodeURIComponent(documentId)}?${query}`,
    )
  },
  listAdmin(input = {}) {
    const query = new URLSearchParams()
    appendQuery(query, 'scopeType', input.scopeType)
    appendQuery(query, 'projectId', input.projectId)
    appendQuery(query, 'branchId', input.branchId)
    appendQuery(query, 'directory', input.directory)
    appendQuery(query, 'status', input.status)
    appendQuery(query, 'tag', input.tag)
    appendQuery(query, 'page', input.page)
    appendQuery(query, 'size', input.size)
    const suffix = query.size > 0 ? `?${query}` : ''
    return requestJson<PageResult<KnowledgeDocumentSummary>>(`/api/admin/knowledge-documents${suffix}`)
  },
  getAdminDocument: documentId => requestJson<AdminKnowledgeDocumentView>(
    `/api/admin/knowledge-documents/${encodeURIComponent(documentId)}`,
  ),
  createDocument: input => requestJson<AdminKnowledgeDocumentView>('/api/admin/knowledge-documents', {
    method: 'POST',
    body: JSON.stringify(input),
  }),
  updateDocument: (documentId, input) => requestJson<AdminKnowledgeDocumentView>(
    `/api/admin/knowledge-documents/${encodeURIComponent(documentId)}`,
    { method: 'PUT', body: JSON.stringify(input) },
  ),
  publishDocument: (documentId, replacesDocumentId) => requestJson<AdminKnowledgeDocumentView>(
    `/api/admin/knowledge-documents/${encodeURIComponent(documentId)}/publish`,
    {
      method: 'POST',
      body: JSON.stringify({ replacesDocumentId: replacesDocumentId ?? null }),
    },
  ),
  batchPublishDocuments: documentIds => requestJson<BatchPublishKnowledgeResult>(
    '/api/admin/knowledge-documents/batch-publish',
    { method: 'POST', body: JSON.stringify({ documentIds }) },
  ),
  archiveDocument: documentId => requestJson<AdminKnowledgeDocumentView>(
    `/api/admin/knowledge-documents/${encodeURIComponent(documentId)}/archive`,
    { method: 'POST' },
  ),
  importDocuments(file, options) {
    const form = new FormData()
    form.append('file', file)
    form.append('options', new Blob([JSON.stringify(options)], { type: 'application/json' }))
    return requestJson<KnowledgeImportBatch>('/api/admin/knowledge-document-imports', {
      method: 'POST',
      body: form,
    })
  },
  getImportBatch: batchId => requestJson<KnowledgeImportBatch>(
    `/api/admin/knowledge-document-imports/${encodeURIComponent(batchId)}`,
  ),
  submitIndexJob: () => requestJson<KnowledgeIndexJob>('/api/admin/knowledge-index-jobs', { method: 'POST' }),
  getIndexJob: jobId => requestJson<KnowledgeIndexJob>(
    `/api/admin/knowledge-index-jobs/${encodeURIComponent(jobId)}`,
  ),
  async pollIndexJob(jobId, options = {}) {
    const maxAttempts = Math.max(1, options.maxAttempts ?? 20)
    const intervalMs = Math.max(0, options.intervalMs ?? 1_000)
    let latest: KnowledgeIndexJob | undefined
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      options.signal?.throwIfAborted()
      latest = await knowledgeApi.getIndexJob(jobId)
      if (latest.status === 'SUCCEEDED' || latest.status === 'FAILED') {
        return latest
      }
      if (attempt < maxAttempts - 1 && intervalMs > 0) {
        await wait(intervalMs, options.signal)
      }
    }
    return latest as KnowledgeIndexJob
  },
}

export type KnowledgeApiError = ApiError

function wait(delayMs: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(resolve, delayMs)
    signal?.addEventListener('abort', () => {
      window.clearTimeout(timer)
      reject(signal.reason ?? new DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
}
