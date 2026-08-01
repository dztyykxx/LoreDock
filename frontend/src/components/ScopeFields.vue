<template>
  <fieldset class="scope-fields" :disabled="disabled">
    <legend>适用范围</legend>
    <label>
      <span>知识范围</span>
      <select data-testid="scope-type" :value="localValue.type" @change="changeType(($event.target as HTMLSelectElement).value as 'GLOBAL' | 'PROJECT')">
        <option value="GLOBAL">通用业务知识</option>
        <option value="PROJECT">项目级</option>
      </select>
    </label>
    <label v-if="localValue.type !== 'GLOBAL'">
      <span>适用项目</span>
      <select data-testid="scope-project" :value="localValue.project ?? ''" @change="changeProject(($event.target as HTMLSelectElement).value)">
        <option value="" disabled>请选择项目</option>
        <option v-for="project in projects" :key="project.identifier" :value="project.identifier">{{ project.name }}</option>
      </select>
    </label>
  </fieldset>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { KnowledgeScopeInput } from '../api/knowledge'

const props = withDefaults(defineProps<{
  modelValue: KnowledgeScopeInput
  projects: Array<{ identifier: string; name: string }>
  disabled?: boolean
}>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: KnowledgeScopeInput] }>()
const localValue = ref<KnowledgeScopeInput>({ ...props.modelValue })

watch(() => props.modelValue, value => { localValue.value = { ...value } }, { deep: true })

function update(value: KnowledgeScopeInput): void {
  localValue.value = value
  emit('update:modelValue', value)
}

function changeType(type: 'GLOBAL' | 'PROJECT'): void {
  if (type === 'GLOBAL') {
    update({ type, project: null, branch: null })
  } else {
    update({ type: 'PROJECT', project: localValue.value.project ?? null, branch: null })
  }
}

function changeProject(project: string): void {
  update({ type: 'PROJECT', project, branch: null })
}

</script>
