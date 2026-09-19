<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useApi } from '../composables/useApi'
import { renderMarkdown } from '../composables/useMarkdown'
import CodeEditor from './CodeEditor.vue'

const props = defineProps({
  dashboard: { type: Object, required: true },
  training: { type: Object, required: true },
  starting: { type: Boolean, default: false }
})

const emit = defineEmits(['start'])

const { fetchJson } = useApi()

const selectedId = ref('')
const contentStore = reactive({})
const codeStore = reactive({})
const activeTab = ref('')
const showConsole = ref(false)
const CODE_PREFIX = 'training:bench:code:'

const DIFFICULTY_TONE = {
  基础: 'green',
  进阶: 'amber',
  AI进阶: 'purple'
}

const questions = computed(() => props.dashboard.questions.value ?? [])

// Left-pane filter: matches id, title and topic so "B001" and "双指针" both work.
const searchTerm = ref('')
const filteredQuestions = computed(() => {
  const keyword = searchTerm.value.trim().toLowerCase()
  if (!keyword) return questions.value
  return questions.value.filter((item) =>
    [item.id, item.title, item.topic]
      .filter(Boolean)
      .some((field) => String(field).toLowerCase().includes(keyword))
  )
})

const selectedQuestion = computed(
  () => questions.value.find((item) => item.id === selectedId.value) ?? null
)
const activeSession = computed(() => props.training.activeSession.value)

const currentAttempt = computed(() => {
  const session = activeSession.value
  if (!session || !selectedId.value) return null
  return session.attempts.find((attempt) => attempt.questionId === selectedId.value) ?? null
})

const content = computed(() => contentStore[selectedId.value] ?? null)

// The newest outcome wins: judge and run are mutually exclusive by construction (each clears the
// other), so reading run first and falling back to judge always surfaces the latest result.
const result = computed(() => {
  const attempt = currentAttempt.value
  if (!attempt) return null
  return (
    props.training.runResults.value[attempt.id] ??
    props.training.judgeResults.value[attempt.id] ??
    null
  )
})

const cases = computed(() => result.value?.cases ?? [])
const passedCases = computed(() => cases.value.filter((item) => item.status === 'PASSED').length)
const running = computed(() => props.training.runBusy.value || props.training.trainingBusy.value)

const editorCode = computed({
  get() {
    const attempt = currentAttempt.value
    return attempt ? codeStore[attempt.id] ?? '' : ''
  },
  set(value) {
    const attempt = currentAttempt.value
    if (!attempt) return
    codeStore[attempt.id] = value
    try {
      localStorage.setItem(CODE_PREFIX + attempt.id, value)
    } catch { /* storage unavailable, drafts are best-effort */ }
  }
})

function readPersistedCode(attemptId) {
  try {
    return localStorage.getItem(CODE_PREFIX + attemptId)
  } catch {
    return null
  }
}

/** Splits "# title" + "## sections" markdown into a title block and tabbable sections. */
function parsedContent() {
  const raw = content.value?.description ?? ''
  if (!raw) return { title: '', sections: [] }
  const blocks = raw.split(/^## /m)
  const head = blocks.shift() ?? ''
  const title = head.replace(/^#\s+/m, '').trim()
  const sections = blocks
    .map((block) => {
      const breakAt = block.indexOf('\n')
      const heading = (breakAt >= 0 ? block.slice(0, breakAt) : block).trim()
      const body = breakAt >= 0 ? block.slice(breakAt + 1) : ''
      return { heading, html: renderMarkdown(body) }
    })
    .filter((section) => section.heading)
  return { title, sections }
}

const parsed = computed(() => parsedContent())

async function loadContent(questionId) {
  if (!questionId || contentStore[questionId]) return
  try {
    contentStore[questionId] = await fetchJson(`/api/questions/${questionId}/content`)
  } catch { /* the panel simply stays empty; the error surfaces through the dashboard */ }
}

function selectQuestion(question) {
  selectedId.value = question.id
  activeTab.value = ''
  loadContent(question.id)
  const session = activeSession.value
  const existing = session?.attempts.find((attempt) => attempt.questionId === question.id)
  if (!existing) {
    emit('start', question)
  }
}

async function runCode() {
  const attempt = currentAttempt.value
  if (!attempt) return
  await props.training.runAttempt(attempt, editorCode.value)
}

async function submitCode() {
  const attempt = currentAttempt.value
  if (!attempt) return
  await props.training.judgeAttempt(attempt, editorCode.value)
}

function resetCode() {
  const attempt = currentAttempt.value
  if (!attempt) return
  codeStore[attempt.id] = content.value?.starterCode ?? ''
}

function statusText(status) {
  if (status === 'PASSED') return '通过'
  if (status === 'SKIPPED') return '跳过'
  if (status === 'ERROR') return '错误'
  return '失败'
}

function camelToWords(value) {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .toLowerCase()
}

// Seed the editor from the starter the first time an attempt appears, restoring any saved draft.
watch(currentAttempt, (attempt) => {
  if (!attempt) return
  const persisted = readPersistedCode(attempt.id)
  if (persisted !== null) {
    codeStore[attempt.id] = persisted
    return
  }
  if (!codeStore[attempt.id]) {
    codeStore[attempt.id] = contentStore[attempt.questionId]?.starterCode ?? ''
  }
}, { immediate: true })

watch(content, () => {
  const attempt = currentAttempt.value
  if (attempt && !codeStore[attempt.id] && content.value?.starterCode) {
    codeStore[attempt.id] = content.value.starterCode
  }
})

watch(activeTab, () => { showConsole.value = false })

// ---- 算法动画 ----
// 动画库是独立静态页(public/algo-anim), 通过 anim-ids.json 知道哪些题有 demo,
// 有的话在题头显示「看动画」, 点击以 iframe 弹窗打开并定位到该题。
const animIds = ref({})
const animOpen = ref(false)

const animUrl = computed(() =>
  selectedId.value ? `/algo-anim/index.html?id=${encodeURIComponent(selectedId.value)}&embed=1` : ''
)
const animTitle = computed(() => animIds.value[selectedId.value] || '')
const hasAnim = computed(() => Boolean(animIds.value[selectedId.value]))

async function loadAnimIds() {
  try {
    const data = await fetchJson('/algo-anim/anim-ids.json')
    const map = {}
    for (const item of data.items ?? []) map[item.id] = item.title
    animIds.value = map
  } catch {
    animIds.value = {} // 动画库缺失时静默降级, 不阻塞刷题
  }
}

function openAnim() {
  if (hasAnim.value) animOpen.value = true
}

function openAnimTab() {
  if (animUrl.value) window.open(animUrl.value, '_blank', 'noopener')
}

function onKeydown(event) {
  if (event.key === 'Escape' && animOpen.value) animOpen.value = false
}

// The bench is a coding bench: default the catalogue to the CODING track and pull the list here,
// because the dashboard bootstrap only loads health/catalog/stats/history.
onMounted(async () => {
  props.dashboard.selectedTrack.value = 'CODING'
  await props.dashboard.loadQuestions()
  await props.dashboard.loadFilters()
  await loadAnimIds()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="wb">
    <aside class="wb-side">
      <div class="side-head">
        <strong>题库</strong>
        <span class="count">{{ filteredQuestions.length }}</span>
      </div>
      <input v-model="searchTerm" class="side-search" placeholder="搜索题号或标题" />
      <ul class="q-list">
        <li v-for="question in filteredQuestions" :key="question.id">
          <button
            type="button"
            class="q-item"
            :class="{ active: question.id === selectedId }"
            @click="selectQuestion(question)"
          >
            <span class="q-id">{{ question.id }}</span>
            <span class="q-title">{{ question.title }}</span>
            <span class="q-tags">
              <em v-if="animIds[question.id]" class="anim-dot" title="有算法动画">动画</em>
              <em class="tag" :class="'tone-' + (DIFFICULTY_TONE[question.difficulty] || 'gray')">
                {{ question.difficulty }}
              </em>
            </span>
          </button>
        </li>
      </ul>
    </aside>

    <section class="wb-main">
      <div v-if="!selectedQuestion" class="empty">
        <h2>从左侧选一道题开始</h2>
        <p>选中后会为你创建一次答题，代码会自动填入该题的起始模板。</p>
      </div>

      <template v-else>
        <header class="q-head">
          <div>
            <span class="q-num">{{ selectedQuestion.id }}</span>
            <h2>{{ parsed.title || selectedQuestion.title }}</h2>
          </div>
          <div class="q-head-actions">
            <button
              v-if="hasAnim"
              type="button"
              class="btn-anim"
              :title="'算法动画：' + animTitle"
              @click="openAnim"
            >
              ▶ 看动画
            </button>
            <em class="tag" :class="'tone-' + (DIFFICULTY_TONE[selectedQuestion.difficulty] || 'gray')">
              {{ selectedQuestion.difficulty }}
            </em>
          </div>
        </header>

        <nav v-if="parsed.sections.length" class="tabs">
          <button
            v-for="section in parsed.sections"
            :key="section.heading"
            type="button"
            class="tab"
            :class="{ active: activeTab === section.heading }"
            @click="activeTab = section.heading"
          >
            {{ section.heading }}
          </button>
        </nav>

        <div class="q-body">
          <div v-if="!parsed.sections.length" class="q-text">加载中…</div>
          <div
            v-for="section in parsed.sections"
            v-show="!activeTab || activeTab === section.heading"
            :key="section.heading"
            class="q-text markdown-body"
            v-html="section.html"
          />
        </div>

        <div v-if="currentAttempt" class="editor-wrap">
          <div class="editor-bar">
            <span class="lang">{{ selectedQuestion.language || 'java' }}</span>
            <button type="button" class="ghost" :disabled="running" @click="resetCode">重置模板</button>
            <span class="spacer" />
            <button type="button" class="run" :disabled="running" @click="runCode">
              {{ props.training.runBusy.value ? '运行中…' : '运行' }}
            </button>
            <button type="button" class="submit" :disabled="running" @click="submitCode">提交</button>
          </div>
          <CodeEditor v-model="editorCode" :language="selectedQuestion.language || 'java'" />
        </div>

        <div v-else class="empty inline">
          <button type="button" :disabled="starting" @click="emit('start', selectedQuestion)">
            {{ starting ? '创建中…' : '开始这道题' }}
          </button>
        </div>
      </template>
    </section>

    <aside class="wb-result">
      <div class="side-head"><strong>运行结果</strong></div>

      <div v-if="!result" class="empty small">
        <p>点「运行」跑公开用例（不记录成绩），或点「提交」正式判题。</p>
      </div>

      <template v-else>
        <div class="verdict" :class="result.status === 'PASSED' ? 'ok' : 'bad'">
          <strong>{{ result.status === 'PASSED' ? '通过' : '未通过' }}</strong>
          <span>{{ passedCases }} / {{ cases.length }} 用例</span>
          <span class="ms">{{ result.durationMillis }} ms</span>
        </div>

        <p v-if="result.compileHint" class="hint">{{ result.compileHint }}</p>

        <ul v-if="cases.length" class="case-list">
          <li v-for="item in cases" :key="item.name" class="case" :class="(item.status || '').toLowerCase()">
            <div class="case-top">
              <span class="case-dot" />
              <span class="case-name">{{ camelToWords(item.name) }}</span>
              <span class="case-ms">{{ item.durationMillis ?? '-' }} ms</span>
            </div>
            <div class="case-status">{{ statusText(item.status) }}</div>
            <div v-if="item.expected !== null || item.actual !== null" class="case-diff">
              <div><label>期望</label><code>{{ item.expected ?? '-' }}</code></div>
              <div><label>实际</label><code>{{ item.actual ?? '-' }}</code></div>
            </div>
            <div v-if="item.message" class="case-msg">{{ item.message }}</div>
          </li>
        </ul>

        <div v-else class="empty small">
          <p>没有拿到用例明细。可能是编译失败，或该题用的是 Python 判题（暂不解析用例）。</p>
        </div>

        <details class="console" @toggle="showConsole = !showConsole">
          <summary>控制台输出</summary>
          <pre v-if="result.stdoutExcerpt">{{ result.stdoutExcerpt }}</pre>
          <pre v-if="result.stderrExcerpt" class="err">{{ result.stderrExcerpt }}</pre>
        </details>
      </template>
    </aside>
  </div>

  <!-- 算法动画弹窗：内嵌独立动画页, Esc 或点遮罩关闭。Teleport 到 body 避免多根节点影响三栏布局 -->
  <Teleport to="body">
    <div v-if="animOpen" class="anim-mask" @click.self="animOpen = false">
      <div class="anim-modal">
        <div class="anim-bar">
          <strong>算法动画 · {{ selectedId }} {{ animTitle }}</strong>
          <div class="anim-bar-actions">
            <button type="button" @click="openAnimTab">新窗口打开</button>
            <button type="button" class="close" @click="animOpen = false">关闭 ✕</button>
          </div>
        </div>
        <iframe :src="animUrl" class="anim-frame" title="算法动画" allowfullscreen />
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.wb {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr) 380px;
  gap: 14px;
  height: calc(100vh - 132px);
  min-height: 560px;
}

.wb-side,
.wb-main,
.wb-result {
  background: #fff;
  border: 1px solid #e2e8f2;
  border-radius: 16px;
  overflow: auto;
}

.wb-side,
.wb-result {
  padding: 14px;
}

.wb-main {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.side-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  margin-bottom: 10px;
}

.count {
  font-size: 12px;
  color: #64748b;
}

.side-search {
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
}

.q-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.q-item {
  width: 100%;
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 9px 10px;
  border-radius: 10px;
  background: transparent;
  color: #182235;
  font-weight: 500;
  font-size: 13px;
  text-align: left;
  box-shadow: none;
}

.q-item:hover {
  background: #f1f5fb;
}

.q-item.active {
  background: #e6f0ff;
}

.q-id {
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}

.q-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag {
  font-style: normal;
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 999px;
  background: #f1f5fb;
  color: #475569;
}

.tone-green {
  background: #e7f6e9;
  color: #2f7a3d;
}

.tone-amber {
  background: #fdf1de;
  color: #96601a;
}

.tone-purple {
  background: #f0ecfd;
  color: #5b4bb7;
}

.q-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.q-head h2 {
  margin: 2px 0 0;
  font-size: 18px;
  font-weight: 600;
}

.q-num {
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}

.tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  border-bottom: 1px solid #e2e8f2;
  padding-bottom: 8px;
}

.tab {
  padding: 5px 11px;
  border-radius: 999px;
  background: #f1f5fb;
  color: #475569;
  font-size: 12px;
  font-weight: 500;
  box-shadow: none;
}

.tab.active {
  background: #2563eb;
  color: #fff;
}

.q-body {
  flex: 0 1 auto;
  overflow: auto;
  min-height: 90px;
  max-height: 38vh;
  font-size: 13px;
  line-height: 1.7;
  color: #334155;
}

.editor-wrap {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 220px;
}

.editor-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.lang {
  font-size: 12px;
  color: #64748b;
  text-transform: uppercase;
}

.spacer {
  flex: 1;
}

.run,
.submit,
.ghost {
  padding: 7px 16px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  box-shadow: none;
}

.ghost {
  background: #f1f5fb;
  color: #475569;
}

.run {
  background: #0f766e;
}

.submit {
  background: linear-gradient(135deg, #2563eb, #7c3aed);
}

.verdict {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 13px;
  margin-bottom: 10px;
}

.verdict.ok {
  background: #e7f6e9;
  color: #2f7a3d;
}

.verdict.bad {
  background: #fdeaea;
  color: #a32d2d;
}

.verdict .ms {
  margin-left: auto;
  font-size: 12px;
  opacity: 0.75;
}

.hint {
  margin: 0 0 10px;
  padding: 9px 11px;
  border-radius: 10px;
  background: #fdf1de;
  color: #7c4a12;
  font-size: 12px;
  line-height: 1.6;
}

.case-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.case {
  border: 1px solid #e2e8f2;
  border-radius: 12px;
  padding: 9px 11px;
  font-size: 12px;
}

.case.passed {
  border-color: #cfe8d4;
}

.case.failed,
.case.error {
  border-color: #f2c9c9;
}

.case-top {
  display: flex;
  align-items: center;
  gap: 7px;
}

.case-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #94a3b8;
  flex: 0 0 auto;
}

.case.passed .case-dot {
  background: #2f7a3d;
}

.case.failed .case-dot,
.case.error .case-dot {
  background: #c0392b;
}

.case-name {
  font-weight: 600;
  color: #182235;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-ms {
  margin-left: auto;
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
}

.case-status {
  margin-top: 2px;
  color: #64748b;
}

.case.passed .case-status {
  color: #2f7a3d;
}

.case.failed .case-status,
.case.error .case-status {
  color: #c0392b;
}

.case-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-top: 7px;
}

.case-diff label {
  display: block;
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 2px;
}

.case-diff code {
  display: block;
  padding: 5px 7px;
  border-radius: 7px;
  background: #f6f8fc;
  font-size: 11px;
  word-break: break-all;
}

.case-msg {
  margin-top: 7px;
  padding: 7px 8px;
  border-radius: 8px;
  background: #f6f8fc;
  color: #475569;
  line-height: 1.6;
  word-break: break-word;
}

.console {
  margin-top: 12px;
  font-size: 12px;
}

.console summary {
  cursor: pointer;
  color: #64748b;
}

.console pre {
  max-height: 200px;
  overflow: auto;
  background: #0f172a;
  color: #d7e0f0;
  padding: 10px;
  border-radius: 10px;
  font-size: 11px;
  white-space: pre-wrap;
}

.console pre.err {
  background: #2b1416;
  color: #f3c9c9;
}

.empty {
  text-align: center;
  color: #64748b;
  font-size: 13px;
  padding: 40px 20px;
}

.empty.inline {
  padding: 14px;
}

.empty h2 {
  font-size: 16px;
  font-weight: 600;
  color: #182235;
  margin: 0 0 6px;
}

.empty.small {
  padding: 18px 8px;
  text-align: left;
  line-height: 1.7;
}

/* ---- 算法动画入口 ---- */
.q-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.btn-anim {
  padding: 5px 11px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 6px;
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: 0.15s;
}

.btn-anim:hover {
  background: #dbeafe;
  border-color: #93c5fd;
}

.anim-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
  padding: 24px;
}

.anim-modal {
  background: #fff;
  border-radius: 10px;
  width: min(1180px, 100%);
  height: min(860px, 100%);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.3);
}

.anim-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  background: #f8fafc;
  flex: none;
}

.anim-bar strong {
  font-size: 13.5px;
}

.anim-bar-actions {
  display: flex;
  gap: 8px;
}

.anim-bar-actions button {
  padding: 5px 11px;
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 6px;
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

.anim-bar-actions button:hover {
  background: #f3f4f6;
}

.anim-bar-actions .close {
  border-color: #fecaca;
  color: #b91c1c;
}

.anim-frame {
  flex: 1;
  width: 100%;
  border: 0;
  min-height: 0;
}

.anim-dot {
  margin-left: auto;
  flex: none;
  font-size: 10px;
  color: #1d4ed8;
  background: #eff6ff;
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
