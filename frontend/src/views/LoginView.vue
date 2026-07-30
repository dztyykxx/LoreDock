<template>
  <main class="login-page">
    <section class="login-brand-panel" aria-label="LoreDock 产品介绍">
      <div class="login-brand"><span class="login-logo">L</span><strong>LoreDock</strong></div>
      <div class="login-proposition">
        <span class="trust-label"><IconGlyph name="shield" />可信的项目上下文</span>
        <h1>让每个答案，都能回到证据。</h1>
        <p>集中管理业务知识、设计原因与代码快照，为开发者和本地 Agent 提供可检索、可引用的项目上下文。</p>
      </div>
      <ul class="login-trust-list">
        <li><span><IconGlyph name="check" /></span>项目与分支严格隔离</li>
        <li><span><IconGlyph name="check" /></span>回答始终附带来源</li>
        <li><span><IconGlyph name="check" /></span>证据不足时明确拒答</li>
      </ul>
    </section>

    <section class="login-form-panel">
      <form class="login-form" :aria-describedby="errorMessage ? 'login-error' : undefined" @submit.prevent="submit">
        <header>
          <h2>欢迎回来</h2>
          <p>登录后进入 LoreDock 项目知识空间</p>
        </header>
        <div class="login-fields">
          <FormField
            id="username"
            v-model="username"
            label="账号"
            icon="user"
            placeholder="请输入管理员或组内账号"
            autocomplete="username"
            required
            :disabled="busy"
          />
          <FormField
            id="password"
            v-model="password"
            label="密码"
            type="password"
            icon="lock"
            placeholder="请输入密码"
            autocomplete="current-password"
            required
            :disabled="busy"
          />
        </div>
        <p v-if="errorMessage" id="login-error" class="login-error" role="alert">{{ errorMessage }}</p>
        <AppButton class="login-submit" type="submit" icon="arrowRight" :busy="busy" busy-label="正在登录…">
          登录 LoreDock
        </AppButton>
        <NoticeBanner>管理员可维护与发布；组内账号仅浏览、搜索和问答。</NoticeBanner>
        <p class="login-security">内部系统 · 未授权访问将被拒绝</p>
      </form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../appContext'
import { resolveSafeRedirect } from '../router'
import AppButton from '../components/AppButton.vue'
import FormField from '../components/FormField.vue'
import IconGlyph from '../components/IconGlyph.vue'
import NoticeBanner from '../components/NoticeBanner.vue'

const session = useSession()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const errorMessage = ref('')

async function submit() {
  if (busy.value) {
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    await session.login({ username: username.value, password: password.value })
    await router.replace(resolveSafeRedirect(route.query.redirect))
  } catch {
    // 登录失败统一使用同一提示，既不泄露账号是否存在，也不把后端异常详情暴露到页面。
    password.value = ''
    errorMessage.value = '账号或密码不正确，请检查后重试。'
  } finally {
    busy.value = false
  }
}
</script>
