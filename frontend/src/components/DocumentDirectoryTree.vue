<template>
  <nav class="document-directory-tree" aria-label="知识目录">
    <button
      type="button"
      data-directory=""
      :aria-current="currentPath === '' ? 'page' : undefined"
      @click="$emit('select', '')"
      @keydown.enter.prevent="$emit('select', '')"
    >
      <IconGlyph name="book" /><span>全部文档</span>
    </button>
    <button
      v-for="node in nodes"
      :key="node.path"
      type="button"
      :data-directory="node.path"
      :aria-current="currentPath === node.path ? 'page' : undefined"
      :style="{ '--directory-depth': directoryDepth(node.path) }"
      @click="$emit('select', node.path)"
      @keydown.enter.prevent="$emit('select', node.path)"
    >
      <IconGlyph name="folder" />
      <span>{{ node.name }}</span>
      <small>{{ node.documentCount }}</small>
    </button>
  </nav>
</template>

<script setup lang="ts">
import type { KnowledgeDirectoryNode } from '../api/knowledge'
import IconGlyph from './IconGlyph.vue'

defineProps<{ nodes: KnowledgeDirectoryNode[]; currentPath: string }>()
defineEmits<{ select: [path: string] }>()

function directoryDepth(path: string): number {
  return Math.max(0, path.split('/').filter(Boolean).length - 1)
}
</script>
