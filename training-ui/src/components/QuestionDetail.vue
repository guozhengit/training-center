<script setup>
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../composables/useApi'
import { renderMarkdownCollapsible } from '../composables/useMarkdown'

const props = defineProps({
  question: Object,
  starting: Boolean
})

const emit = defineEmits(['close', 'start'])

const { t } = useI18n()
const { fetchJson } = useApi()
const content = ref(null)
const loading = ref(false)
const error = ref('')
const maximized = ref(false)

watch(() => props.question, async (q) => {
  if (!q) {
    content.value = null
    maximized.value = false
    return
  }
  loading.value = true
  error.value = ''
  content.value = null
  try {
    content.value = await fetchJson(`/api/questions/${q.id}/content`)
  } catch (e) {
    error.value = e.message || t('qdetail.loadFailed')
  } finally {
    loading.value = false
  }
}, { immediate: true })

function priorityLabel(priority) {
  return t(`qdetail.priority.${priority}`)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="question" class="detail-backdrop" @click.self="emit('close')">
      <aside class="detail-drawer question-detail-drawer" :class="{ 'qd-maximized': maximized }">
        <header class="qd-header">
          <div class="qd-title-row">
            <span class="question-id">{{ question.id }}</span>
            <h2>{{ question.title }}</h2>
          </div>
          <div class="qd-header-actions">
            <button type="button" class="qd-maximize" :aria-label="maximized ? t('qdetail.restore') : t('qdetail.maximize')" :title="maximized ? t('qdetail.restore') : t('qdetail.maximize')" @click="maximized = !maximized">
              {{ maximized ? '🗗' : '⬜' }}
            </button>
            <button type="button" class="qd-close" :aria-label="t('qdetail.close')" :title="t('qdetail.close')" @click="emit('close')">✕</button>
          </div>
        </header>

        <div class="qd-meta">
          <span>{{ question.track }}</span>
          <span>{{ question.topic }}</span>
          <span>{{ question.difficulty }}</span>
          <span>{{ question.language }}</span>
          <span v-if="question.priority" :class="'priority-' + question.priority" class="qd-priority">
            {{ priorityLabel(question.priority) }}
          </span>
        </div>

        <div v-if="loading" class="loading-box">{{ t('qdetail.loading') }}</div>
        <div v-else-if="error" class="error-box">{{ error }}</div>
        <div v-else-if="content" class="qd-content">
          <div class="question-desc-text markdown-body" v-html="renderMarkdownCollapsible(content.description)"></div>
          <div v-if="content.starterCode" class="qd-starter">
            <h3>{{ t('qdetail.startCode') }}</h3>
            <pre><code>{{ content.starterCode }}</code></pre>
          </div>
          <div v-if="content.referenceCode" class="qd-starter">
            <h3>{{ t('qdetail.answerExample', { lang: question.language === 'python' ? 'Python' : 'Java' }) }}</h3>
            <pre><code>{{ content.referenceCode }}</code></pre>
          </div>
        </div>

        <footer class="qd-footer">
          <button type="button" class="qd-start-btn" :disabled="starting" @click="emit('start', question)">
            <span v-if="starting" class="qd-btn-loading"></span>
            {{ starting ? t('qdetail.starting') : t('qdetail.start') }}
          </button>
          <button type="button" class="qd-cancel-btn" :disabled="starting" @click="emit('close')">{{ t('qdetail.close') }}</button>
        </footer>
      </aside>
    </div>
  </Teleport>
</template>
