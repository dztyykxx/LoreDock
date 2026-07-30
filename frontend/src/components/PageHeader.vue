<template>
  <header class="page-header">
    <div>
      <p class="page-header__breadcrumb">{{ breadcrumb }}</p>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions"><slot name="actions" /></div>
  </header>
</template>

<script setup lang="ts">
defineProps<{ breadcrumb: string; title: string; description: string }>()
</script>
