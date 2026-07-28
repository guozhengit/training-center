<script setup>
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
        <p class="eyebrow">Training Workspace</p>
        <h2>开始一组训练</h2>
      </div>
      <span v-if="activeSession" class="pill ok">当前进度 {{ activeSessionProgress }}</span>
    </div>

    <div class="training-form">
      <label>
        <span>训练类型</span>
        <select v-model="sessionForm.track">
          <option v-for="track in trainingTracks" :key="track.value" :value="track.value">
            {{ track.label }}
          </option>
        </select>
      </label>
      <label>
        <span>抽题数量</span>
        <input v-model.number="sessionForm.count" min="1" max="20" type="number" />
      </label>
      <label class="check-label">
        <input v-model="sessionForm.prioritizeWrongAnswers" type="checkbox" />
        <span>优先错题/未见题</span>
      </label>
      <label class="check-label">
        <input v-model="sessionForm.dueReviewOnly" type="checkbox" />
        <span>只抽到期复习</span>
      </label>
      <button :disabled="trainingBusy" type="button" @click="$emit('create')">
        {{ trainingBusy ? '处理中...' : '开始训练' }}
      </button>
    </div>
  </section>
</template>
