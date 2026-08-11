<template>
  <section class="qa-recent" aria-labelledby="qa-recent-title">
    <div class="qa-recent__heading">
      <h2 id="qa-recent-title">最近会话</h2>
      <span>{{ items.length }}</span>
    </div>
    <p v-if="items.length === 0" class="qa-recent__empty">还没有提问记录</p>
    <button
      v-for="item in items"
      :key="itemId(item)"
      :data-testid="itemTestId(item)"
      type="button"
      :class="{ 'qa-recent__item--active': itemId(item) === selectedId }"
      :aria-current="itemId(item) === selectedId ? 'true' : undefined"
      @click="$emit('select', itemId(item))"
    >
      <span :title="itemText(item)">{{ itemText(item) }}</span>
      <small>{{ formatTime(itemTime(item)) }}</small>
      <em v-if="itemScope(item)" class="qa-recent__scope">{{ itemScope(item) }}</em>
    </button>
  </section>
</template>

<script setup lang="ts">
import { scopeLabel, type QaConversationSummary, type QaQuestion } from '../api/qa'

defineProps<{ items: Array<QaConversationSummary | QaQuestion>; selectedId: number | null }>()
defineEmits<{ select: [itemId: number] }>()

function isConversation(item: QaConversationSummary | QaQuestion): item is QaConversationSummary {
  return 'title' in item
}

/** @returns 会话检索范围标注；旧问题列表（无 scope 字段）不标注，保持兼容 */
function itemScope(item: QaConversationSummary | QaQuestion): string | null {
  return isConversation(item) && 'scope' in item ? scopeLabel(item) : null
}

function itemId(item: QaConversationSummary | QaQuestion): number {
  return isConversation(item) ? item.conversationId : item.questionId
}

function itemTestId(item: QaConversationSummary | QaQuestion): string {
  return isConversation(item) ? `recent-conversation-${item.conversationId}` : `recent-question-${item.questionId}`
}

function itemText(item: QaConversationSummary | QaQuestion): string {
  return isConversation(item)
    ? item.title
    : item.messages.find(message => message.role === 'USER')?.content ?? '未命名问题'
}

function itemTime(item: QaConversationSummary | QaQuestion): string {
  return isConversation(item) ? item.lastQuestionAt : item.createdAt
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(value))
}
</script>
