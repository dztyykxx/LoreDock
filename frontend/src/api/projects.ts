import { requestJson } from './http'
import type {
  AdminProjectDetail,
  BranchView,
  CreateProjectInput,
  ProjectDetail,
  ProjectStatus,
  ProjectSummary,
} from './types'

export interface ProjectApi {
  listProjects(): Promise<ProjectSummary[]>
  getProject(identifier: string, branch?: string): Promise<ProjectDetail>
  getAdminProject(projectId: string): Promise<AdminProjectDetail>
  createProject(input: CreateProjectInput): Promise<AdminProjectDetail>
  addBranch(projectId: string, name: string): Promise<BranchView>
  changeStatus(projectId: string, status: ProjectStatus): Promise<AdminProjectDetail>
}

export const projectApi: ProjectApi = {
  listProjects: () => requestJson<ProjectSummary[]>('/api/projects'),
  getProject(identifier, branch) {
    const query = branch ? `?branch=${encodeURIComponent(branch)}` : ''
    return requestJson<ProjectDetail>(`/api/projects/${encodeURIComponent(identifier)}${query}`)
  },
  getAdminProject: projectId => requestJson<AdminProjectDetail>(`/api/admin/projects/${projectId}`),
  createProject: input => requestJson<AdminProjectDetail>('/api/admin/projects', {
    method: 'POST',
    body: JSON.stringify(input),
  }),
  addBranch: (projectId, name) => requestJson<BranchView>(`/api/admin/projects/${projectId}/branches`, {
    method: 'POST',
    body: JSON.stringify({ name }),
  }),
  changeStatus: (projectId, status) => requestJson<AdminProjectDetail>(`/api/admin/projects/${projectId}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  }),
}
