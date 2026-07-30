<template>
  <div v-if="open" class="dialog-backdrop" @click.self="cancel">
    <section ref="dialog" role="dialog" aria-modal="true" :aria-labelledby="titleId" class="app-dialog app-dialog--small" @keydown.esc="cancel">
      <header><div><h2 :id="titleId">{{ title }}</h2><p>{{ message }}</p></div></header>
      <footer>
        <AppButton data-testid="confirm-dialog-cancel" variant="secondary" :disabled="busy" @click="cancel">取消</AppButton>
        <AppButton
          data-testid="confirm-dialog-submit"
          :variant="danger ? 'danger' : 'primary'"
          :busy="busy"
          busy-label="正在处理…"
          @click="$emit('confirm')"
        >{{ confirmLabel }}</AppButton>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppButton from './AppButton.vue'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  danger?: boolean
  busy?: boolean
}>(), { confirmLabel: '确认', danger: false, busy: false })
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const titleId = computed(() => `confirm-${props.title.replace(/\s+/g, '-')}`)
const dialog = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null

watch(() => props.open, async open => {
  if (open) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    dialog.value?.querySelector<HTMLButtonElement>('button')?.focus()
  } else {
    previousFocus?.focus()
    previousFocus = null
  }
}, { immediate: true })

function cancel(): void {
  if (!props.busy) {
    emit('cancel')
  }
}

onBeforeUnmount(() => previousFocus?.focus())
</script>
