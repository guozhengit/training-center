<script setup>
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
      <span>题库总量</span>
      <strong>{{ totalQuestions }}</strong>
    </article>
  </section>

  <section class="grid cards training-stats">
    <article class="metric-card">
      <span>训练会话</span>
      <strong>{{ stats?.sessions ?? 0 }}</strong>
    </article>
    <article class="metric-card">
      <span>完成练习</span>
      <strong>{{ stats?.finishedAttempts ?? 0 }}</strong>
    </article>
    <article class="metric-card">
      <span>待复习错题</span>
      <strong>{{ stats?.dueReviews ?? 0 }}</strong>
    </article>
    <article class="metric-card">
      <span>口述均分</span>
      <strong>{{ (stats?.averageOralScore ?? 0).toFixed(1) }}</strong>
    </article>
  </section>
</template>
