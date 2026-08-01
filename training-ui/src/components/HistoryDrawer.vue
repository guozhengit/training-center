<script setup>
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
          <p class="eyebrow">Attempt Detail</p>
          <h2>{{ detail.attempt.questionId }} 复盘详情</h2>
        </div>
        <button type="button" @click="$emit('close')">关闭</button>
      </div>

      <div class="detail-summary">
        <span>类型：{{ detail.attempt.track }}</span>
        <span>状态：{{ detail.attempt.status }}</span>
        <span>结果：{{ detail.attempt.verdict ?? '-' }}</span>
        <span>开始：{{ formatDate(detail.attempt.startedAt) }}</span>
        <span>提交：{{ formatDate(detail.attempt.submittedAt) }}</span>
      </div>

      <h3>{{ detail.attempt.title }}</h3>
      <p class="muted">{{ detail.attempt.topic }}</p>

      <p v-if="detail.attempt.sandboxPath" class="sandbox-line">{{ detail.attempt.sandboxPath }}</p>

      <div v-if="detail.oralScore" class="detail-block oral-score-detail">
        <h4>口述评分：{{ detail.oralScore.total }}/10</h4>
        <p class="muted">
          准确性 {{ detail.oralScore.correctness }} / 结构化 {{ detail.oralScore.structure }}
          / 项目证据 {{ detail.oralScore.projectEvidence }} / 取舍意识 {{ detail.oralScore.tradeoff }}
          / 事实边界 {{ detail.oralScore.factRestraint }}
        </p>
      </div>

      <div v-if="detail.attempt.notes" class="detail-block">
        <h4>复盘备注</h4>
        <p>{{ detail.attempt.notes }}</p>
      </div>

      <div v-if="detail.attempt.improvedAnswer" class="detail-block">
        <h4>优化后的答案</h4>
        <pre class="improved-answer">{{ detail.attempt.improvedAnswer }}</pre>
      </div>

      <div v-if="detail.judgements.length" class="judgement-timeline">
        <article v-for="judgement in detail.judgements" :key="judgement.id" class="judgement-detail">
          <div class="question-head">
            <span class="question-id">#{{ judgement.sequenceNo }}</span>
            <span class="question-track">{{ judgement.status }}</span>
          </div>
          <div class="history-meta">
            <span>开始：{{ formatDate(judgement.startedAt) }}</span>
            <span>结束：{{ formatDate(judgement.finishedAt) }}</span>
            <span>通过/失败：{{ judgement.passedCount ?? '-' }}/{{ judgement.failedCount ?? '-' }}</span>
            <span>耗时：{{ judgement.durationMillis ?? '-' }} ms</span>
            <span>Exit：{{ judgement.exitCode ?? '-' }}</span>
          </div>
          <pre v-if="judgement.stderrExcerpt">{{ judgement.stderrExcerpt }}</pre>
          <pre v-if="judgement.stdoutExcerpt">{{ judgement.stdoutExcerpt }}</pre>
        </article>
      </div>
      <p v-else class="muted">这条练习还没有自动判题记录。</p>
    </section>
  </div>
</template>
