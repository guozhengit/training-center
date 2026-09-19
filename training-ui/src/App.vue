<script setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale, availableLocales } from './i18n'
import { useDashboard } from './composables/useDashboard'
import { useTraining } from './composables/useTraining'
import { useHistory } from './composables/useHistory'
import { useExport } from './composables/useExport'

import Workbench from './components/Workbench.vue'
import HistoryPanel from './components/HistoryPanel.vue'
import HistoryDrawer from './components/HistoryDrawer.vue'
import TrainingReport from './components/TrainingReport.vue'
import MatrixReport from './components/MatrixReport.vue'

const { t, locale } = useI18n()
const dashboard = useDashboard()
const training = useTraining(dashboard)
const history = useHistory(dashboard, training)
const exporter = useExport(dashboard, history, training)

const view = ref('bench')
const startingQuestion = ref(false)

async function startQuestionTraining(question) {
  startingQuestion.value = true
  try {
    await training.createSessionFromQuestionIds(question.track, [question.id])
  } finally {
    startingQuestion.value = false
  }
}

onMounted(() => {
  dashboard.loadDashboard()
  if (training.activeSession.value) {
    training.initSubmitForms(training.activeSession.value)
  }
})
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <strong>算法训练台</strong>
        <span>Algorithm Training Bench</span>
      </div>

      <nav class="views">
        <button type="button" :class="{ active: view === 'bench' }" @click="view = 'bench'">训练台</button>
        <button type="button" :class="{ active: view === 'history' }" @click="view = 'history'">历史</button>
        <button type="button" :class="{ active: view === 'stats' }" @click="view = 'stats'">统计</button>
      </nav>

      <div class="topbar-right">
        <div class="status" :class="{ ok: dashboard.health.value?.status === 'UP' }">
          <span class="dot" />
          {{ dashboard.health.value?.status || t('app.statusChecking') }}
        </div>
        <select class="locale" :value="locale" @change="setLocale($event.target.value)">
          <option v-for="loc in availableLocales" :key="loc.value" :value="loc.value">{{ loc.label }}</option>
        </select>
      </div>
    </header>

    <p v-if="dashboard.errors.health" class="banner err">{{ dashboard.errors.health }}</p>
    <p v-if="training.trainingMessage.value" class="banner ok">{{ training.trainingMessage.value }}</p>

    <main class="body">
      <Workbench
        v-if="view === 'bench'"
        :dashboard="dashboard"
        :training="training"
        :starting="startingQuestion"
        @start="startQuestionTraining"
      />

      <div v-else-if="view === 'history'" class="panel-view">
        <HistoryPanel
          :history-filters="history.historyFilters.value"
          :history-rows="history.filteredHistoryRows.value"
          :failed-retrain-count="history.failedRetrainRows.value.length"
          :training-busy="training.trainingBusy.value"
          :format-date="training.formatDate"
          :latest-judgement="history.latestJudgement"
          @retrain="history.createFailedRetrainSession"
          @export-md="exporter.exportHistoryMarkdown"
          @export-csv="exporter.exportHistoryCsv"
          @detail="history.showHistoryDetail"
          @load-session="training.loadSessionFromHistory"
          @copy-path="exporter.copySandboxPath"
          @copy-cd="exporter.copyCdCommand"
          @open-sandbox="training.openSandbox"
        />
      </div>

      <div v-else class="panel-view">
        <TrainingReport :stats="dashboard.stats.value" />
        <MatrixReport
          :matrix="dashboard.matrix.value"
          :matrix-cards="dashboard.matrixCards.value"
          :matrix-ready="dashboard.matrixReady.value"
        />
      </div>
    </main>

    <HistoryDrawer
      :detail="history.activeHistoryDetail.value"
      :format-date="training.formatDate"
      @close="history.closeHistoryDetail"
    />
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 12px 22px;
  background: #fff;
  border-bottom: 1px solid #e2e8f2;
}

.brand {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand strong {
  font-size: 15px;
  font-weight: 600;
  color: #182235;
}

.brand span {
  font-size: 11px;
  color: #94a3b8;
  letter-spacing: 0.03em;
}

.views {
  display: flex;
  gap: 4px;
}

.views button {
  padding: 6px 14px;
  border-radius: 999px;
  background: #f1f5fb;
  color: #475569;
  font-size: 13px;
  font-weight: 500;
  box-shadow: none;
}

.views button.active {
  background: #2563eb;
  color: #fff;
}

.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #64748b;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
}

.status.ok .dot {
  background: #2f7a3d;
}

.locale {
  padding: 6px 8px;
  border-radius: 9px;
  font-size: 12px;
}

.banner {
  margin: 0;
  padding: 8px 22px;
  font-size: 12px;
}

.banner.err {
  background: #fdeaea;
  color: #a32d2d;
}

.banner.ok {
  background: #e7f6e9;
  color: #2f7a3d;
}

.body {
  flex: 1;
  padding: 14px 22px 22px;
  min-height: 0;
}

.panel-view {
  width: min(1180px, 100%);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
