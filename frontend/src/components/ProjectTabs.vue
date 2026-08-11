<template>
  <nav class="project-tabs" :aria-label="global ? '通用业务知识模块' : '项目模块'">
    <RouterLink
      data-tab="knowledge"
      :to="knowledgeTarget"
      :class="{ 'project-tabs__item--active': active === 'knowledge' }"
      :aria-current="active === 'knowledge' ? 'page' : undefined"
    >
      知识文档 <span v-if="knowledgeCount !== null">{{ knowledgeCount }}</span>
    </RouterLink>
    <RouterLink
      v-if="projectIdentifier"
      data-tab="qa"
      :to="qaTarget"
      :class="{ 'project-tabs__item--active': active === 'qa' }"
      :aria-current="active === 'qa' ? 'page' : undefined"
    >项目问答</RouterLink>
    <RouterLink
      v-if="role === 'ADMIN' && (projectIdentifier || global)"
      data-tab="drafts"
      :to="draftsTarget"
      :class="{ 'project-tabs__item--active': active === 'drafts' }"
      :aria-current="active === 'drafts' ? 'page' : undefined"
    >草稿 <span v-if="draftCount !== null" class="tab-count--warning">{{ draftCount }}</span></RouterLink>
    <RouterLink
      v-if="role === 'ADMIN' && (projectIdentifier || global)"
      data-tab="tasks"
      :to="tasksTarget"
      :class="{ 'project-tabs__item--active': active === 'tasks' }"
      :aria-current="active === 'tasks' ? 'page' : undefined"
    >知识任务 <span v-if="taskCount !== null">{{ taskCount }}</span></RouterLink>
    <RouterLink
      v-if="role === 'ADMIN' && projectId"
      data-tab="settings"
      :to="`/projects/${projectId}/settings`"
      :class="{ 'project-tabs__item--active': active === 'settings' }"
      :aria-current="active === 'settings' ? 'page' : undefined"
    >项目设置</RouterLink>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { WebRole } from '../api/types'

const props = withDefaults(defineProps<{
  active: string
  role?: WebRole
  projectIdentifier?: string
  projectId?: number
  /** 无项目上下文的通用业务知识页：草稿与知识任务指向全局入口 */
  global?: boolean
  knowledgeCount?: number | null
  draftCount?: number | null
  taskCount?: number | null
}>(), {
  role: 'MEMBER',
  projectIdentifier: '',
  projectId: 0,
  global: false,
  knowledgeCount: null,
  draftCount: null,
  taskCount: null,
})

// 目标必须随 props 响应式更新：项目页首次渲染时项目详情尚未加载完成（projectIdentifier 为空），
// 若用 setup 期常量固化，项目加载完成后 tab 仍会指向全局入口。
const knowledgeTarget = computed(() => props.projectIdentifier ? `/projects/${props.projectIdentifier}` : '/knowledge')
const qaTarget = computed(() => `/projects/${props.projectIdentifier}/qa`)
const draftsTarget = computed(() => props.projectIdentifier ? `/projects/${props.projectIdentifier}/drafts` : '/knowledge/drafts')
const tasksTarget = computed(() => props.projectIdentifier
  ? `/projects/${props.projectIdentifier}/knowledge-tasks`
  : '/knowledge/knowledge-tasks')
</script>
