<script setup>
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
        <p class="eyebrow">Task21 Matrix</p>
        <h2>Starter 全量验证</h2>
      </div>
      <span class="pill" :class="{ ok: matrixReady }">
        {{ matrixReady ? '360/360 PASS' : '需要检查' }}
      </span>
    </div>

    <div v-if="matrix?.available" class="matrix-grid">
      <article v-for="item in matrixCards" :key="item.label" class="matrix-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.data.passed }}/{{ item.data.total }}</strong>
        <em>{{ passRate(item.data) }}</em>
      </article>
    </div>
    <p v-else class="muted">还没有找到 starter-matrix-report.json。</p>

    <footer v-if="matrix?.available" class="report-footer">
      <span>报告 SHA-256：{{ matrix.reportSha256 }}</span>
      <span>官方题树：{{ matrix.officialTreeAfterFileCount }} files</span>
    </footer>
  </section>
</template>
