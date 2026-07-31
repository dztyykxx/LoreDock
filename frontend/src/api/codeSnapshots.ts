import { requestJson } from './http'

export type CodeSnapshotAvailability = 'NOT_INDEXED' | 'INDEXED'
export type CodeSnapshotChangeHint = 'INITIAL' | 'CHANGED' | 'UNCHANGED'
export type CodeSnapshotJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface ActiveCodeSnapshot {
  projectIdentifier: string
  branch: string
  status: CodeSnapshotAvailability
  snapshotId?: string | null
  commit?: string | null
  indexedAt?: string | null
  indexedFileCount?: number | null
  changeHint?: CodeSnapshotChangeHint | null
}

export interface CodeSnapshotJob {
  snapshotId: string
  jobId: string
  projectId: string
  branchId: string
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
  projectId: string
  branchId: string
  commit: string
  file: File
}

export interface CodeSnapshotApi {
  getActive(identifier: string, branch: string): Promise<ActiveCodeSnapshot>
  upload(input: UploadCodeSnapshotInput): Promise<CodeSnapshotJob>
  getJob(jobId: string): Promise<CodeSnapshotJob>
  reindex(snapshotId: string): Promise<CodeSnapshotJob>
}

export const codeSnapshotApi: CodeSnapshotApi = {
  getActive(identifier, branch) {
    return requestJson<ActiveCodeSnapshot>(
      `/api/projects/${encodeURIComponent(identifier)}/code-snapshot?branch=${encodeURIComponent(branch)}`,
    )
  },
  upload(input) {
    const form = new FormData()
    form.append('projectId', input.projectId)
    form.append('branchId', input.branchId)
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
