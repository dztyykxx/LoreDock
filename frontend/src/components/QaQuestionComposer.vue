<template>
  <form class="qa-composer" @submit.prevent="submit">
    <label for="qa-question-input">向当前项目提问</label>
    <div class="qa-composer__control">
      <textarea
        id="qa-question-input"
        v-model="question"
        rows="2"
        maxlength="2000"
        placeholder="继续追问当前项目…"
        :disabled="busy"
        :aria-invalid="error ? 'true' : 'false'"
        :aria-describedby="error ? 'qa-question-help qa-question-error' : 'qa-question-help'"
        @keydown.enter.exact.prevent="submit"
      />
      <button type="submit" :disabled="busy || !question.trim()" :aria-label="busy ? '正在提交问题' : '提交问题'">
        <IconGlyph name="arrowRight" />
      </button>
    </div>
    <p id="qa-question-help">回答只基于当前项目与已发布知识；证据不足时会明确拒答。Shift + Enter 换行。</p>
    <p v-if="error" id="qa-question-error" role="alert">{{ error }}</p>
  </form>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import IconGlyph from './IconGlyph.vue'

defineProps<{ busy: boolean; error?: string | null }>()
const emit = defineEmits<{ submit: [question: string] }>()
const question = ref('')

function submit(): void {
  const value = question.value.trim()
  if (!value) return
  emit('submit', value)
}

function clear(): void {
  question.value = ''
}

defineExpose({ clear })
</script>
