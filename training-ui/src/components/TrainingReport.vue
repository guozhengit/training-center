<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { Chart, registerables } from 'chart.js'

const { t } = useI18n()

Chart.register(...registerables)

const props = defineProps({
  stats: Object
})

const donutCanvas = ref(null)
const trendCanvas = ref(null)
let donutChart = null
let trendChart = null

function buildDonut() {
  if (!donutCanvas.value || !props.stats) return
  const passed = props.stats.passedAttempts ?? 0
  const failed = props.stats.failedAttempts ?? 0
  const pending = (props.stats.finishedAttempts ?? 0) - passed - failed

  if (donutChart) donutChart.destroy()
  donutChart = new Chart(donutCanvas.value, {
    type: 'doughnut',
    data: {
      labels: [t('report.passed'), t('report.failed'), t('report.pending')],
      datasets: [{
        data: [passed, failed, Math.max(0, pending)],
        backgroundColor: ['#10b981', '#ef4444', '#e2e8f0'],
        borderWidth: 0
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '60%',
      plugins: {
        legend: { position: 'bottom', labels: { padding: 16 } }
      }
    }
  })
}

function buildTrend() {
  if (!trendCanvas.value || !props.stats?.recentAttempts?.length) return
  const attempts = props.stats.recentAttempts.slice().reverse()
  const labels = attempts.map(a => a.questionId ?? a.id?.slice(0, 6))
  const durations = attempts.map(a => a.durationSeconds ?? 0)
  const colors = attempts.map(a =>
    a.verdict === 'PASSED' ? '#10b981' : a.verdict === 'FAILED' ? '#ef4444' : '#94a3b8'
  )

  if (trendChart) trendChart.destroy()
  trendChart = new Chart(trendCanvas.value, {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        label: t('report.durationLabel'),
        data: durations,
        backgroundColor: colors,
        borderRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        y: { beginAtZero: true, title: { display: true, text: t('report.seconds') } },
        x: { ticks: { maxRotation: 45 } }
      },
      plugins: {
        legend: { display: false }
      }
    }
  })
}

function rebuild() {
  nextTick(() => {
    buildDonut()
    buildTrend()
  })
}

watch(() => props.stats, rebuild, { deep: true })
onMounted(rebuild)
onUnmounted(() => {
  if (donutChart) donutChart.destroy()
  if (trendChart) trendChart.destroy()
})
</script>

<template>
  <section v-if="stats" class="report-panel">
    <h2 class="panel-title">{{ t('report.title') }}</h2>
    <div class="report-grid">
      <div class="report-card">
        <h4>{{ t('report.distribution') }}</h4>
        <div class="chart-box">
          <canvas ref="donutCanvas" />
        </div>
        <div class="report-summary">
          <span>{{ t('report.totalPractice') }} {{ stats.totalAttempts ?? 0 }}</span>
          <span>{{ t('report.passRate') }} {{ stats.finishedAttempts ? Math.round((stats.passedAttempts ?? 0) / stats.finishedAttempts * 100) : 0 }}%</span>
          <span>{{ t('report.dueReview') }} {{ stats.dueReviewCount ?? 0 }}</span>
        </div>
      </div>
      <div class="report-card">
        <h4>{{ t('report.trend') }}</h4>
        <div class="chart-box">
          <canvas ref="trendCanvas" />
        </div>
        <div class="report-summary">
          <span>{{ t('report.avgDuration') }} {{ stats.avgDurationSeconds ? Math.round(stats.avgDurationSeconds) : '-' }} {{ t('report.seconds') }}</span>
          <span>{{ t('report.avgOralScore') }} {{ stats.avgOralScore ? stats.avgOralScore.toFixed(1) : '-' }}/10</span>
        </div>
      </div>
    </div>
  </section>
</template>
