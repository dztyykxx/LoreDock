<template>
  <header class="app-topbar">
    <div class="app-topbar__breadcrumb"><span>项目</span><IconGlyph name="chevronRight" /><strong>{{ projectName }}</strong></div>
  </header>
</template>

<script setup lang="ts">
import IconGlyph from './IconGlyph.vue'

defineProps<{ projectName: string }>()
</script>
