<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({
  detail: Object,
  formatDate: Function
})

defineEmits(['close'])
</script>

<template>
  <div v-if="detail" class="detail-backdrop" @click.self="$emit('close')">
    <section class="detail-drawer">
      <div class="panel-title">
        <div>
          <p class="eyebrow">{{ t('detail.eyebrow') }}</p>
          <h2>{{ t('detail.title', { id: detail.attempt.questionId }) }}</h2>
        </div>
        <button type="button" @click="$emit('close')">{{ t('detail.close') }}</button>
      </div>

      <div class="detail-summary">
        <span>{{ t('detail.type') }}：{{ detail.attempt.track }}</span>
        <span>{{ t('detail.status') }}：{{ detail.attempt.status }}</span>
        <span>{{ t('detail.result') }}：{{ detail.attempt.verdict ?? '-' }}</span>
        <span>{{ t('detail.started') }}：{{ formatDate(detail.attempt.startedAt) }}</span>
        <span>{{ t('detail.submitted') }}：{{ formatDate(detail.attempt.submittedAt) }}</span>
      </div>

      <h3>{{ detail.attempt.title }}</h3>
      <p class="muted">{{ detail.attempt.topic }}</p>

      <p v-if="detail.attempt.sandboxPath" class="sandbox-line">{{ detail.attempt.sandboxPath }}</p>

      <div v-if="detail.oralScore" class="detail-block oral-score-detail">
        <h4>{{ t('detail.oralScore', { score: detail.oralScore.total }) }}</h4>
        <p class="muted">
          {{ t('detail.dimNames', {
            correctness: detail.oralScore.correctness,
            structure: detail.oralScore.structure,
            projectEvidence: detail.oralScore.projectEvidence,
            tradeoff: detail.oralScore.tradeoff,
            factRestraint: detail.oralScore.factRestraint
          }) }}
        </p>
      </div>

      <div v-if="detail.attempt.notes" class="detail-block">
        <h4>{{ t('detail.notes') }}</h4>
        <p>{{ detail.attempt.notes }}</p>
      </div>

      <div v-if="detail.attempt.improvedAnswer" class="detail-block">
        <h4>{{ t('detail.improvedAnswer') }}</h4>
        <pre class="improved-answer">{{ detail.attempt.improvedAnswer }}</pre>
      </div>

      <div v-if="detail.judgements.length" class="judgement-timeline">
        <article v-for="judgement in detail.judgements" :key="judgement.id" class="judgement-detail">
          <div class="question-head">
            <span class="question-id">#{{ judgement.sequenceNo }}</span>
            <span class="question-track">{{ judgement.status }}</span>
          </div>
          <div class="history-meta">
            <span>{{ t('detail.startedAt') }}：{{ formatDate(judgement.startedAt) }}</span>
            <span>{{ t('detail.finishedAt') }}：{{ formatDate(judgement.finishedAt) }}</span>
            <span>{{ t('detail.passedFailed') }}：{{ judgement.passedCount ?? '-' }}/{{ judgement.failedCount ?? '-' }}</span>
            <span>{{ t('detail.duration') }}：{{ judgement.durationMillis ?? '-' }} ms</span>
            <span>{{ t('detail.exit') }}：{{ judgement.exitCode ?? '-' }}</span>
          </div>
          <pre v-if="judgement.stderrExcerpt">{{ judgement.stderrExcerpt }}</pre>
          <pre v-if="judgement.stdoutExcerpt">{{ judgement.stdoutExcerpt }}</pre>
        </article>
      </div>
      <p v-else class="muted">{{ t('detail.noJudge') }}</p>
    </section>
  </div>
</template>
