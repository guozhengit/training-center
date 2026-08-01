<script setup>
import { reactive } from 'vue'
import { useRecorder } from '../composables/useRecorder'
import { useHint } from '../composables/useHint'
import { useCountdown } from '../composables/useCountdown'
import { useApi } from '../composables/useApi'
import { renderMarkdownCollapsible } from '../composables/useMarkdown'
import CodeEditor from './CodeEditor.vue'

const { fetchJson } = useApi()

defineProps({
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

// Question content store: { [questionId]: { description, starterCode, answer, followUps, sections, evidenceEntry, factBoundary, recommendedSeconds, loaded, showDesc } }
const contentStore = reactive({})
// Reference-answer reveal state per attempt: { [attemptId]: boolean }
const revealState = reactive({})
// Code editor store: { [attemptId]: code }
const codeStore = reactive({})

async function loadQuestionContent(attempt) {
  const qid = attempt.questionId
  if (contentStore[qid]?.loaded) {
    contentStore[qid].showDesc = !contentStore[qid].showDesc
    return
  }
  try {
    const data = await fetchJson(`/api/questions/${qid}/content`)
    contentStore[qid] = {
      description: data.description,
      starterCode: data.starterCode,
      answer: data.answer,
      followUps: data.followUps,
      sections: data.sections ?? [],
      evidenceEntry: data.evidenceEntry,
      factBoundary: data.factBoundary,
      recommendedSeconds: data.recommendedSeconds,
      loaded: true,
      showDesc: true
    }
    // Pre-fill code editor with starter code if empty
    if (!codeStore[attempt.id]) {
      codeStore[attempt.id] = data.starterCode || ''
    }
    if (data.starterCode) {
      hint.setSource(attempt.id, data.starterCode)
    }
  } catch {
    contentStore[qid] = {
      description: '（题目内容加载失败）', starterCode: '', answer: '', followUps: '',
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
  return codeStore[attemptId] ?? ''
}

function setCode(attemptId, value) {
  codeStore[attemptId] = value
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
  if (seconds) return `推荐 ${seconds} 秒作答`
  return attempt.difficulty === '三星' ? '60 min' : '40 min'
}

function sectionRole(qid, role) {
  const content = contentStore[qid]
  if (!content?.sections?.length) return ''
  const target = role === 'evidence' ? content.evidenceEntry : content.factBoundary
  const found = content.sections.find((s) => s.content === target)
  return found ? found.heading : ''
}

const rubricAnchors = {
  ORAL: {
    correctness: '对照「完整口述稿」核对要点覆盖度',
    structure: '是否先结论后展开（总-分-总）',
    projectEvidence: '是否给出具体项目实例支撑',
    tradeoff: '是否说明取舍与备选方案',
    factRestraint: '是否虚构指标/所有权；打 0 分强制重练'
  },
  PROJECT: {
    correctness: '是否覆盖案例核心结论',
    structure: '是否按「结论-证据-取舍」结构化陈述',
    projectEvidence: '逐条对照「可验证依据」章节',
    tradeoff: '是否说明技术选型取舍理由',
    factRestraint: '逐条对照「不支持声称警示」；打 0 分强制重练'
  }
}

function rubricAnchor(track, key) {
  return (rubricAnchors[track] ?? rubricAnchors.ORAL)[key] || ''
}
</script>

<template>
  <div v-if="activeSession" class="active-session">
    <header>
      <div>
        <h3>{{ activeSession.mode }} 训练</h3>
        <p>Session：{{ activeSession.id }}</p>
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
        <span>结果：{{ attempt.verdict }}</span>
        <span>用时：{{ attempt.durationSeconds ?? '-' }} 秒</span>
      </div>

      <div v-else-if="attempt.track === 'CODING'" class="coding-judge-box">
        <!-- Question content viewer -->
        <div class="question-content-section">
          <button type="button" class="btn-toggle-desc" @click="loadQuestionContent(attempt)">
            {{ contentStore[attempt.questionId]?.showDesc ? '收起题目' : '查看题目' }}
          </button>
          <div v-if="contentStore[attempt.questionId]?.showDesc" class="question-desc-panel">
            <div class="question-desc-text markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].description)"></div>
          </div>
        </div>

        <!-- Countdown timer -->
        <div class="countdown-bar" :class="{ 'countdown-warning': countdown.isWarning(attempt.id), 'countdown-expired': countdown.getTimer(attempt.id).expired }">
          <template v-if="!countdown.getTimer(attempt.id).running && countdown.getTimer(attempt.id).remaining === 0 && !countdown.getTimer(attempt.id).expired">
            <button type="button" class="btn-timer-start" @click="countdown.start(attempt.id, attempt.difficulty)">
              开始限时（{{ attempt.difficulty === '三星' ? '60' : '40' }}min）
            </button>
          </template>
          <template v-else>
            <span class="countdown-display">{{ countdown.formatRemaining(countdown.getTimer(attempt.id).remaining) }}</span>
            <span v-if="countdown.getTimer(attempt.id).expired" class="countdown-label">时间到!</span>
            <span v-else-if="countdown.isWarning(attempt.id)" class="countdown-label">剩余不足5分钟</span>
            <span v-else class="countdown-label">{{ attempt.difficulty === '三星' ? '200分题 / 60min' : '100分题 / 40min' }}</span>
            <button v-if="countdown.getTimer(attempt.id).running" type="button" class="btn-timer-sm btn-timer-pause" @click="countdown.pause(attempt.id)">⏸ 暂停</button>
            <button v-else-if="!countdown.getTimer(attempt.id).expired" type="button" class="btn-timer-sm btn-timer-resume" @click="countdown.resume(attempt.id)">▶ 继续</button>
            <button type="button" class="btn-timer-sm btn-timer-reset" @click="countdown.reset(attempt.id)">↺ 重置</button>
          </template>
        </div>

        <!-- Code editor -->
        <div class="code-editor-section">
          <label class="code-editor-label">代码编辑区（{{ attempt.language }}）</label>
          <CodeEditor
            :model-value="getCode(attempt.id)"
            :language="attempt.language"
            @update:model-value="setCode(attempt.id, $event)"
          />
        </div>

        <p class="muted">
          自动判题会先创建隔离沙箱；如需修改代码，可在返回的 sandboxPath 中编辑后再次运行判题。
        </p>
        <div class="finished-row" v-if="attempt.sandboxPath">
          <span>沙箱：{{ attempt.sandboxPath }}</span>
        </div>
        <button :disabled="trainingBusy" type="button" @click="$emit('judge', attempt, getCode(attempt.id))">
          {{ trainingBusy ? '判题中...' : '运行自动判题' }}
        </button>

        <div v-if="judgeProgress" class="judge-progress">
          <div class="judge-progress-bar">
            <div class="judge-progress-fill" :data-stage="judgeProgress.stage"></div>
          </div>
          <span class="judge-progress-text">{{ judgeProgress.message }}</span>
        </div>

        <div v-if="judgeResults[attempt.id]" class="judge-result">
          <strong>判题结果：{{ judgeResults[attempt.id].status }}</strong>
          <span>Verdict：{{ judgeResults[attempt.id].verdict }}</span>
          <span>
            Tests：{{ judgeResults[attempt.id].passedCount ?? '-' }}/{{ judgeResults[attempt.id].failedCount ?? '-' }}
          </span>
          <span>耗时：{{ judgeResults[attempt.id].durationMillis }} ms</span>
          <span>Sandbox：{{ judgeResults[attempt.id].sandboxPath }}</span>
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
              {{ hint.getHint(attempt.id).level < 0 ? '查看提示' : '更多提示' }}
            </button>
            <span v-if="hint.getHint(attempt.id).level >= 0" class="hint-level">
              {{ hint.LEVEL_LABELS[hint.getHint(attempt.id).level] }}
            </span>
            <button
              v-if="hint.getHint(attempt.id).level >= 0"
              type="button"
              class="btn-reset"
              @click="hint.resetHint(attempt.id)"
            >收起</button>
          </div>
          <pre v-if="hint.getHint(attempt.id).content" class="hint-content">{{ hint.getHint(attempt.id).content }}</pre>
        </div>
      </div>

      <div v-else-if="submitForms[attempt.id]" class="attempt-form">
        <div class="question-content-section">
          <div class="oral-content-controls">
            <button type="button" class="btn-toggle-desc" @click="loadQuestionContent(attempt)">
              {{ contentStore[attempt.questionId]?.showDesc ? '收起题目' : '查看题目' }}
            </button>
            <button
              type="button"
              class="btn-toggle-desc"
              :class="{ 'btn-revealed': revealState[attempt.id] }"
              @click="toggleReveal(attempt)"
            >
              {{ revealState[attempt.id] ? '收起参考要点' : '揭晓参考要点' }}
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
              <h4 class="reveal-heading">完整口述稿</h4>
              <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].answer)"></div>
              <h4 v-if="contentStore[attempt.questionId].followUps" class="reveal-heading">继续追问</h4>
              <div v-if="contentStore[attempt.questionId].followUps" class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].followUps)"></div>
            </template>
            <template v-else>
              <h4 class="reveal-heading evidence-heading">可验证依据</h4>
              <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].evidenceEntry)"></div>
              <h4 class="reveal-heading fact-heading">不支持声称警示</h4>
              <div class="markdown-body" v-html="renderMarkdownCollapsible(contentStore[attempt.questionId].factBoundary)"></div>
            </template>
          </div>
        </div>

        <div class="countdown-bar" :class="{ 'countdown-warning': countdown.isWarning(attempt.id), 'countdown-expired': countdown.getTimer(attempt.id).expired }">
          <template v-if="!countdown.getTimer(attempt.id).running && countdown.getTimer(attempt.id).remaining === 0 && !countdown.getTimer(attempt.id).expired">
            <button type="button" class="btn-timer-start" @click="startTimer(attempt)">
              开始限时（{{ timeLabel(attempt) }}）
            </button>
          </template>
          <template v-else>
            <span class="countdown-display">{{ countdown.formatRemaining(countdown.getTimer(attempt.id).remaining) }}</span>
            <span v-if="countdown.getTimer(attempt.id).expired" class="countdown-label">时间到!</span>
            <span v-else-if="countdown.isWarning(attempt.id)" class="countdown-label">即将超时</span>
            <span v-else class="countdown-label">{{ timeLabel(attempt) }}</span>
            <button v-if="countdown.getTimer(attempt.id).running" type="button" class="btn-timer-sm btn-timer-pause" @click="countdown.pause(attempt.id)">⏸ 暂停</button>
            <button v-else-if="!countdown.getTimer(attempt.id).expired" type="button" class="btn-timer-sm btn-timer-resume" @click="countdown.resume(attempt.id)">▶ 继续</button>
            <button type="button" class="btn-timer-sm btn-timer-reset" @click="countdown.reset(attempt.id)">↺ 重置</button>
          </template>
        </div>

        <div class="recorder-box">
          <div class="recorder-timer">
            <span class="timer-display">{{ recorder.formatElapsed(recorder.getTimer(attempt.id).elapsed) }}</span>
            <span v-if="recorder.getTimer(attempt.id).running" class="timer-running">计时中</span>
          </div>
          <div class="recorder-controls">
            <template v-if="recorder.recordingSupported.value">
              <button
                v-if="!recorder.getRecording(attempt.id).active"
                type="button"
                class="btn-record"
                @click="recorder.startRecording(attempt.id)"
              >开始录音</button>
              <button
                v-else
                type="button"
                class="btn-stop"
                @click="recorder.stopRecording(attempt.id)"
              >停止录音</button>
            </template>
            <template v-else>
              <button
                v-if="!recorder.getTimer(attempt.id).running"
                type="button"
                @click="recorder.startTimer(attempt.id)"
              >开始计时</button>
              <button
                v-else
                type="button"
                @click="recorder.stopTimer(attempt.id)"
              >停止计时</button>
            </template>
            <button
              v-if="recorder.getTimer(attempt.id).elapsed > 0 && !recorder.getTimer(attempt.id).running"
              type="button"
              class="btn-reset"
              @click="recorder.discardRecording(attempt.id)"
            >重置</button>
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
          <span>结果</span>
          <select v-model="submitForms[attempt.id].verdict">
            <option value="PASSED">通过 / 表达达标</option>
            <option value="FAILED">失败 / 需要复习</option>
          </select>
        </label>
        <label>
          <span>用时（秒）</span>
          <input v-model.number="submitForms[attempt.id].durationSeconds" min="0" type="number" />
        </label>
        <label class="check-label">
          <input v-model="submitForms[attempt.id].answerUnlocked" type="checkbox" />
          <span>已看答案</span>
        </label>

        <div v-if="attempt.track !== 'CODING'" class="score-grid">
          <label v-for="dimension in scoreDimensions" :key="dimension.key">
            <span>{{ dimension.label }}</span>
            <select v-model.number="submitForms[attempt.id][dimension.key]">
              <option :value="0">0</option>
              <option :value="1">1</option>
              <option :value="2">2</option>
            </select>
            <small class="rubric-anchor">{{ rubricAnchor(attempt.track, dimension.key) }}</small>
          </label>
          <strong>总分 {{ scoreTotal(submitForms[attempt.id]) }}/10</strong>
        </div>

        <label class="wide-field">
          <span>复盘备注</span>
          <textarea v-model="submitForms[attempt.id].notes" rows="2" placeholder="哪里卡住、下次怎么说得更好" />
        </label>
        <label class="wide-field">
          <span>优化后的答案</span>
          <textarea v-model="submitForms[attempt.id].improvedAnswer" rows="2" placeholder="沉淀一版 1-3 分钟口述稿或关键思路" />
        </label>
        <button :disabled="trainingBusy" type="button" @click="$emit('submit', attempt)">
          记录本题结果
        </button>
      </div>
    </article>
  </div>
</template>
