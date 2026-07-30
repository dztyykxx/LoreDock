<template>
  <div class="form-field">
    <label :for="id">{{ label }}</label>
    <div class="form-field__control" :class="{ 'form-field__control--readonly': readonly }">
      <IconGlyph v-if="icon" :name="icon" />
      <textarea
        v-if="multiline"
        ref="control"
        :id="id"
        :name="name ?? id"
        :value="modelValue"
        :placeholder="placeholder"
        :readonly="readonly"
        :disabled="disabled"
        :required="required"
        :autofocus="autofocus"
        :aria-describedby="help ? `${id}-help` : undefined"
        @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      />
      <input
        v-else
        ref="control"
        :id="id"
        :name="name ?? id"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :autocomplete="autocomplete"
        :readonly="readonly"
        :disabled="disabled"
        :required="required"
        :autofocus="autofocus"
        :aria-describedby="help ? `${id}-help` : undefined"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      >
    </div>
    <p v-if="help" :id="`${id}-help`" class="form-field__help">{{ help }}</p>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import IconGlyph from './IconGlyph.vue'

const props = withDefaults(defineProps<{
  id: string
  label: string
  modelValue: string
  name?: string
  type?: string
  icon?: string
  help?: string
  placeholder?: string
  autocomplete?: string
  readonly?: boolean
  disabled?: boolean
  required?: boolean
  autofocus?: boolean
  multiline?: boolean
}>(), {
  name: undefined,
  type: 'text',
  icon: undefined,
  help: undefined,
  placeholder: undefined,
  autocomplete: undefined,
  readonly: false,
  disabled: false,
  required: false,
  autofocus: false,
  multiline: false,
})

defineEmits<{ 'update:modelValue': [value: string] }>()

const control = ref<HTMLInputElement | HTMLTextAreaElement | null>(null)

onMounted(async () => {
  if (!props.autofocus) {
    return
  }
  // 对话框是条件渲染的，只依赖 HTML autofocus 不能保证动态插入后聚焦，需在 DOM 就绪后明确转移键盘焦点。
  await nextTick()
  control.value?.focus()
})
</script>
