<template>
  <svg
    class="icon-glyph"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="path in paths" :key="path" :d="path" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ name: string }>()

const iconPaths: Record<string, string[]> = {
  arrowLeft: ['M19 12H5', 'm11 18-6-6 6-6'],
  arrowRight: ['M5 12h14', 'm13 6 6 6-6 6'],
  book: ['M4 19.5A2.5 2.5 0 0 1 6.5 17H20', 'M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z'],
  branch: ['M6 3v12', 'M18 9a6 6 0 0 1-6 6H6', 'M15 6l3 3 3-3', 'M3 18l3-3 3 3'],
  check: ['m5 12 4 4L19 6'],
  chevronDown: ['m6 9 6 6 6-6'],
  chevronRight: ['m9 18 6-6-6-6'],
  circleCheck: ['M22 11.1V12a10 10 0 1 1-5.9-9.1', 'm9 11 3 3L22 4'],
  file: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z', 'M14 2v6h6', 'M8 13h8', 'M8 17h8'],
  folder: ['M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z'],
  lock: ['M6 10V8a6 6 0 0 1 12 0v2', 'M5 10h14v11H5Z'],
  logout: ['M10 17l5-5-5-5', 'M15 12H3', 'M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4'],
  message: ['M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z'],
  more: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  network: ['M9 12H5a2 2 0 0 0-2 2v5h6v-7Z', 'M21 12h-6v7h6v-7Z', 'M15 2H9v6h6V2Z', 'M12 8v4', 'M6 12v-1h12v1'],
  plus: ['M12 5v14', 'M5 12h14'],
  save: ['M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z', 'M17 21v-8H7v8', 'M7 3v5h8'],
  search: ['m21 21-4.3-4.3', 'M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z'],
  settings: ['M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z', 'M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.12 3.67-.09-.03a1.7 1.7 0 0 0-1.84.18l-.08.05a1.7 1.7 0 0 0-.82 1.56V22H9.15v-.1a1.7 1.7 0 0 0-.82-1.56l-.08-.05a1.7 1.7 0 0 0-1.84-.18l-.09.03L4.2 16.47l.06-.06A1.7 1.7 0 0 0 4.6 15l-.03-.1A1.7 1.7 0 0 0 3.15 14H3V9h.15a1.7 1.7 0 0 0 1.42-.9l.03-.1a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.12-3.67.09.03a1.7 1.7 0 0 0 1.84-.18l.08-.05A1.7 1.7 0 0 0 9.15.63V.5h5.7v.13a1.7 1.7 0 0 0 .82 1.56l.08.05a1.7 1.7 0 0 0 1.84.18l.09-.03 2.12 3.67-.06.06A1.7 1.7 0 0 0 19.4 8l.03.1a1.7 1.7 0 0 0 1.42.9H21v5h-.15a1.7 1.7 0 0 0-1.42.9Z'],
  shield: ['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z', 'm9 12 2 2 4-4'],
  user: ['M20 21a8 8 0 0 0-16 0', 'M12 13a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z'],
  warning: ['M10.3 2.9 1.8 17a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 2.9a2 2 0 0 0-3.4 0Z', 'M12 9v4', 'M12 17h.01'],
}

const paths = computed(() => iconPaths[props.name] ?? iconPaths.more)
</script>
