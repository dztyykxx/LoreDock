<template>
  <RouterLink class="project-card" :to="target">
    <div class="project-card__heading">
      <span class="project-card__icon"><IconGlyph name="network" /></span>
      <IconGlyph name="chevronRight" />
    </div>
    <h3>{{ project.name }}</h3>
    <code>{{ project.identifier }}</code>
    <p>{{ project.technologyStack }}<template v-if="project.description"> · {{ project.description }}</template></p>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { ProjectSummary, WebRole } from '../api/types'
import IconGlyph from './IconGlyph.vue'

const props = defineProps<{
  project: ProjectSummary
  role: WebRole
}>()

const target = computed(() => `/projects/${props.project.identifier}`)
</script>
