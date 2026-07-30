<template>
  <aside class="app-sidebar">
    <RouterLink class="sidebar-brand" to="/projects" aria-label="LoreDock 项目列表">
      <span class="brand-mark">L</span><strong>LoreDock</strong>
    </RouterLink>

    <button class="sidebar-new-question" type="button" disabled>
      <IconGlyph name="message" />新建问答
    </button>

    <nav class="sidebar-nav" aria-label="主导航">
      <button type="button" disabled><IconGlyph name="message" />问答</button>
      <RouterLink to="/projects"><IconGlyph name="folder" />项目</RouterLink>
      <RouterLink data-testid="global-knowledge-link" to="/knowledge"><IconGlyph name="book" />通用业务知识</RouterLink>
      <button type="button" disabled><IconGlyph name="search" />全局搜索</button>
    </nav>

    <div v-if="currentProject" class="sidebar-project">
      <p>当前项目</p>
      <RouterLink data-testid="current-project-link" class="sidebar-project__card" :to="`/projects/${currentProject.identifier}`">
        <span class="project-icon"><IconGlyph name="network" /></span>
        <span><strong>{{ currentProject.name }}</strong><small>{{ currentProject.identifier }}</small></span>
      </RouterLink>
    </div>
    <div v-else class="sidebar-recent">
      <p>最近问答</p>
      <button v-for="question in DESIGN_SAMPLES.recentQuestions" :key="question" type="button" disabled>{{ question }}</button>
    </div>

    <div class="sidebar-profile">
      <span class="sidebar-avatar">{{ role === 'ADMIN' ? '管' : '阅' }}</span>
      <span><strong>{{ displayName }}</strong><small>{{ role === 'ADMIN' ? '内容与项目维护' : '只读浏览与问答' }}</small></span>
      <button type="button" aria-label="退出登录" @click="$emit('logout')"><IconGlyph name="logout" /></button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { WebRole } from '../api/types'
import { DESIGN_SAMPLES } from '../designSamples'
import IconGlyph from './IconGlyph.vue'

defineProps<{
  displayName: string
  role: WebRole
  currentProject?: { name: string; identifier: string }
}>()

defineEmits<{ logout: [] }>()
</script>
