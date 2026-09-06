<script setup>
import { reactive, watch, getCurrentScope, onScopeDispose } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRecorder } from '../composables/useRecorder'
import { useHint } from '../composables/useHint'
import { useCountdown } from '../composables/useCountdown'
import { useApi } from '../composables/useApi'
import { renderMarkdownCollapsible } from '../composables/useMarkdown'
import CodeEditor from './CodeEditor.vue'

const { t } = useI18n()
const { fetchJson } = useApi()

const props = defineProps({
  activeSession: Object,
  submitForms: Object,
  judgeResults: Object,
  judgeProgress: Object,
  trainingBusy: Boolean,
  scoreDimensions: Array
})

defineEmits(['judge', 'submit', 'open-sandbox'])

const recorder = useRecorder()
const hint = useHint()
const countdown = useCountdown()

// Question content store: { [questionId]: { description, starterCode, referenceCode, answer, followUps, sections, evidenceEntry, factBoundary, recommendedSeconds, loaded, showDesc } }
const contentStore = reactive({})
// Reference-answer reveal state per attempt: { [attemptId]: boolean }
const revealState = reactive({})
// Code editor store: { [attemptId]: code }, mirrored to localStorage so drafts survive page refresh
const codeStore = reactive({})

const CODE_STORAGE_PREFIX = 'training:code:'
const CODE_PERSIST_DELAY_MS = 500
const codePersistTimers = {}

function readPersistedCode(attemptId) {
  try {
    return localStorage.getItem(CODE_STORAGE_PREFIX + attemptId)
  } catch { /* storage unavailable, ignore */ }
  return null
}

function writePersistedCode(attemptId, value) {
  try {
    if (value) {
      localStorage.setItem(CODE_STORAGE_PREFIX + attemptId, value)
    } else {
      localStorage.removeItem(CODE_STORAGE_PREFIX + attemptId)
    }
  } catch { /* storage full or unavailable, non-critical */ }
}

function scheduleCodePersist(attemptId, value) {
  clearTimeout(codePersistTimers[attemptId])
  codePersistTimers[attemptId] = setTimeout(() => {
    delete codePersistTimers[attemptId]
    writePersistedCode(attemptId, value)
  }, CODE_PERSIST_DELAY_MS)
}

// Drop persisted drafts that no longer belong to the active session
function pruneStaleCode() {
  const keep = new Set((props.activeSession?.attempts ?? []).map((attempt) => attempt.id))
  try {
    const stale = []
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key && key.startsWith(CODE_STORAGE_PREFIX) && !keep.has(key.slice(CODE_STORAGE_PREFIX.length))) {
        stale.push(key)
      }
    }
    stale.forEach((key) => localStorage.removeItem(key))
  } catch { /* storage unavailable, ignore */ }
}

watch(() => props.activeSession, pruneStaleCode, { immediate: true })

async function loadQuestionContent(attempt) {
  const qid = attempt.questionId
  if (contentStore[qid]?.loaded) {
    contentStore[qid].showDesc = !contentStore[qid].showDesc
    return
  }
  if (contentStore[qid]?.loading) return
  if (!contentStore[qid]) contentStore[qid] = { loading: true }
  else contentStore[qid].loading = true
  try {
    const data = await fetchJson(`/api/questions/${qid}/content`)
    contentStore[qid] = {
      description: data.description,
      starterCode: data.starterCode,
      referenceCode: data.referenceCode,
      answer: data.answer,
      followUps: data.followUps,
      sections: data.sections ?? [],
      evidenceEntry: data.evidenceEntry,
      factBoundary: data.factBoundary,
      recommendedSeconds: data.recommendedSeconds,
      loaded: true,
      showDesc: true
    }
    // Pre-fill code editor with the persisted draft, falling back to starter code
    if (codeStore[attempt.id] === undefined) {
      const persisted = readPersistedCode(attempt.id)
      codeStore[attempt.id] = persisted !== null ? persisted : (data.starterCode || '')
    }
    if (data.starterCode) {
      hint.setSource(attempt.id, data.starterCode)
    }
  } catch {
    contentStore[qid] = {
      description: t('attempt.loadFailed'), starterCode: '', referenceCode: '', answer: '', followUps: '',
      sections: [], evidenceEntry: '', factBoundary: '', loaded: true, showDesc: true
    }
  }
}

function toggleReveal(attempt) {
  revealState[attempt.id] = !revealState[attempt.id]
  if (revealState[attempt.id] && !contentStore[attempt.questionId]?.loaded) {
    loadQuestionContent(attempt)
  }
}

function getCode(attemptId) {
  if (codeStore[attemptId] === undefined) {
    const persisted = readPersistedCode(attemptId)
    codeStore[attemptId] = persisted ?? ''
  }
  return codeStore[attemptId]
}

function setCode(attemptId, value) {
  codeStore[attemptId] = value
  scheduleCodePersist(attemptId, value)
}

function scoreTotal(form) {
  if (!form) return 0
  return Number(form.correctness) + Number(form.structure) + Number(form.projectEvidence)
    + Number(form.tradeoff) + Number(form.factRestraint)
}

function recommendedSecondsFor(attempt) {
  return contentStore[attempt.questionId]?.recommendedSeconds || null
}

function startTimer(attempt) {
  const seconds = recommendedSecondsFor(attempt)
  if (seconds) {
    countdown.startWithSeconds(attempt.id, seconds)
  } else {
    countdown.start(attempt.id, attempt.difficulty)
  }
}

function timeLabel(attempt) {
  const seconds = recommendedSecondsFor(attempt)
  if (seconds) {
    const formatted = seconds >= 60 ? `${Math.round(seconds / 60)} min` : `${seconds} ${t('report.seconds')}`
    return t('attempt.recommended', { time: formatted })
  }
  return attempt.difficulty === '三星' ? '60 min' : '40 min'
}

function sectionRole(qid, role) {
  const content = contentStore[qid]
  if (!content?.sections?.length) return ''
  const target = role === 'evidence' ? content.evidenceEntry : content.factBoundary
  const found = content.sections.find((s) => s.content === target)
  return found ? found.heading : ''
}

const RUBRIC_TRACKS = ['ORAL', 'PROJECT']

function rubricAnchor(track, key) {
  const base = RUBRIC_TRACKS.includes(track) ? track : 'ORAL'
  return t(`rubric.${base}.${key}`)
}

if (getCurrentScope()) {
  // Flush pending draft writes instead of dropping them on unmount
  onScopeDispose(() => {
    for (const [attemptId, timer] of Object.entries(codePersistTimers)) {
      clearTimeout(timer)
      delete codePersistTimers[attemptId]
      writePersistedCode(attemptId, codeStore[attemptId])
    }
  })
}
</script>

<template>
  <div v-if="activeSession" class="active-session">
    <header>
      <div>
        <h3>{{ activeSession.mode }} {{ t('attempt.training') }}</h3>
        <p>{{ t('attempt.session') }}：{{ activeSession.id }}</p>
      </div>
      <span class="question-track">{{ activeSession.status }}</span>
    </header>

    <article v-for="attempt in activeSession.attempts" :key="attempt.id" class="attempt-card">
      <div class="question-head">
        <span class="question-id">{{ attempt.questionId }}</span>
        <span class="question-track">{{ attempt.status }}</span>
      </div>
      <h3>{{ attempt.title }}</h3>
      <p>{{ attempt.topic }}</p>

      <div v-if="attempt.status === 'FINISHED'" class="finished-row">
        <span>{{ t('attempt.result') }}：{{ attempt.verdict }}</span>
        <span>{{ t('attempt.duration') }}：{{ attempt.durationSeconds ?? '-' }} {{ t('attempt.durationUnit') }}</span>
      </div>

      <div v-else-if="attempt.track === 'CODING'" class="coding-judge-box">
        <!-- Question content viewer -->
        <div class="question-content-section">
          <div class="oral-content-controls">
            <button type="button" class="btn-toggle-desc" @click="loadQuestionContent(attempt)">
              {{ contentStore[attempt.questionId]?.showDesc ? t('attempt.hideDesc') : t('attempt.showDesc') }}
            </button>
            <button
              v-if="contentStore[attempt.questionId]?.loaded && contentStore[attempt.questionId].answer"
              type="button"
              class="btn-toggle-desc"
              :class="{ 'btn-revealed': revealState[attempt.id] }"
              @click="toggleReveal(attempt)"
            >
              {{ revealState[attempt.id] ? t('attempt.hideAnswer') : t('attempt.revealAnswer') }}
            </button>
          </div>
          <div v-if="contentStore[attempt.questionId]?.showDesc" class="question-desc-panel">
            <div class="question-desc-text markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].description)"></div>
          </div>
          <div v-if="revealState[attempt.id] && contentStore[attempt.questionId]?.loaded && contentStore[attempt.questionId].answer" class="reveal-panel">
            <h4 class="reveal-heading">{{ t('attempt.thinkApproach') }}</h4>
            <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].answer)"></div>
            <h4 v-if="contentStore[attempt.questionId].referenceCode" class="reveal-heading">
              {{ t('attempt.answerExample', { lang: attempt.language === 'python' ? 'Python' : 'Java' }) }}
            </h4>
            <div v-if="contentStore[attempt.questionId].referenceCode" class="reference-code">
              <pre><code>{{ contentStore[attempt.questionId].referenceCode }}</code></pre>
            </div>
          </div>
        </div>

        <!-- Countdown timer -->
        <div class="countdown-bar" :class="{ 'countdown-warning': countdown.isWarning(attempt.id), 'countdown-expired': countdown.getTimer(attempt.id).expired }">
          <template v-if="!countdown.getTimer(attempt.id).running && countdown.getTimer(attempt.id).remaining === 0 && !countdown.getTimer(attempt.id).expired">
            <button type="button" class="btn-timer-start" @click="startTimer(attempt)">
              {{ t('attempt.startTimer', { label: timeLabel(attempt) }) }}
            </button>
          </template>
          <template v-else>
            <span class="countdown-display">{{ countdown.formatRemaining(countdown.getTimer(attempt.id).remaining) }}</span>
            <span v-if="countdown.getTimer(attempt.id).expired" class="countdown-label">{{ t('attempt.expired') }}</span>
            <span v-else-if="countdown.isWarning(attempt.id)" class="countdown-label">{{ t('attempt.warn5') }}</span>
            <span v-else class="countdown-label">{{ timeLabel(attempt) }}</span>
            <button v-if="countdown.getTimer(attempt.id).running" type="button" class="btn-timer-sm btn-timer-pause" @click="countdown.pause(attempt.id)">{{ t('attempt.pause') }}</button>
            <button v-else-if="!countdown.getTimer(attempt.id).expired" type="button" class="btn-timer-sm btn-timer-resume" @click="countdown.resume(attempt.id)">{{ t('attempt.resume') }}</button>
            <button type="button" class="btn-timer-sm btn-timer-reset" @click="countdown.reset(attempt.id)">{{ t('attempt.reset') }}</button>
          </template>
        </div>

        <!-- Code editor -->
        <div class="code-editor-section">
          <label class="code-editor-label">{{ t('attempt.codeLabel', { lang: attempt.language }) }}</label>
          <CodeEditor
            :model-value="getCode(attempt.id)"
            :language="attempt.language"
            @update:model-value="setCode(attempt.id, $event)"
          />
        </div>

        <p class="muted">
          {{ t('attempt.sandboxHint') }}
        </p>
        <div class="finished-row" v-if="attempt.sandboxPath">
          <span>{{ t('attempt.sandbox') }}：{{ attempt.sandboxPath }}</span>
        </div>
        <button :disabled="trainingBusy" type="button" @click="$emit('judge', attempt, getCode(attempt.id))">
          {{ trainingBusy ? t('attempt.judging') : t('attempt.judge') }}
        </button>

        <div v-if="judgeProgress" class="judge-progress">
          <div class="judge-progress-bar">
            <div class="judge-progress-fill" :data-stage="judgeProgress.stage"></div>
          </div>
          <span class="judge-progress-text">{{ judgeProgress.message }}</span>
        </div>

        <div v-if="judgeResults[attempt.id]" class="judge-result">
          <strong>{{ t('attempt.judgeResult') }}：{{ judgeResults[attempt.id].status }}</strong>
          <span>{{ t('attempt.verdict') }}：{{ judgeResults[attempt.id].verdict }}</span>
          <span>
            {{ t('attempt.tests') }}：{{ judgeResults[attempt.id].passedCount ?? '-' }}/{{ judgeResults[attempt.id].failedCount ?? '-' }}
          </span>
          <span>{{ t('attempt.duration') }}：{{ judgeResults[attempt.id].durationMillis }} ms</span>
          <span>{{ t('attempt.sandbox') }}：{{ judgeResults[attempt.id].sandboxPath }}</span>
          <p v-if="judgeResults[attempt.id].compileHint" class="judge-compile-hint">{{ judgeResults[attempt.id].compileHint }}</p>
          <pre v-if="judgeResults[attempt.id].stderrExcerpt">{{ judgeResults[attempt.id].stderrExcerpt }}</pre>
          <pre v-else-if="judgeResults[attempt.id].stdoutExcerpt">{{ judgeResults[attempt.id].stdoutExcerpt }}</pre>
        </div>

        <div class="hint-box">
          <div class="hint-controls">
            <button
              v-if="hint.getHint(attempt.id).level < 2"
              type="button"
              class="btn-hint"
              @click="hint.revealNext(attempt.id)"
            >
              {{ hint.getHint(attempt.id).level < 0 ? t('hint.show') : t('hint.more') }}
            </button>
            <span v-if="hint.getHint(attempt.id).level >= 0" class="hint-level">
              {{ t('hint.level' + hint.getHint(attempt.id).level) }}
            </span>
            <button
              v-if="hint.getHint(attempt.id).level >= 0"
              type="button"
              class="btn-reset"
              @click="hint.resetHint(attempt.id)"
            >{{ t('hint.collapse') }}</button>
          </div>
          <pre v-if="hint.getHint(attempt.id).content" class="hint-content">{{ hint.getHint(attempt.id).content }}</pre>
        </div>
      </div>

      <div v-else-if="submitForms[attempt.id]" class="attempt-form">
        <div class="question-content-section">
          <div class="oral-content-controls">
            <button type="button" class="btn-toggle-desc" @click="loadQuestionContent(attempt)">
              {{ contentStore[attempt.questionId]?.showDesc ? t('attempt.hideDesc') : t('attempt.showDesc') }}
            </button>
            <button
              type="button"
              class="btn-toggle-desc"
              :class="{ 'btn-revealed': revealState[attempt.id] }"
              @click="toggleReveal(attempt)"
            >
              {{ revealState[attempt.id] ? t('attempt.hidePoints') : t('attempt.revealPoints') }}
            </button>
          </div>

          <div v-if="contentStore[attempt.questionId]?.showDesc" class="question-desc-panel">
            <div class="question-desc-text markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].description)"></div>
            <div v-if="attempt.track === 'PROJECT' && contentStore[attempt.questionId].sections.length" class="section-steps">
              <details
                v-for="section in contentStore[attempt.questionId].sections"
                :key="section.heading"
                class="q-section"
                :class="{
                  'evidence-section': section.heading === sectionRole(attempt.questionId, 'evidence'),
                  'fact-section': section.heading === sectionRole(attempt.questionId, 'fact')
                }"
              >
                <summary>{{ section.heading }}</summary>
                <div class="q-section-body markdown-body" v-html="renderMarkdownCollapsible(section.content)"></div>
              </details>
            </div>
          </div>

          <div v-if="revealState[attempt.id] && contentStore[attempt.questionId]?.loaded" class="reveal-panel">
            <template v-if="attempt.track === 'ORAL'">
              <h4 class="reveal-heading">{{ t('attempt.fullScript') }}</h4>
              <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].answer)"></div>
              <h4 v-if="contentStore[attempt.questionId].followUps" class="reveal-heading">{{ t('attempt.followUps') }}</h4>
              <div v-if="contentStore[attempt.questionId].followUps" class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].followUps)"></div>
            </template>
            <template v-else>
              <h4 class="reveal-heading evidence-heading">{{ t('attempt.evidence') }}</h4>
              <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].evidenceEntry)"></div>
              <h4 class="reveal-heading fact-heading">{{ t('attempt.factBoundary') }}</h4>
              <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].factBoundary)"></div>
            </template>
          </div>
        </div>

        <div class="countdown-bar" :class="{ 'countdown-warning': countdown.isWarning(attempt.id), 'countdown-expired': countdown.getTimer(attempt.id).expired }">
          <template v-if="!countdown.getTimer(attempt.id).running && countdown.getTimer(attempt.id).remaining === 0 && !countdown.getTimer(attempt.id).expired">
            <button type="button" class="btn-timer-start" @click="startTimer(attempt)">
              {{ t('attempt.startTimer', { label: timeLabel(attempt) }) }}
            </button>
          </template>
          <template v-else>
            <span class="countdown-display">{{ countdown.formatRemaining(countdown.getTimer(attempt.id).remaining) }}</span>
            <span v-if="countdown.getTimer(attempt.id).expired" class="countdown-label">{{ t('attempt.expired') }}</span>
            <span v-else-if="countdown.isWarning(attempt.id)" class="countdown-label">{{ t('attempt.warnSoon') }}</span>
            <span v-else class="countdown-label">{{ timeLabel(attempt) }}</span>
            <button v-if="countdown.getTimer(attempt.id).running" type="button" class="btn-timer-sm btn-timer-pause" @click="countdown.pause(attempt.id)">{{ t('attempt.pause') }}</button>
            <button v-else-if="!countdown.getTimer(attempt.id).expired" type="button" class="btn-timer-sm btn-timer-resume" @click="countdown.resume(attempt.id)">{{ t('attempt.resume') }}</button>
            <button type="button" class="btn-timer-sm btn-timer-reset" @click="countdown.reset(attempt.id)">{{ t('attempt.reset') }}</button>
          </template>
        </div>

        <div class="recorder-box">
          <div class="recorder-timer">
            <span class="timer-display">{{ recorder.formatElapsed(recorder.getTimer(attempt.id).elapsed) }}</span>
            <span v-if="recorder.getTimer(attempt.id).running" class="timer-running">{{ t('recorder.timing') }}</span>
          </div>
          <div class="recorder-controls">
            <template v-if="recorder.recordingSupported.value">
              <button
                v-if="!recorder.getRecording(attempt.id).active"
                type="button"
                class="btn-record"
                @click="recorder.startRecording(attempt.id)"
              >{{ t('recorder.startRecording') }}</button>
              <button
                v-else
                type="button"
                class="btn-stop"
                @click="recorder.stopRecording(attempt.id)"
              >{{ t('recorder.stopRecording') }}</button>
            </template>
            <template v-else>
              <button
                v-if="!recorder.getTimer(attempt.id).running"
                type="button"
                @click="recorder.startTimer(attempt.id)"
              >{{ t('recorder.startTimer') }}</button>
              <button
                v-else
                type="button"
                @click="recorder.stopTimer(attempt.id)"
              >{{ t('recorder.stopTimer') }}</button>
            </template>
            <button
              v-if="recorder.getTimer(attempt.id).elapsed > 0 && !recorder.getTimer(attempt.id).running"
              type="button"
              class="btn-reset"
              @click="recorder.discardRecording(attempt.id)"
            >{{ t('recorder.reset') }}</button>
          </div>
          <p v-if="recorder.getRecording(attempt.id).error" class="recorder-error">
            {{ recorder.getRecording(attempt.id).error }}
          </p>
          <audio
            v-if="recorder.getRecording(attempt.id).url"
            :src="recorder.getRecording(attempt.id).url"
            controls
            class="recorder-playback"
          />
        </div>

        <label>
          <span>{{ t('attempt.result') }}</span>
          <select v-model="submitForms[attempt.id].verdict">
            <option value="PASSED">{{ t('attempt.passed') }}</option>
            <option value="FAILED">{{ t('attempt.failed') }}</option>
          </select>
        </label>
        <label>
          <span>{{ t('attempt.durationSeconds') }}</span>
          <input v-model.number="submitForms[attempt.id].durationSeconds" min="0" type="number" />
        </label>
        <label class="check-label">
          <input v-model="submitForms[attempt.id].answerUnlocked" type="checkbox" />
          <span>{{ t('attempt.answerUnlocked') }}</span>
        </label>

        <div v-if="attempt.track !== 'CODING'" class="score-grid">
          <label v-for="dimension in scoreDimensions" :key="dimension.key">
            <span>{{ t('score.' + dimension.key) }}</span>
            <select v-model.number="submitForms[attempt.id][dimension.key]">
              <option :value="0">0</option>
              <option :value="1">1</option>
              <option :value="2">2</option>
            </select>
            <small class="rubric-anchor">{{ rubricAnchor(attempt.track, dimension.key) }}</small>
          </label>
          <strong>{{ t('attempt.totalScore', { score: scoreTotal(submitForms[attempt.id]) }) }}</strong>
        </div>

        <label class="wide-field">
          <span>{{ t('attempt.notes') }}</span>
          <textarea v-model="submitForms[attempt.id].notes" rows="2" :placeholder="t('attempt.notesPlaceholder')" />
        </label>
        <label class="wide-field">
          <span>{{ t('attempt.improvedAnswer') }}</span>
          <textarea v-model="submitForms[attempt.id].improvedAnswer" rows="2" :placeholder="t('attempt.improvedPlaceholder')" />
        </label>
        <button :disabled="trainingBusy" type="button" @click="$emit('submit', attempt)">
          {{ t('attempt.submit') }}
        </button>
      </div>
    </article>
  </div>
</template>
