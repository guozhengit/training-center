<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({
  questions: Array,
  tracks: Array,
  selectedTrack: String,
  selectedGroup: String,
  selectedTopic: String,
  selectedDifficulty: String,
  availableFilters: Object,
  loading: Boolean,
  error: String
})

defineEmits(['update:selectedTrack', 'update:selectedGroup', 'update:selectedTopic', 'update:selectedDifficulty', 'load', 'select'])

function priorityLabel(priority) {
  return t(`questions.priority.${priority}`)
}
</script>

<template>
  <section class="panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">{{ t('questions.eyebrow') }}</p>
        <h2>{{ t('questions.title') }}</h2>
      </div>
      <select :value="selectedTrack" @change="$emit('update:selectedTrack', $event.target.value); $emit('load')">
        <option v-for="track in tracks" :key="track.value" :value="track.value">
          {{ track.label }}
        </option>
      </select>
    </div>

    <div class="filter-bar">
      <select :value="selectedGroup" @change="$emit('update:selectedGroup', $event.target.value); $emit('load')">
        <option value="">{{ t('questions.allGroups') }}</option>
        <option v-for="g in (availableFilters?.groups ?? [])" :key="g" :value="g">{{ g }}</option>
      </select>
      <select :value="selectedTopic" @change="$emit('update:selectedTopic', $event.target.value); $emit('load')">
        <option value="">{{ t('questions.allTopics') }}</option>
        <option v-for="tpc in (availableFilters?.topics ?? [])" :key="tpc" :value="tpc">{{ tpc }}</option>
      </select>
      <select :value="selectedDifficulty" @change="$emit('update:selectedDifficulty', $event.target.value); $emit('load')">
        <option value="">{{ t('questions.allDifficulties') }}</option>
        <option v-for="d in (availableFilters?.difficulties ?? [])" :key="d" :value="d">{{ d }}</option>
      </select>
      <span class="filter-count">{{ t('questions.count', { count: questions?.length ?? 0 }) }}</span>
    </div>

    <div v-if="loading" class="loading-box">{{ t('questions.loading') }}</div>
    <div v-else-if="error" class="error-box">{{ error }}</div>
    <div v-else-if="!questions?.length" class="muted">{{ t('questions.empty') }}</div>
    <div v-else class="question-list">
      <article v-for="question in questions" :key="question.id" class="question-card question-card-clickable" :title="t('questions.clickView')" @click="$emit('select', question)">
        <div class="question-head">
          <span class="question-id">{{ question.id }}</span>
          <span v-if="question.priority" class="priority-dot" :class="'priority-' + question.priority" :title="priorityLabel(question.priority)"></span>
          <span class="question-track">{{ question.groupName }}</span>
        </div>
        <h3>{{ question.title }}</h3>
        <p>{{ question.topic }}</p>
        <footer>
          <span>{{ question.difficulty }}</span>
          <span>{{ question.language }}</span>
        </footer>
      </article>
    </div>
  </section>
</template>