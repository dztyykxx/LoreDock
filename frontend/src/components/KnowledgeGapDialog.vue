<template>
  <section class="knowledge-gap-dialog" role="dialog" aria-modal="true" aria-labelledby="knowledge-gap-title">
    <header>
      <div><p>帮助团队补齐上下文</p><h2 id="knowledge-gap-title">记录知识缺口</h2></div>
      <button type="button" aria-label="关闭知识缺口表单" @click="$emit('close')">×</button>
    </header>
    <div class="knowledge-gap-scope">
      <strong>确认固定范围</strong>
      <span>{{ snapshot.scope.projectIdentifier }}</span>
      <p>{{ userQuestion }}</p>
    </div>
    <div v-if="submitted" class="knowledge-gap-success" role="status">
      <IconGlyph name="circleCheck" /><strong>知识缺口已记录</strong><span>管理员可在反馈列表中确认并处理。</span>
    </div>
    <form v-else @submit.prevent="submit">
      <label for="knowledge-gap-type">反馈类型</label>
      <select id="knowledge-gap-type" v-model="type" :disabled="submitting">
        <option value="NO_ANSWER">没有得到答案</option>
        <option value="WRONG_ANSWER">回答可能有误</option>
        <option value="OUTDATED_KNOWLEDGE">知识可能已过期</option>
      </select>
      <label for="knowledge-gap-note">补充说明（可选）</label>
      <textarea id="knowledge-gap-note" v-model="note" maxlength="2000" :disabled="submitting" aria-describedby="knowledge-gap-help knowledge-gap-error" />
      <p id="knowledge-gap-help">反馈只记录本次问答的服务端范围、终态与来源，不会自动发布知识或触发索引。</p>
      <p v-if="error" id="knowledge-gap-error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="submitting || submitted">{{ submitting ? '正在提交…' : '提交知识缺口' }}</button>
    </form>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { KnowledgeGapType, QaApi, QaQuestion } from '../api/qa'
import IconGlyph from './IconGlyph.vue'

const props = defineProps<{
  api: QaApi
  snapshot: QaQuestion
  createIdempotencyKey?: () => string
}>()
const emit = defineEmits<{ close: []; submitted: [feedbackId: number] }>()
const type = ref<KnowledgeGapType>(recommendedType(props.snapshot))
const note = ref('')
const submitting = ref(false)
const submitted = ref(false)
const error = ref<string | null>(null)
let pendingKey: string | null = null

const userQuestion = computed(() => props.snapshot.messages.find(message => message.role === 'USER')?.content ?? '未保存问题正文')

async function submit(): Promise<void> {
  if (submitting.value || submitted.value) return
  pendingKey ??= props.createIdempotencyKey?.() ?? defaultIdempotencyKey()
  submitting.value = true
  error.value = null
  try {
    const feedback = await props.api.createKnowledgeGap(props.snapshot.scope.projectIdentifier, {
      idempotencyKey: pendingKey,
      branch: props.snapshot.scope.branch,
      type: type.value,
      questionId: props.snapshot.questionId,
      note: note.value.trim() || undefined,
    })
    submitted.value = true
    pendingKey = null
    emit('submitted', feedback.feedbackId)
  } catch (cause) {
    error.value = cause instanceof TypeError
      ? '提交结果未知，说明已保留；请重试以安全确认。'
      : cause instanceof Error ? cause.message : '反馈提交失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

function recommendedType(snapshot: QaQuestion): KnowledgeGapType {
  return snapshot.trustState === 'INSUFFICIENT_EVIDENCE' || snapshot.trustState === 'FAILED'
    ? 'NO_ANSWER'
    : snapshot.trustState === 'RELIABLE_ANSWER' ? 'WRONG_ANSWER' : 'OUTDATED_KNOWLEDGE'
}

function defaultIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `gap-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
</script>
