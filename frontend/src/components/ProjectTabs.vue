<template>
  <nav class="project-tabs" aria-label="项目模块">
    <RouterLink
      data-tab="knowledge"
      :to="knowledgeTarget"
      :class="{ 'project-tabs__item--active': active === 'knowledge' }"
      :aria-current="active === 'knowledge' ? 'page' : undefined"
    >
      知识文档 <span>{{ knowledgeCount }}</span>
    </RouterLink>
    <RouterLink
      v-if="projectIdentifier"
      data-tab="qa"
      :to="qaTarget"
      :class="{ 'project-tabs__item--active': active === 'qa' }"
      :aria-current="active === 'qa' ? 'page' : undefined"
    >项目问答</RouterLink>
    <button
      v-for="tab in futureTabs"
      :key="tab.id"
      type="button"
      :data-tab="tab.id"
      disabled
    >
      {{ tab.label }}
      <span v-if="'count' in tab" :class="{ 'tab-count--warning': 'tone' in tab && tab.tone === 'warning' }">{{ tab.count }}</span>
    </button>
    <RouterLink
      v-if="role === 'ADMIN' && projectId"
      data-tab="code-snapshots"
      :to="codeSnapshotTarget"
      :class="{ 'project-tabs__item--active': active === 'code-snapshots' }"
      :aria-current="active === 'code-snapshots' ? 'page' : undefined"
    >代码快照</RouterLink>
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
import { DESIGN_SAMPLES } from '../designSamples'

const props = withDefaults(defineProps<{
  active: string
  role?: WebRole
  projectIdentifier?: string
  projectId?: string
  branch?: string
  knowledgeCount?: number
}>(), {
  role: 'MEMBER',
  projectIdentifier: '',
  projectId: '',
  branch: 'main',
  knowledgeCount: 0,
})

const knowledgeTarget = computed(() => {
  const path = props.projectIdentifier ? `/projects/${props.projectIdentifier}` : '/knowledge'
  return props.branch && props.branch !== 'main' ? { path, query: { branch: props.branch } } : path
})
const qaTarget = computed(() => props.branch && props.branch !== 'main'
  ? { path: `/projects/${props.projectIdentifier}/qa`, query: { branch: props.branch } }
  : `/projects/${props.projectIdentifier}/qa`)
const codeSnapshotTarget = computed(() => props.branch && props.branch !== 'main'
  ? { path: `/projects/${props.projectId}/code-snapshots`, query: { branch: props.branch } }
  : `/projects/${props.projectId}/code-snapshots`)
const futureTabs = DESIGN_SAMPLES.tabs.filter(tab => tab.id !== 'knowledge' && tab.id !== 'settings')
</script>
