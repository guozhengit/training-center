<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({
  sessionForm: Object,
  trainingTracks: Array,
  trainingBusy: Boolean,
  activeSession: Object,
  activeSessionProgress: String
})

defineEmits(['create'])
</script>

<template>
  <section class="panel training-panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">{{ t('session.workspace') }}</p>
        <h2>{{ t('session.title') }}</h2>
      </div>
      <span v-if="activeSession" class="pill ok">{{ t('session.progress', { progress: activeSessionProgress }) }}</span>
    </div>

    <div class="training-form">
      <label>
        <span>{{ t('session.trackLabel') }}</span>
        <select v-model="sessionForm.track">
          <option v-for="track in trainingTracks" :key="track.value" :value="track.value">
            {{ track.label }}
          </option>
        </select>
      </label>
      <label>
        <span>{{ t('session.count') }}</span>
        <input v-model.number="sessionForm.count" min="1" max="20" type="number" />
      </label>
      <label class="check-label">
        <input v-model="sessionForm.prioritizeWrongAnswers" type="checkbox" />
        <span>{{ t('session.prioritizeWrong') }}</span>
      </label>
      <label class="check-label">
        <input v-model="sessionForm.dueReviewOnly" type="checkbox" />
        <span>{{ t('session.dueReviewOnly') }}</span>
      </label>
      <button :disabled="trainingBusy" type="button" @click="$emit('create')">
        {{ trainingBusy ? t('session.busy') : t('session.start') }}
      </button>
    </div>
  </section>
</template>
