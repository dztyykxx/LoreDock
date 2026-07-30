<template>
  <header class="app-topbar">
    <div class="app-topbar__breadcrumb"><span>项目</span><IconGlyph name="chevronRight" /><strong>{{ projectName }}</strong></div>
    <label v-if="branches.length" class="branch-selector">
      <span class="sr-only">当前分支</span>
      <IconGlyph name="branch" />
      <select data-testid="branch-selector" :value="selectedBranch" @change="$emit('branchChange', ($event.target as HTMLSelectElement).value)">
        <option v-for="branch in branches" :key="branch" :value="branch">{{ branch }}</option>
      </select>
    </label>
  </header>
</template>

<script setup lang="ts">
import IconGlyph from './IconGlyph.vue'

defineProps<{ projectName: string; selectedBranch: string; branches: string[] }>()
defineEmits<{ branchChange: [branch: string] }>()
</script>
