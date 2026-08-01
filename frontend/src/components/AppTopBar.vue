<template>
  <header class="app-topbar">
    <div class="app-topbar__breadcrumb">
      <RouterLink data-testid="workspace-link" to="/projects">工作空间</RouterLink>
      <IconGlyph name="chevronRight" />
      <strong>{{ projectName }}</strong>
    </div>
  </header>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import IconGlyph from './IconGlyph.vue'

defineProps<{ projectName: string }>()
</script>
