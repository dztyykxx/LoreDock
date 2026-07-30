<template>
  <div class="notice-banner" :class="`notice-banner--${tone}`" role="note">
    <IconGlyph :name="tone === 'warning' ? 'warning' : 'shield'" />
    <span><slot /></span>
  </div>
</template>

<script setup lang="ts">
import IconGlyph from './IconGlyph.vue'

withDefaults(defineProps<{ tone?: 'neutral' | 'warning' }>(), { tone: 'neutral' })
</script>
