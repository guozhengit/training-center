<script setup>
import { ref, watch } from 'vue'
import { useApi } from '../composables/useApi'
import { renderMarkdownCollapsible } from '../composables/useMarkdown'

const props = defineProps({
  question: Object
})

const emit = defineEmits(['close', 'start'])

const { fetchJson } = useApi()
const content = ref(null)
const loading = ref(false)
const error = ref('')

watch(() => props.question, async (q) => {
  if (!q) {
    content.value = null
    return
  }
  loading.value = true
  error.value = ''
  content.value = null
  try {
    content.value = await fetchJson(`/api/dashboard/questions/${q.id}/content`)
  } catch (e) {
    error.value = e.message || '加载题目内容失败'
  } finally {
    loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <Teleport to="body">
    <div v-if="question" class="detail-backdrop" @click.self="emit('close')">
      <aside class="detail-drawer question-detail-drawer">
        <header class="qd-header">
          <div class="qd-title-row">
            <span class="question-id">{{ question.id }}</span>
            <h2>{{ question.title }}</h2>
          </div>
          <button type="button" class="qd-close" @click="emit('close')">✕</button>
        </header>

        <div class="qd-meta">
          <span>{{ question.track }}</span>
          <span>{{ question.topic }}</span>
          <span>{{ question.difficulty }}</span>
          <span>{{ question.language }}</span>
          <span v-if="question.priority" :class="'priority-' + question.priority" class="qd-priority">
            {{ question.priority === 'green' ? '高频必刷' : question.priority === 'yellow' ? '中频推荐' : '低频补充' }}
          </span>
        </div>

        <div v-if="loading" class="loading-box">加载题目内容中...</div>
        <div v-else-if="error" class="error-box">{{ error }}</div>
        <div v-else-if="content" class="qd-content">
          <div class="question-desc-text markdown-body" v-html="renderMarkdownCollapsible(content.description, 3)"></div>
          <div v-if="content.starterCode" class="qd-starter">
            <h3>起始代码</h3>
            <pre><code>{{ content.starterCode }}</code></pre>
          </div>
        </div>

        <footer class="qd-footer">
          <button type="button" class="qd-start-btn" @click="emit('start', question)">
            开始训练此题
          </button>
          <button type="button" class="qd-cancel-btn" @click="emit('close')">关闭</button>
        </footer>
      </aside>
    </div>
  </Teleport>
</template>
