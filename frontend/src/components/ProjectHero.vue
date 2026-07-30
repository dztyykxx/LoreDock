<template>
  <section class="project-hero">
    <div class="project-hero__identity">
      <span class="project-hero__icon"><IconGlyph name="network" /></span>
      <div>
        <div class="project-hero__title"><h2>{{ name }}</h2><StatusBadge :status="status" /></div>
        <p>{{ identifier }} · {{ technologyStack }}</p>
      </div>
    </div>
    <div v-if="$slots.actions" class="project-hero__actions"><slot name="actions" /></div>
  </section>
</template>

<script setup lang="ts">
import type { ProjectStatus } from '../api/types'
import IconGlyph from './IconGlyph.vue'
import StatusBadge from './StatusBadge.vue'

withDefaults(defineProps<{
  name: string
  identifier: string
  technologyStack: string
  status?: ProjectStatus
}>(), { status: 'ENABLED' })
</script>
