<script setup>
import { reactive } from 'vue'
import { useRecorder } from '../composables/useRecorder'
import { useHint } from '../composables/useHint'
import { renderMarkdown } from '../composables/useMarkdown'
import CodeEditor from './CodeEditor.vue'

defineProps({
  activeSession: Object,
  submitForms: Object,
  judgeResults: Object,
  trainingBusy: Boolean,
  scoreDimensions: Array
})

defineEmits(['judge', 'submit', 'open-sandbox'])

const recorder = useRecorder()
const hint = useHint()

// Question content store: { [questionId]: { description, starterCode, loaded, showDesc } }
const contentStore = reactive({})
// Code editor store: { [attemptId]: code }
const codeStore = reactive({})

async function loadQuestionContent(attempt) {
  const qid = attempt.questionId
  if (contentStore[qid]?.loaded) {
    contentStore[qid].showDesc = !contentStore[qid].showDesc
    return
  }
  try {
    const response = await fetch(`/api/questions/${qid}/content`)
    if (!response.ok) throw new Error('Failed to load')
    const data = await response.json()
    contentStore[qid] = { description: data.description, starterCode: data.starterCode, loaded: true, showDesc: true }
    // Pre-fill code editor with starter code if empty
    if (!codeStore[attempt.id]) {
      codeStore[attempt.id] = data.starterCode || ''
    }
  } catch {
    contentStore[qid] = { description: '（题目内容加载失败）', starterCode: '', loaded: true, showDesc: true }
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
            <div class="question-desc-text markdown-body" v-html="renderMarkdown(contentStore[attempt.questionId].description)"></div>
          </div>
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
        <button :disabled="trainingBusy" type="button" @click="$emit('judge', attempt)">
          {{ trainingBusy ? '判题中...' : '运行自动判题' }}
        </button>

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

      <div v-else class="attempt-form">
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
