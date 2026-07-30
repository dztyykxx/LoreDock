<template>
  <section class="qa-recent" aria-labelledby="qa-recent-title">
    <div class="qa-recent__heading">
      <h2 id="qa-recent-title">最近问题</h2>
      <span>{{ items.length }}</span>
    </div>
    <p v-if="items.length === 0" class="qa-recent__empty">还没有提问记录</p>
    <button
      v-for="item in items"
      :key="item.questionId"
      :data-testid="`recent-question-${item.questionId}`"
      type="button"
      :class="{ 'qa-recent__item--active': item.questionId === selectedId }"
      :aria-current="item.questionId === selectedId ? 'true' : undefined"
      @click="$emit('select', item.questionId)"
    >
      <span>{{ questionText(item) }}</span>
      <small>{{ item.scope.branch }} · {{ formatTime(item.createdAt) }}</small>
    </button>
  </section>
</template>

<script setup lang="ts">
import type { QaQuestion } from '../api/qa'

defineProps<{ items: QaQuestion[]; selectedId: string | null }>()
defineEmits<{ select: [questionId: string] }>()

function questionText(item: QaQuestion): string {
  return item.messages.find(message => message.role === 'USER')?.content ?? '未命名问题'
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(value))
}
</script>
