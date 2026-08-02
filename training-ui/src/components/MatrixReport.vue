<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({
  matrix: Object,
  matrixCards: Array,
  matrixReady: Boolean
})

function passRate(item) {
  if (!item?.total) return '0%'
  return `${Math.round((item.passed / item.total) * 100)}%`
}
</script>

<template>
  <section class="panel matrix-panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">{{ t('matrix.eyebrow') }}</p>
        <h2>{{ t('matrix.title') }}</h2>
      </div>
      <span class="pill" :class="{ ok: matrixReady }">
        {{ matrixReady
          ? t('matrix.pass', { passed: (matrix.totalResults ?? 0) - (matrix.nonPassResults ?? 0), total: matrix.totalResults ?? 0 })
          : t('matrix.needCheck') }}
      </span>
    </div>

    <div v-if="matrix?.available" class="matrix-grid">
      <article v-for="item in matrixCards" :key="item.label" class="matrix-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.data.passed }}/{{ item.data.total }}</strong>
        <em>{{ passRate(item.data) }}</em>
      </article>
    </div>
    <p v-else class="muted">{{ t('matrix.empty') }}</p>

    <footer v-if="matrix?.available" class="report-footer">
      <span>{{ t('matrix.reportSha') }}：{{ matrix.reportSha256 }}</span>
      <span>{{ t('matrix.officialTree') }}：{{ matrix.officialTreeAfterFileCount }} {{ t('matrix.files') }}</span>
    </footer>
  </section>
</template>
