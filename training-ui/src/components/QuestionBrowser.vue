<script setup>
defineProps({
  questions: Array,
  tracks: Array,
  selectedTrack: String
})

defineEmits(['update:selectedTrack', 'load'])
</script>

<template>
  <section class="panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">Question Browser</p>
        <h2>训练题目</h2>
      </div>
      <select :value="selectedTrack" @change="$emit('update:selectedTrack', $event.target.value); $emit('load')">
        <option v-for="track in tracks" :key="track.value" :value="track.value">
          {{ track.label }}
        </option>
      </select>
    </div>

    <div class="question-list">
      <article v-for="question in questions" :key="question.id" class="question-card">
        <div class="question-head">
          <span class="question-id">{{ question.id }}</span>
          <span class="question-track">{{ question.track }}</span>
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
