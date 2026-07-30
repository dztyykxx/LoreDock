<template>
  <div class="tag-input">
    <label :for="id">{{ label }}</label>
    <div class="tag-input__control">
      <button v-for="tag in tags" :key="tag.toLocaleLowerCase()" type="button" :disabled="disabled" :aria-label="`删除标签 ${tag}`" @click="remove(tag)">
        {{ tag }} <span aria-hidden="true">×</span>
      </button>
      <input
        :id="id"
        v-model="draft"
        type="text"
        :disabled="disabled"
        :placeholder="tags.length ? '' : placeholder"
        @keydown.enter.prevent="add"
        @keydown.,.prevent="add"
        @keydown.backspace="removeLastWhenEmpty"
      >
    </div>
    <p v-if="help">{{ help }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  id: string
  label: string
  modelValue: string[]
  help?: string
  placeholder?: string
  disabled?: boolean
  max?: number
}>(), { help: '', placeholder: '输入后按 Enter 添加', disabled: false, max: 20 })
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const tags = ref([...props.modelValue])
const draft = ref('')

watch(() => props.modelValue, value => { tags.value = [...value] }, { deep: true })

function commit(value: string[]): void {
  tags.value = value
  emit('update:modelValue', value)
}

function add(): void {
  const value = draft.value.trim()
  draft.value = ''
  if (!value || tags.value.length >= props.max || tags.value.some(tag => tag.toLocaleLowerCase() === value.toLocaleLowerCase())) {
    return
  }
  commit([...tags.value, value])
}

function remove(tag: string): void {
  commit(tags.value.filter(value => value !== tag))
}

function removeLastWhenEmpty(): void {
  if (!draft.value && tags.value.length) {
    commit(tags.value.slice(0, -1))
  }
}
</script>
