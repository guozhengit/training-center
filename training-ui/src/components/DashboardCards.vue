<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({
  trackCards: Array,
  totalQuestions: Number,
  stats: Object
})
</script>

<template>
  <section class="grid cards">
    <article v-for="card in trackCards" :key="card.label" class="metric-card" :class="card.tone">
      <span>{{ card.label }}</span>
      <strong>{{ card.value }}</strong>
    </article>
    <article class="metric-card dark">
      <span>{{ t('dashboard.totalQuestions') }}</span>
      <strong>{{ totalQuestions }}</strong>
    </article>
  </section>

  <section class="grid cards training-stats">
    <article class="metric-card">
      <span>{{ t('dashboard.sessions') }}</span>
      <strong>{{ stats?.sessions ?? 0 }}</strong>
    </article>
    <article class="metric-card">
      <span>{{ t('dashboard.finishedAttempts') }}</span>
      <strong>{{ stats?.finishedAttempts ?? 0 }}</strong>
    </article>
    <article class="metric-card">
      <span>{{ t('dashboard.dueReviews') }}</span>
      <strong>{{ stats?.dueReviews ?? 0 }}</strong>
    </article>
    <article class="metric-card">
      <span>{{ t('dashboard.avgOralScore') }}</span>
      <strong>{{ (stats?.averageOralScore ?? 0).toFixed(1) }}</strong>
    </article>
  </section>
</template>
