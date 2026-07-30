<script setup>
defineProps({
  questions: Array,
  tracks: Array,
  selectedTrack: String,
  selectedGroup: String,
  selectedTopic: String,
  selectedDifficulty: String,
  availableFilters: Object
})

defineEmits(['update:selectedTrack', 'update:selectedGroup', 'update:selectedTopic', 'update:selectedDifficulty', 'load', 'select'])
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

    <div class="filter-bar">
      <select :value="selectedGroup" @change="$emit('update:selectedGroup', $event.target.value); $emit('load')">
        <option value="">全部分组</option>
        <option v-for="g in (availableFilters?.groups ?? [])" :key="g" :value="g">{{ g }}</option>
      </select>
      <select :value="selectedTopic" @change="$emit('update:selectedTopic', $event.target.value); $emit('load')">
        <option value="">全部分类</option>
        <option v-for="t in (availableFilters?.topics ?? [])" :key="t" :value="t">{{ t }}</option>
      </select>
      <select :value="selectedDifficulty" @change="$emit('update:selectedDifficulty', $event.target.value); $emit('load')">
        <option value="">全部难度</option>
        <option v-for="d in (availableFilters?.difficulties ?? [])" :key="d" :value="d">{{ d }}</option>
      </select>
      <span class="filter-count">{{ questions?.length ?? 0 }} 题</span>
    </div>

    <div class="question-list">
      <article v-for="question in questions" :key="question.id" class="question-card question-card-clickable" title="点击查看详情" @click="$emit('select', question)">
        <div class="question-head">
          <span class="question-id">{{ question.id }}</span>
          <span v-if="question.priority" class="priority-dot" :class="'priority-' + question.priority" :title="question.priority === 'green' ? '高频必刷' : question.priority === 'yellow' ? '中频推荐' : '低频补充'"></span>
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
