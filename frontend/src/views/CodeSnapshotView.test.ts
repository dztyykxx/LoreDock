import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CodeSnapshotApi, CodeSnapshotJob } from '../api/codeSnapshots'
import type { ProjectApi } from '../api/projects'
import type { AdminProjectDetail, SessionView } from '../api/types'
import { codeSnapshotApiKey, projectApiKey, sessionKey } from '../appContext'
import type { SessionController, SessionStatus } from '../session/useSession'
import CodeSnapshotView from './CodeSnapshotView.vue'

const project: AdminProjectDetail = {
  id: 1,
  identifier: 'nanobot',
  name: 'nanobot',
  description: '本地 coding harness',
  technologyStack: 'Python',
  status: 'ENABLED',
  defaultBranch: 'main',
  branches: [{
    id: 11,
    name: 'main',
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:00Z',
    createdBy: 'admin',
    updatedBy: 'admin',
  }],
  createdAt: '2026-07-30T00:00:00Z',
  updatedAt: '2026-07-30T00:00:00Z',
  createdBy: 'admin',
  updatedBy: 'admin',
}

const pendingJob: CodeSnapshotJob = {
  snapshotId: 21,
  jobId: 31,
  projectId: project.id,
  branchId: 11,
  commit: 'a41e9c7',
  status: 'PENDING',
  progress: 0,
  indexedFileCount: 0,
  ignoredFileCount: 0,
  createdAt: '2026-07-31T04:00:00Z',
  finishedAt: null,
  failureCode: null,
  failureSummary: null,
}

function session(): SessionController {
  const identity: SessionView = { username: 'admin', displayName: '管理员', role: 'ADMIN' }
  return {
    status: ref<SessionStatus>('authenticated'),
    identity: ref(identity),
    restore: vi.fn().mockResolvedValue(undefined),
    login: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    clear: vi.fn(),
  }
}

function projects(): ProjectApi {
  return {
    listProjects: vi.fn(),
    getProject: vi.fn(),
    getAdminProject: vi.fn().mockResolvedValue(project),
    createProject: vi.fn(),
    addBranch: vi.fn(),
    changeStatus: vi.fn(),
  }
}

async function render(api: CodeSnapshotApi) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/projects/:projectId/code-snapshots', component: CodeSnapshotView }],
  })
  await router.push('/projects/1/code-snapshots')
  await router.isReady()
  const wrapper = mount(CodeSnapshotView, {
    global: {
      plugins: [router],
      provide: {
        [sessionKey as symbol]: session(),
        [projectApiKey as symbol]: projects(),
        [codeSnapshotApiKey as symbol]: api,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('CodeSnapshotView', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  /**
   * 业务目的：管理员上传有效 ZIP 后必须看到后台任务进度，并在任务成功时刷新为可查询的活动快照。
   */
  it('uploads and refreshes the active snapshot after the indexing job succeeds', async () => {
    vi.useFakeTimers()
    const getActive = vi.fn()
      .mockResolvedValueOnce({ projectIdentifier: 'nanobot', branch: 'main', status: 'NOT_INDEXED' })
      .mockResolvedValueOnce({
        projectIdentifier: 'nanobot', branch: 'main', status: 'INDEXED', snapshotId: 21,
        commit: 'a41e9c7', indexedAt: '2026-07-31T04:01:00Z', indexedFileCount: 128, changeHint: 'INITIAL',
      })
    const upload = vi.fn().mockResolvedValue(pendingJob)
    const getJob = vi.fn().mockResolvedValue({
      ...pendingJob,
      status: 'SUCCEEDED',
      progress: 100,
      indexedFileCount: 128,
      ignoredFileCount: 5,
      finishedAt: '2026-07-31T04:01:00Z',
    })
    const wrapper = await render({ getActive, upload, getJob, reindex: vi.fn() })
    const input = wrapper.get('[data-testid="snapshot-file"]')
    const file = new File(['PK\u0003\u0004'], 'nanobot.zip', { type: 'application/zip' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await wrapper.get('[data-testid="snapshot-commit"]').setValue('a41e9c7')
    await wrapper.get('[data-testid="snapshot-upload-form"]').trigger('submit')
    await flushPromises()

    expect(upload).toHaveBeenCalledWith({
      projectId: project.id,
      branchId: 11,
      commit: 'a41e9c7',
      file,
    })
    expect(wrapper.text()).toContain('等待索引')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(getJob).toHaveBeenCalledWith(31)
    expect(getActive).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('可查询')
    expect(wrapper.text()).toContain('128 个文件')
  })

  /**
   * 业务目的：浏览器必须在上传前拒绝非 ZIP 文件，避免管理员等待后台任务后才发现明显的文件类型错误。
   */
  it('rejects a non-zip file before calling the backend', async () => {
    const upload = vi.fn()
    const wrapper = await render({
      getActive: vi.fn().mockResolvedValue({ projectIdentifier: 'nanobot', branch: 'main', status: 'NOT_INDEXED' }),
      upload,
      getJob: vi.fn(),
      reindex: vi.fn(),
    })
    const input = wrapper.get('[data-testid="snapshot-file"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['source'], 'main.tar.gz', { type: 'application/gzip' })],
    })
    await input.trigger('change')
    await wrapper.get('[data-testid="snapshot-commit"]').setValue('a41e9c7')
    await wrapper.get('[data-testid="snapshot-upload-form"]').trigger('submit')

    expect(upload).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('请选择 ZIP 文件')
  })
})
