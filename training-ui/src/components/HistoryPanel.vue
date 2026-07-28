<script setup>
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
        <p class="eyebrow">Review History</p>
        <h2>练习与判题复盘</h2>
      </div>
      <div class="button-row">
        <button :disabled="trainingBusy || !failedRetrainCount" type="button" @click="$emit('retrain')">
          重练当前失败题 {{ failedRetrainCount || '' }}
        </button>
        <button :disabled="!historyRows.length" type="button" @click="$emit('export-md')">
          导出 Markdown
        </button>
        <button :disabled="!historyRows.length" type="button" @click="$emit('export-csv')">
          导出 CSV
        </button>
      </div>
    </div>

    <div class="history-filter">
      <label>
        <span>类型</span>
        <select v-model="historyFilters.track">
          <option value="ALL">全部</option>
          <option value="CODING">机试题</option>
          <option value="ORAL">口述题</option>
          <option value="PROJECT">项目答辩</option>
        </select>
      </label>
      <label>
        <span>状态</span>
        <select v-model="historyFilters.status">
          <option value="ALL">全部</option>
          <option value="IN_PROGRESS">进行中</option>
          <option value="FINISHED">已完成</option>
          <option value="SKIPPED">已跳过</option>
        </select>
      </label>
      <label>
        <span>结果</span>
        <select v-model="historyFilters.verdict">
          <option value="ALL">全部</option>
          <option value="PASSED">通过</option>
          <option value="FAILED">失败</option>
          <option value="NONE">无结果</option>
        </select>
      </label>
      <label class="check-label">
        <input v-model="historyFilters.failedOnly" type="checkbox" />
        <span>只看失败/未通过</span>
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
          <span>状态：{{ entry.attempt.status }}</span>
          <span>结果：{{ entry.attempt.verdict ?? '-' }}</span>
          <span>开始：{{ formatDate(entry.attempt.startedAt) }}</span>
          <span>提交：{{ formatDate(entry.attempt.submittedAt) }}</span>
          <span>用时：{{ entry.attempt.durationSeconds ?? '-' }} 秒</span>
        </div>

        <div v-if="latestJudgement(entry)" class="judge-mini">
          <strong>最近判题：{{ latestJudgement(entry).status }}</strong>
          <span>
            Tests：{{ latestJudgement(entry).passedCount ?? '-' }}/{{ latestJudgement(entry).failedCount ?? '-' }}
          </span>
          <span>耗时：{{ latestJudgement(entry).durationMillis ?? '-' }} ms</span>
          <span>Exit：{{ latestJudgement(entry).exitCode ?? '-' }}</span>
        </div>

        <div class="history-actions">
          <button type="button" @click="$emit('detail', entry)">查看详情</button>
          <button :disabled="trainingBusy" type="button" @click="$emit('load-session', entry)">载入本组继续练</button>
          <button v-if="entry.attempt.sandboxPath" :disabled="trainingBusy" type="button"
            @click="$emit('copy-path', entry.attempt.sandboxPath)">复制路径</button>
          <button v-if="entry.attempt.sandboxPath" :disabled="trainingBusy" type="button"
            @click="$emit('copy-cd', entry.attempt.sandboxPath)">复制 cd 命令</button>
          <button v-if="entry.attempt.sandboxPath" :disabled="trainingBusy" type="button"
            @click="$emit('open-sandbox', entry.attempt)">打开沙箱</button>
        </div>

        <p v-if="entry.attempt.sandboxPath" class="sandbox-line">{{ entry.attempt.sandboxPath }}</p>
      </article>
    </div>
    <p v-else class="muted">还没有训练历史；先开始一组训练，面板会自动记录。</p>
  </section>
</template>
