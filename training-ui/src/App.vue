<script setup>
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale, availableLocales } from './i18n'
import { useDashboard } from './composables/useDashboard'
import { useTraining } from './composables/useTraining'
import { useHistory } from './composables/useHistory'
import { useExport } from './composables/useExport'

import DashboardCards from './components/DashboardCards.vue'
import TrainingReport from './components/TrainingReport.vue'
import SessionCreator from './components/SessionCreator.vue'
import ActiveSession from './components/ActiveSession.vue'
import HistoryPanel from './components/HistoryPanel.vue'
import HistoryDrawer from './components/HistoryDrawer.vue'
import MatrixReport from './components/MatrixReport.vue'
import QuestionBrowser from './components/QuestionBrowser.vue'

const { t, locale } = useI18n()
const dashboard = useDashboard()
const training = useTraining(dashboard)
const history = useHistory(dashboard, training)
const exporter = useExport(dashboard, history, training)

onMounted(() => {
  dashboard.loadDashboard()
  if (training.activeSession.value) {
    training.initSubmitForms(training.activeSession.value)
  }
})
</script>

<template>
  <main class="page-shell">
    <section class="hero">
      <div>
        <p class="eyebrow">{{ t('app.eyebrow') }}</p>
        <h1>{{ t('app.title') }}</h1>
        <p class="hero-text">{{ t('app.heroText') }}</p>
      </div>
      <div class="hero-actions">
        <select class="locale-switcher" :value="locale" @change="setLocale($event.target.value)">
          <option v-for="loc in availableLocales" :key="loc.value" :value="loc.value">{{ loc.label }}</option>
        </select>
        <div class="status-card" :class="{ ok: dashboard.health.value?.status === 'UP' }">
          <span class="status-dot"></span>
          <div>
            <strong>{{ dashboard.health.value?.status || t('app.statusChecking') }}</strong>
            <small>{{ dashboard.health.value?.matrixReportAvailable ? t('app.matrixLoaded') : t('app.matrixWaiting') }}</small>
          </div>
        </div>
      </div>
    </section>

    <p v-if="dashboard.error.value" class="error-box">{{ dashboard.error.value }}</p>
    <div v-if="dashboard.errorList.value.length" class="error-list">
      <p v-for="item in dashboard.errorList.value" :key="item.key" class="error-box error-item">
        <span>[{{ item.key }}] {{ item.msg }}</span>
        <button type="button" class="error-dismiss" @click="dashboard.dismissError(item.key)">×</button>
      </p>
    </div>
    <p v-if="training.trainingMessage.value" class="success-box">{{ training.trainingMessage.value }}</p>
    <p v-if="dashboard.loading.value" class="loading-box">正在加载训练数据...</p>

    <template v-else>
      <DashboardCards
        :track-cards="dashboard.trackCards.value"
        :total-questions="dashboard.catalog.value?.totalQuestions ?? 0"
        :stats="dashboard.stats.value"
      />

      <TrainingReport :stats="dashboard.stats.value" />

      <SessionCreator
        :session-form="training.sessionForm.value"
        :training-tracks="training.trainingTracks"
        :training-busy="training.trainingBusy.value"
        :active-session="training.activeSession.value"
        :active-session-progress="training.activeSessionProgress.value"
        @create="training.createTrainingSession"
      />

      <ActiveSession
        :active-session="training.activeSession.value"
        :submit-forms="training.submitForms.value"
        :judge-results="training.judgeResults.value"
        :training-busy="training.trainingBusy.value"
        :score-dimensions="training.scoreDimensions"
        @judge="training.judgeAttempt"
        @submit="training.submitAttempt"
        @open-sandbox="training.openSandbox"
      />

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

      <HistoryDrawer
        :detail="history.activeHistoryDetail.value"
        :format-date="training.formatDate"
        @close="history.closeHistoryDetail"
      />

      <MatrixReport
        :matrix="dashboard.matrix.value"
        :matrix-cards="dashboard.matrixCards.value"
        :matrix-ready="dashboard.matrixReady.value"
      />

      <QuestionBrowser
        :questions="dashboard.questions.value"
        :tracks="dashboard.tracks"
        :selected-track="dashboard.selectedTrack.value"
        @update:selected-track="dashboard.selectedTrack.value = $event"
        @load="dashboard.loadQuestions"
      />
    </template>
  </main>
</template>
