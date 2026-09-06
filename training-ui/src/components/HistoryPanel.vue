<script setup>
import { useI18n } from 'vue-i18n'

const { t, tm, rt } = useI18n()

defineProps({
  historyFilters: Object,
  historyRows: Array,
  failedRetrainCount: Number,
  trainingBusy: Boolean,
  formatDate: Function,
  latestJudgement: Function
})

defineEmits(['retrain', 'export-md', 'export-csv', 'detail', 'load-session', 'copy-path', 'copy-cd', 'open-sandbox'])
</script>

<template>
  <section class="panel history-panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">{{ t('history.eyebrow') }}</p>
        <h2>{{ t('history.title') }}</h2>
      </div>
      <div class="button-row">
        <button :disabled="trainingBusy || !failedRetrainCount" type="button" @click="$emit('retrain')">
          {{ t('history.retrain', { count: failedRetrainCount || '' }) }}
        </button>
        <button :disabled="!historyRows.length" type="button" @click="$emit('export-md')">
          {{ t('history.exportMd') }}
        </button>
        <button :disabled="!historyRows.length" type="button" @click="$emit('export-csv')">
          {{ t('history.exportCsv') }}
        </button>
      </div>
    </div>

    <div class="history-filter">
      <label>
        <span>{{ t('history.type') }}</span>
        <select v-model="historyFilters.track">
          <option value="ALL">{{ t('history.all') }}</option>
          <option v-for="(label, value) in tm('history.trackOptions')" :key="value" :value="value">{{ rt(label) }}</option>
        </select>
      </label>
      <label>
        <span>{{ t('history.status') }}</span>
        <select v-model="historyFilters.status">
          <option value="ALL">{{ t('history.all') }}</option>
          <option v-for="(label, value) in tm('history.statusOptions')" :key="value" :value="value">{{ rt(label) }}</option>
        </select>
      </label>
      <label>
        <span>{{ t('history.result') }}</span>
        <select v-model="historyFilters.verdict">
          <option value="ALL">{{ t('history.all') }}</option>
          <option v-for="(label, value) in tm('history.verdictOptions')" :key="value" :value="value">{{ rt(label) }}</option>
        </select>
      </label>
      <label class="check-label">
        <input v-model="historyFilters.failedOnly" type="checkbox" />
        <span>{{ t('history.failedOnly') }}</span>
      </label>
    </div>

    <div v-if="historyRows.length" class="history-list">
      <article v-for="entry in historyRows" :key="entry.attempt.id" class="history-card">
        <div class="question-head">
          <span class="question-id">{{ entry.attempt.questionId }}</span>
          <span class="question-track">{{ entry.attempt.track }}</span>
        </div>
        <h3>{{ entry.attempt.title }}</h3>
        <p>{{ entry.attempt.topic }}</p>
        <div class="history-meta">
          <span>{{ t('attempt.status') }}：{{ entry.attempt.status }}</span>
          <span>{{ t('attempt.result') }}：{{ entry.attempt.verdict ?? '-' }}</span>
          <span>{{ t('attempt.started') }}：{{ formatDate(entry.attempt.startedAt) }}</span>
          <span>{{ t('attempt.submitted') }}：{{ formatDate(entry.attempt.submittedAt) }}</span>
          <span>{{ t('attempt.duration') }}：{{ entry.attempt.durationSeconds ?? '-' }} {{ t('attempt.durationUnit') }}</span>
        </div>

        <div v-if="entry.oralScore" class="oral-score-mini">
          <span>{{ t('history.oralScore', { total: entry.oralScore.total }) }}</span>
          <span>
            {{ t('history.dimAbbr', {
              correctness: entry.oralScore.correctness,
              structure: entry.oralScore.structure,
              projectEvidence: entry.oralScore.projectEvidence,
              tradeoff: entry.oralScore.tradeoff,
              factRestraint: entry.oralScore.factRestraint
            }) }}
          </span>
        </div>

        <p v-if="entry.attempt.notes" class="notes-line">{{ t('history.reviewNote', { notes: entry.attempt.notes }) }}</p>

        <div v-if="latestJudgement(entry)" class="judge-mini">
          <strong>{{ t('history.latestJudge', { status: latestJudgement(entry).status }) }}</strong>
          <span>
            {{ t('history.tests', { passed: latestJudgement(entry).passedCount ?? '-', failed: latestJudgement(entry).failedCount ?? '-' }) }}
          </span>
          <span>{{ t('history.durationMs', { ms: latestJudgement(entry).durationMillis ?? '-' }) }}</span>
          <span>{{ t('history.exit', { code: latestJudgement(entry).exitCode ?? '-' }) }}</span>
        </div>

        <div class="history-actions">
          <button type="button" @click="$emit('detail', entry)">{{ t('history.detail') }}</button>
          <button :disabled="trainingBusy" type="button" @click="$emit('load-session', entry)">{{ t('history.loadSession') }}</button>
          <button v-if="entry.attempt.sandboxPath" :disabled="trainingBusy" type="button"
            @click="$emit('copy-path', entry.attempt.sandboxPath)">{{ t('history.copyPath') }}</button>
          <button v-if="entry.attempt.sandboxPath" :disabled="trainingBusy" type="button"
            @click="$emit('copy-cd', entry.attempt.sandboxPath)">{{ t('history.copyCd') }}</button>
          <button v-if="entry.attempt.sandboxPath" :disabled="trainingBusy" type="button"
            @click="$emit('open-sandbox', entry.attempt)">{{ t('history.openSandbox') }}</button>
        </div>

        <p v-if="entry.attempt.sandboxPath" class="sandbox-line">{{ entry.attempt.sandboxPath }}</p>
      </article>
    </div>
    <p v-else class="muted">{{ t('history.empty') }}</p>
  </section>
</template>
