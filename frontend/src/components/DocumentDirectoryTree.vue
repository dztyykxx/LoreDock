<template>
  <nav class="document-directory-tree" aria-label="知识目录">
    <button
      type="button"
      class="document-directory-tree__all"
      data-directory=""
      :aria-current="currentPath === '' ? 'page' : undefined"
      @click="emit('select', '')"
    >
      <IconGlyph name="book" /><span>全部文档</span>
    </button>
    <div
      v-for="node in visibleNodes"
      :key="node.path"
      class="document-directory-tree__row"
      :style="{ '--directory-depth': directoryDepth(node.path) }"
    >
      <button
        v-if="hasChildren(node.path)"
        type="button"
        class="document-directory-tree__toggle"
        :aria-label="`${isExpanded(node.path) ? '折叠' : '展开'} ${node.name}`"
        :aria-expanded="isExpanded(node.path)"
        @click="toggleNode(node.path)"
      >
        <IconGlyph name="chevronRight" />
      </button>
      <span v-else class="document-directory-tree__toggle-spacer" />
      <button
        type="button"
        class="document-directory-tree__select"
        :data-directory="node.path"
        :aria-current="currentPath === node.path ? 'page' : undefined"
        @click="emit('select', node.path)"
      >
        <IconGlyph name="folder" />
        <span class="document-directory-tree__name" :title="node.name">{{ node.name }}</span>
        <small>{{ node.documentCount }}</small>
      </button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { KnowledgeDirectoryNode } from '../api/knowledge'
import IconGlyph from './IconGlyph.vue'

const props = defineProps<{ nodes: KnowledgeDirectoryNode[]; currentPath: string }>()
const emit = defineEmits<{ select: [path: string] }>()
const expandedPaths = ref(new Set<string>())

const parentPaths = computed(() => {
  const paths = new Set<string>()
  for (const node of props.nodes) {
    directoryAncestors(node.path).forEach(path => paths.add(path))
  }
  return paths
})

const visibleNodes = computed(() => props.nodes.filter(node =>
  directoryAncestors(node.path).every(path => expandedPaths.value.has(path))))

watch(() => props.currentPath, path => {
  if (!path) return
  const next = new Set(expandedPaths.value)
  directoryAncestors(path).forEach(ancestor => next.add(ancestor))
  expandedPaths.value = next
}, { immediate: true })

function directoryAncestors(path: string): string[] {
  const parts = path.split('/').filter(Boolean)
  return parts.slice(0, -1).map((_, index) => parts.slice(0, index + 1).join('/'))
}

function hasChildren(path: string): boolean {
  return parentPaths.value.has(path)
}

function isExpanded(path: string): boolean {
  return expandedPaths.value.has(path)
}

function toggleNode(path: string): void {
  const next = new Set(expandedPaths.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  expandedPaths.value = next
}

function directoryDepth(path: string): number {
  return Math.max(0, path.split('/').filter(Boolean).length - 1)
}
</script>
