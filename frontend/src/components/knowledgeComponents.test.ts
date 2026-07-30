import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { KnowledgeDocumentSummary, KnowledgeImportBatch } from '../api/knowledge'
import ConfirmDialog from './ConfirmDialog.vue'
import DocumentDirectoryTree from './DocumentDirectoryTree.vue'
import DocumentList from './DocumentList.vue'
import DocumentStatusBadge from './DocumentStatusBadge.vue'
import ImportResultPanel from './ImportResultPanel.vue'
import ScopeFields from './ScopeFields.vue'
import TagInput from './TagInput.vue'

const summary: KnowledgeDocumentSummary = {
  id: 'document-1',
  format: 'MARKDOWN',
  title: '<img src=x onerror=alert(1)>',
  directory: '业务规则/导入',
  tags: ['场景包'],
  source: { type: 'UPLOAD', wikiUrl: null, originalFilename: '<script>steal()</script>.md', curationNote: null },
  scope: { type: 'PROJECT', projectId: 'project-1', branchId: null },
  status: 'PUBLISHED',
  revision: 2,
  syncStatus: 'STALE',
  updatedAt: '2026-07-30T00:00:00Z',
}

describe('knowledge components', () => {
  /**
   * 业务目的：目录必须支持键盘选择并回传完整逻辑路径，防止同名子目录导致范围错选或只能用鼠标操作。
   */
  it('selects exact directory paths from keyboard controls', async () => {
    const wrapper = mount(DocumentDirectoryTree, {
      props: {
        nodes: [{ path: '业务规则/导入', name: '导入', documentCount: 3 }],
        currentPath: '',
      },
    })

    await wrapper.get('[data-directory="业务规则/导入"]').trigger('keydown.enter')

    expect(wrapper.emitted('select')?.[0]).toEqual(['业务规则/导入'])
  })

  /**
   * 业务目的：文档状态和索引同步状态必须同时清晰表达，防止管理员把已发布但待同步的文档误认为已经可检索。
   */
  it('renders lifecycle and stale index status without interpreting document text', () => {
    const badge = mount(DocumentStatusBadge, { props: { status: 'PUBLISHED', syncStatus: 'STALE' } })
    const list = mount(DocumentList, { props: { documents: [summary], selectedId: null } })

    expect(badge.text()).toContain('已发布')
    expect(badge.text()).toContain('索引待同步')
    expect(list.text()).toContain('<img src=x onerror=alert(1)>')
    expect(list.find('img').exists()).toBe(false)
    expect(list.find('script').exists()).toBe(false)
  })

  /**
   * 业务目的：标签输入应支持 Enter 添加、忽略大小写重复并用退格删除末项，防止保存出重复标签或键盘无法维护标签。
   */
  it('edits unique tags with the keyboard', async () => {
    const wrapper = mount(TagInput, { props: { id: 'tags', label: '知识标签', modelValue: ['规则'] } })
    const input = wrapper.get('input')
    await input.setValue('场景包')
    await input.trigger('keydown.enter')
    await input.setValue('规则')
    await input.trigger('keydown.enter')
    await input.setValue('')
    await input.trigger('keydown.backspace')

    const updates = wrapper.emitted('update:modelValue')?.map(event => event[0])
    expect(updates).toContainEqual(['规则', '场景包'])
    expect(updates).not.toContainEqual(['规则', '场景包', '规则'])
    expect(updates?.at(-1)).toEqual(['规则'])
  })

  /**
   * 业务目的：三级范围切换必须立即清理不再适用的项目和分支值，防止隐藏字段把文档写入错误范围。
   */
  it('clears project and branch when switching to global scope', async () => {
    const wrapper = mount(ScopeFields, {
      props: {
        modelValue: { type: 'BRANCH', project: 'network-designer', branch: 'feature/import' },
        projects: [{ identifier: 'network-designer', name: '网络设计工具', branches: ['main', 'feature/import'] }],
      },
    })

    await wrapper.get('[data-testid="scope-type"]').setValue('GLOBAL')

    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({ type: 'GLOBAL', project: null, branch: null })
  })

  /**
   * 业务目的：危险生命周期操作必须经过明确对话框，提交期间禁用所有退出和重复确认入口，防止归档请求并发或状态不明。
   */
  it('marks dangerous confirmation, moves focus and disables actions while busy', async () => {
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    const wrapper = mount(ConfirmDialog, {
      props: {
        open: true,
        title: '归档知识',
        message: '归档后将退出普通浏览。',
        confirmLabel: '确认归档',
        danger: true,
        busy: false,
      },
      attachTo: document.body,
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.get('[data-testid="confirm-dialog-submit"]').classes()).toContain('app-button--danger')
    expect(document.activeElement).toBe(wrapper.get('[data-testid="confirm-dialog-cancel"]').element)

    await wrapper.setProps({ busy: true })

    expect(wrapper.get('[data-testid="confirm-dialog-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="confirm-dialog-cancel"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
    trigger.remove()
  })

  /**
   * 业务目的：部分成功批次必须同时展示成功、失败和忽略三组，并把文件名当文本，防止用户误以为整批成功或执行上传内容。
   */
  it('groups all import outcomes and renders untrusted filenames as text', () => {
    const batch: KnowledgeImportBatch = {
      id: 'batch-1',
      originalFilename: '<img src=x onerror=alert(1)>.zip',
      scope: { type: 'GLOBAL', projectId: null, branchId: null },
      directoryPrefix: '',
      status: 'PARTIAL',
      succeededCount: 1,
      failedCount: 1,
      ignoredCount: 1,
      items: [
        { ordinal: 0, entryName: 'ok.md', status: 'SUCCEEDED', reason: 'IMPORTED', message: '已导入', documentId: 'document-1' },
        { ordinal: 1, entryName: '<script>bad()</script>.md', status: 'FAILED', reason: 'INVALID_TEXT_ENCODING', message: '编码无效', documentId: null },
        { ordinal: 2, entryName: 'image.png', status: 'IGNORED', reason: 'UNSUPPORTED_FILE_TYPE', message: '格式不支持', documentId: null },
      ],
      createdAt: '2026-07-30T00:00:00Z',
      createdBy: 'admin',
    }
    const wrapper = mount(ImportResultPanel, { props: { batch } })

    expect(wrapper.text()).toContain('成功 1')
    expect(wrapper.text()).toContain('失败 1')
    expect(wrapper.text()).toContain('忽略 1')
    expect(wrapper.text()).toContain('<script>bad()</script>.md')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
