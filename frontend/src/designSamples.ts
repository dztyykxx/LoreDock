export const DESIGN_SAMPLES = {
  recentQuestions: [
    '场景包导入后为何刷新拓扑？',
    '旧版本文件如何兼容？',
    '导出流程涉及哪些模块？',
  ],
  projectKnowledgeCounts: [26, 8],
  tabs: [
    { id: 'knowledge', label: '知识文档', count: 26 },
    { id: 'changes', label: '变更知识', count: 4 },
    { id: 'drafts', label: '待审核草稿', count: 3, tone: 'warning' },
    { id: 'reports', label: '整理报告', count: 2 },
    { id: 'settings', label: '项目设置' },
  ],
} as const
