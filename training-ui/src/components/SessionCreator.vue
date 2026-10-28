<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps({
  sessionForm: Object,
  trainingTracks: Array,
  trainingBusy: Boolean,
  activeSession: Object,
  activeSessionProgress: String,
  // Narrow-column mode: used inside the bench left pane instead of a full-width panel.
  compact: { type: Boolean, default: false }
})

defineEmits(['create'])

// The backend picks questions at random when no explicit questionIds are sent, so the
// count field is the only knob needed for a "random draw".
const canCreate = computed(() => !props.trainingBusy && Number(props.sessionForm?.count) > 0)
</script>

<template>
  <section class="panel training-panel" :class="{ compact }">
    <div class="panel-title">
      <div>
        <p class="eyebrow">{{ t('session.workspace') }}</p>
        <h2>{{ t('session.title') }}</h2>
      </div>
      <span v-if="activeSession" class="pill ok">{{ t('session.progress', { progress: activeSessionProgress }) }}</span>
    </div>

    <p class="random-hint">{{ t('session.randomHint') }}</p>

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
      <button :disabled="!canCreate" type="button" @click="$emit('create')">
        {{ trainingBusy ? t('session.busy') : t('session.randomStart') }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.training-panel {
  margin-bottom: 12px;
}

.random-hint {
  margin: 0 0 8px;
  font-size: 11px;
  line-height: 1.5;
  color: #64748b;
}

.training-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: end;
}

.training-form label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11px;
  color: #64748b;
}

.training-form select,
.training-form input[type='number'] {
  padding: 5px 7px;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  font-size: 12px;
}

.training-form input[type='number'] {
  width: 68px;
}

.check-label {
  flex-direction: row !important;
  align-items: center;
  gap: 5px !important;
  white-space: nowrap;
}

.training-form button {
  padding: 6px 12px;
  border: none;
  border-radius: 8px;
  background: #2563eb;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.training-form button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* Narrow left pane: stack controls vertically so the 280px column stays readable. */
.compact .panel-title {
  margin-bottom: 6px;
}

.compact .training-form {
  flex-direction: column;
  align-items: stretch;
}

.compact .training-form label {
  width: 100%;
}

.compact .training-form input[type='number'],
.compact .training-form select {
  width: 100%;
}

.compact .check-label {
  width: 100%;
}

.compact .training-form button {
  width: 100%;
  margin-top: 2px;
}
</style>