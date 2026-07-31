import { requestJson } from './http'

export type CodeSnapshotAvailability = 'NOT_INDEXED' | 'INDEXED'
export type CodeSnapshotChangeHint = 'INITIAL' | 'CHANGED' | 'UNCHANGED'
export type CodeSnapshotJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface ActiveCodeSnapshot {
  projectIdentifier: string
  branch: string
  status: CodeSnapshotAvailability
  snapshotId?: number | null
  commit?: string | null
  indexedAt?: string | null
  indexedFileCount?: number | null
  changeHint?: CodeSnapshotChangeHint | null
}

export interface CodeSnapshotJob {
  snapshotId: number
  jobId: number
  projectId: number
  branchId: number
  commit: string
  status: CodeSnapshotJobStatus
  progress: number
  indexedFileCount: number
  ignoredFileCount: number
  createdAt: string
  finishedAt: string | null
  failureCode: string | null
  failureSummary: string | null
}

export interface UploadCodeSnapshotInput {
  projectId: number
  branchId: number
  commit: string
  file: File
}

export interface CodeSnapshotApi {
  getActive(identifier: string, branch: string): Promise<ActiveCodeSnapshot>
  upload(input: UploadCodeSnapshotInput): Promise<CodeSnapshotJob>
  getJob(jobId: number): Promise<CodeSnapshotJob>
  reindex(snapshotId: number): Promise<CodeSnapshotJob>
}

export const codeSnapshotApi: CodeSnapshotApi = {
  getActive(identifier, branch) {
    return requestJson<ActiveCodeSnapshot>(
      `/api/projects/${encodeURIComponent(identifier)}/code-snapshot?branch=${encodeURIComponent(branch)}`,
    )
  },
  upload(input) {
    const form = new FormData()
    form.append('projectId', String(input.projectId))
    form.append('branchId', String(input.branchId))
    form.append('commit', input.commit)
    form.append('file', input.file)
    return requestJson<CodeSnapshotJob>('/api/admin/code-snapshots', { method: 'POST', body: form })
  },
  getJob: jobId => requestJson<CodeSnapshotJob>(`/api/admin/code-snapshot-jobs/${encodeURIComponent(jobId)}`),
  reindex: snapshotId => requestJson<CodeSnapshotJob>(
    `/api/admin/code-snapshots/${encodeURIComponent(snapshotId)}/reindex`,
    { method: 'POST' },
  ),
}
