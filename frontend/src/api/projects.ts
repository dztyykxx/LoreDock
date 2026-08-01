import { requestJson } from './http'
import type {
  AdminProjectDetail,
  CreateProjectInput,
  ProjectDetail,
  ProjectStatus,
  ProjectSummary,
} from './types'

export interface ProjectApi {
  listProjects(): Promise<ProjectSummary[]>
  getProject(identifier: string): Promise<ProjectDetail>
  getAdminProject(projectId: number): Promise<AdminProjectDetail>
  createProject(input: CreateProjectInput): Promise<AdminProjectDetail>
  changeStatus(projectId: number, status: ProjectStatus): Promise<AdminProjectDetail>
}

export const projectApi: ProjectApi = {
  listProjects: () => requestJson<ProjectSummary[]>('/api/projects'),
  getProject: identifier => requestJson<ProjectDetail>(`/api/projects/${encodeURIComponent(identifier)}`),
  getAdminProject: projectId => requestJson<AdminProjectDetail>(`/api/admin/projects/${projectId}`),
  createProject: input => requestJson<AdminProjectDetail>('/api/admin/projects', {
    method: 'POST',
    body: JSON.stringify(input),
  }),
  changeStatus: (projectId, status) => requestJson<AdminProjectDetail>(`/api/admin/projects/${projectId}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  }),
}
