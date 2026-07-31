export type WebRole = 'ADMIN' | 'MEMBER'

export interface SessionView {
  username: string
  displayName: string
  role: WebRole
}

export interface FieldError {
  field: string
  message: string
}

export interface ApiErrorBody {
  code: string
  message: string
  timestamp: string
  traceId: string
  fieldErrors: FieldError[]
}

export interface BranchView {
  id: number
  name: string
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

export interface ProjectSummary {
  id: number
  identifier: string
  name: string
  description: string
  technologyStack: string
  defaultBranch: string
  branchCount: number
}

export interface ProjectDetail {
  id: number
  identifier: string
  name: string
  description: string
  technologyStack: string
  defaultBranch: string
  selectedBranch: string
  branches: BranchView[]
}

export type ProjectStatus = 'ENABLED' | 'DISABLED'

export interface AdminProjectDetail {
  id: number
  identifier: string
  name: string
  description: string
  technologyStack: string
  status: ProjectStatus
  defaultBranch: string
  branches: BranchView[]
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

export interface CreateProjectInput {
  name: string
  identifier: string
  description: string
  technologyStack: string
}
