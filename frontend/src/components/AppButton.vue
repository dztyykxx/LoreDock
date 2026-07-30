<template>
  <button
    class="app-button"
    :class="`app-button--${variant}`"
    :type="type"
    :disabled="disabled || busy"
    :aria-busy="busy ? 'true' : 'false'"
  >
    <IconGlyph v-if="icon" :name="icon" />
    <span>{{ busy ? busyLabel : undefined }}<slot v-if="!busy" /></span>
  </button>
</template>

<script setup lang="ts">
import IconGlyph from './IconGlyph.vue'

withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'danger'
  type?: 'button' | 'submit'
  icon?: string
  disabled?: boolean
  busy?: boolean
  busyLabel?: string
}>(), {
  variant: 'primary',
  type: 'button',
  icon: undefined,
  disabled: false,
  busy: false,
  busyLabel: '处理中…',
})
</script>
