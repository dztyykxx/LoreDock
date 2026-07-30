<template>
  <fieldset class="scope-fields" :disabled="disabled">
    <legend>适用范围</legend>
    <label>
      <span>知识范围</span>
      <select data-testid="scope-type" :value="localValue.type" @change="changeType(($event.target as HTMLSelectElement).value as KnowledgeScopeType)">
        <option value="GLOBAL">通用业务知识</option>
        <option value="PROJECT">项目级</option>
        <option value="BRANCH">分支级</option>
      </select>
    </label>
    <label v-if="localValue.type !== 'GLOBAL'">
      <span>适用项目</span>
      <select data-testid="scope-project" :value="localValue.project ?? ''" @change="changeProject(($event.target as HTMLSelectElement).value)">
        <option value="" disabled>请选择项目</option>
        <option v-for="project in projects" :key="project.identifier" :value="project.identifier">{{ project.name }}</option>
      </select>
    </label>
    <label v-if="localValue.type === 'BRANCH'">
      <span>适用分支</span>
      <select data-testid="scope-branch" :value="localValue.branch ?? ''" @change="changeBranch(($event.target as HTMLSelectElement).value)">
        <option value="" disabled>请选择分支</option>
        <option v-for="branch in selectedBranches" :key="branch" :value="branch">{{ branch }}</option>
      </select>
    </label>
  </fieldset>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { KnowledgeScopeInput, KnowledgeScopeType } from '../api/knowledge'

const props = withDefaults(defineProps<{
  modelValue: KnowledgeScopeInput
  projects: Array<{ identifier: string; name: string; branches: string[] }>
  disabled?: boolean
}>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: KnowledgeScopeInput] }>()
const localValue = ref<KnowledgeScopeInput>({ ...props.modelValue })

watch(() => props.modelValue, value => { localValue.value = { ...value } }, { deep: true })
const selectedBranches = computed(() => props.projects.find(project => project.identifier === localValue.value.project)?.branches ?? [])

function update(value: KnowledgeScopeInput): void {
  localValue.value = value
  emit('update:modelValue', value)
}

function changeType(type: KnowledgeScopeType): void {
  if (type === 'GLOBAL') {
    update({ type, project: null, branch: null })
  } else if (type === 'PROJECT') {
    update({ type, project: localValue.value.project ?? null, branch: null })
  } else {
    update({ type, project: localValue.value.project ?? null, branch: localValue.value.branch ?? null })
  }
}

function changeProject(project: string): void {
  update({ ...localValue.value, project, branch: null })
}

function changeBranch(branch: string): void {
  update({ ...localValue.value, branch })
}
</script>
