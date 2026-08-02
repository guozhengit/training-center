import { computed, ref, getCurrentScope, onScopeDispose } from 'vue'
import { useApi } from './useApi'
import { translate } from '../i18n'

const SESSION_STORAGE_KEY = 'training:activeSession'
const JUDGE_TIMEOUT_MS = 155_000

export function useTraining(dashboard) {
  const { fetchJson } = useApi()

  const trainingBusy = ref(false)
  const trainingMessage = ref('')
  const activeSession = ref(restoreSession())
  const submitForms = ref({})
  const judgeResults = ref({})
  const judgeProgress = ref(null)

  let activeEventSource = null
  let judgeTimeoutId = null

  function closeJudgeStream() {
    if (judgeTimeoutId) {
      clearTimeout(judgeTimeoutId)
      judgeTimeoutId = null
    }
    if (activeEventSource) {
      activeEventSource.close()
      activeEventSource = null
    }
  }

  function abortJudge() {
    closeJudgeStream()
    judgeProgress.value = null
    trainingBusy.value = false
  }

  function restoreSession() {
    try {
      const raw = sessionStorage.getItem(SESSION_STORAGE_KEY)
      if (!raw) return null
      const session = JSON.parse(raw)
      if (session && session.id && session.attempts?.length) return session
    } catch { /* corrupted storage, ignore */ }
    return null
  }

  function persistSession(session) {
    try {
      if (session && session.id) {
        sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session))
      } else {
        sessionStorage.removeItem(SESSION_STORAGE_KEY)
      }
    } catch { /* storage full or unavailable, non-critical */ }
  }

  function clearSession() {
    try { sessionStorage.removeItem(SESSION_STORAGE_KEY) } catch { /* ignore */ }
  }

  const sessionForm = ref({
    track: 'CODING',
    count: 3,
    prioritizeWrongAnswers: true,
    dueReviewOnly: false
  })

  const trainingTracks = computed(() => [
    { value: 'CODING', label: translate('dashboard.coding') },
    { value: 'ORAL', label: translate('dashboard.oral') },
    { value: 'PROJECT', label: translate('dashboard.project') }
  ])

  const scoreDimensions = computed(() => [
    { key: 'correctness', label: translate('score.correctness') },
    { key: 'structure', label: translate('score.structure') },
    { key: 'projectEvidence', label: translate('score.projectEvidence') },
    { key: 'tradeoff', label: translate('score.tradeoff') },
    { key: 'factRestraint', label: translate('score.factRestraint') }
  ])

  const activeSessionProgress = computed(() => {
    const attempts = activeSession.value?.attempts ?? []
    if (!attempts.length) return '0/0'
    const finished = attempts.filter((a) => ['FINISHED', 'SKIPPED'].includes(a.status)).length
    return `${finished}/${attempts.length}`
  })

  function initializeSubmitForms(session) {
    const forms = { ...submitForms.value }
    for (const attempt of session?.attempts ?? []) {
      if (!forms[attempt.id]) {
        forms[attempt.id] = {
          verdict: 'PASSED',
          durationSeconds: attempt.track === 'CODING' ? 900 : 120,
          answerUnlocked: false,
          notes: '',
          improvedAnswer: '',
          correctness: 1,
          structure: 1,
          projectEvidence: attempt.track === 'ORAL' ? 1 : 2,
          tradeoff: 1,
          factRestraint: 1
        }
      }
    }
    submitForms.value = forms
  }

  function scorePayload(form) {
    return {
      correctness: Number(form.correctness),
      structure: Number(form.structure),
      projectEvidence: Number(form.projectEvidence),
      tradeoff: Number(form.tradeoff),
      factRestraint: Number(form.factRestraint)
    }
  }

  function scoreTotal(form) {
    return Object.values(scorePayload(form)).reduce((sum, v) => sum + v, 0)
  }

  function focusDimensionsLabel(csv) {
    if (!csv) return ''
    return csv.split(',').map((key) => translate(`score.${key}`)).join('、')
  }

  async function createTrainingSession() {
    trainingBusy.value = true
    trainingMessage.value = ''
    dashboard.error.value = ''
    try {
      const session = await fetchJson('/api/training/sessions', {
        method: 'POST',
        body: JSON.stringify({
          track: sessionForm.value.track,
          count: Number(sessionForm.value.count),
          prioritizeWrongAnswers: sessionForm.value.prioritizeWrongAnswers,
          dueReviewOnly: sessionForm.value.dueReviewOnly
        })
      })
      activeSession.value = session
      initializeSubmitForms(session)
      persistSession(session)
      await dashboard.loadStats()
      await dashboard.loadHistory()
      trainingMessage.value = translate('session.created', { mode: session.mode, count: session.attempts.length })
    } catch (exception) {
      dashboard.error.value = exception.message || translate('session.createFailed')
    } finally {
      trainingBusy.value = false
    }
  }

  async function submitAttempt(attempt) {
    const form = submitForms.value[attempt.id]
    if (!form) return
    trainingBusy.value = true
    trainingMessage.value = ''
    dashboard.error.value = ''
    try {
      const payload = {
        verdict: form.verdict,
        durationSeconds: Number(form.durationSeconds || 0),
        answerUnlocked: form.answerUnlocked,
        notes: form.notes,
        improvedAnswer: form.improvedAnswer
      }
      if (attempt.track !== 'CODING') {
        payload.oralScore = scorePayload(form)
      }
      const result = await fetchJson(`/api/training/attempts/${attempt.id}/submit`, {
        method: 'POST',
        body: JSON.stringify(payload)
      })
      activeSession.value = result.session
      initializeSubmitForms(result.session)
      if (result.sessionCompleted) { clearSession() } else { persistSession(result.session) }
      await dashboard.loadStats()
      await dashboard.loadHistory()
      const focusSuffix = result.focusDimensions
        ? translate('attempt.focus', { dims: focusDimensionsLabel(result.focusDimensions) })
        : ''
      trainingMessage.value = result.sessionCompleted
        ? translate('attempt.sessionDone')
        : translate('attempt.submittedMsg', { id: attempt.questionId, date: formatDate(result.nextReviewAt) }) + focusSuffix
    } catch (exception) {
      dashboard.error.value = exception.message || translate('session.submitFailed')
    } finally {
      trainingBusy.value = false
    }
  }

  async function judgeAttempt(attempt, sourceCode) {
    closeJudgeStream()

    trainingBusy.value = true
    trainingMessage.value = ''
    dashboard.error.value = ''
    judgeProgress.value = { stage: 'CONNECTING', message: translate('attempt.syncSource') }

    if (sourceCode) {
      try {
        await fetchJson(`/api/training/attempts/${attempt.id}/write-source`, {
          method: 'POST',
          body: JSON.stringify({ sourceCode })
        })
      } catch (exception) {
        trainingBusy.value = false
        judgeProgress.value = null
        dashboard.error.value = exception.message || translate('attempt.writeSourceFail')
        return
      }
    }

    judgeProgress.value = { stage: 'CONNECTING', message: translate('attempt.connectJudge') }

    const source = new EventSource(`/api/training/attempts/${attempt.id}/judge-stream`)
    activeEventSource = source

    judgeTimeoutId = setTimeout(() => {
      if (activeEventSource === source) {
        closeJudgeStream()
        judgeProgress.value = null
        trainingBusy.value = false
        dashboard.error.value = translate('attempt.judgeTimeout')
      }
    }, JUDGE_TIMEOUT_MS)

    source.addEventListener('progress', (event) => {
      try {
        judgeProgress.value = JSON.parse(event.data)
      } catch { /* malformed event, ignore */ }
    })

    source.addEventListener('result', async (event) => {
      closeJudgeStream()
      judgeProgress.value = null
      try {
        const result = JSON.parse(event.data)
        judgeResults.value = { ...judgeResults.value, [attempt.id]: result }
        activeSession.value = result.session
        initializeSubmitForms(result.session)
        if (result.sessionCompleted) { clearSession() } else { persistSession(result.session) }
        await dashboard.loadStats()
        await dashboard.loadHistory()
        trainingMessage.value = result.status === 'PASSED'
          ? translate('attempt.judgePassed', { id: attempt.questionId })
          : translate('attempt.judgeFailed', { id: attempt.questionId })
      } catch (exception) {
        dashboard.error.value = translate('attempt.judgeParseError')
      } finally {
        trainingBusy.value = false
      }
    })

    source.addEventListener('error', (event) => {
      closeJudgeStream()
      judgeProgress.value = null
      trainingBusy.value = false
      if (event.data) {
        try {
          const err = JSON.parse(event.data)
          dashboard.error.value = err.message || translate('attempt.judgeFail')
        } catch {
          dashboard.error.value = translate('attempt.judgeFail')
        }
      } else {
        dashboard.error.value = translate('attempt.judgeDisconnected')
      }
    })

    source.onerror = () => {
      if (activeEventSource !== source) return
      closeJudgeStream()
      judgeProgress.value = null
      trainingBusy.value = false
      dashboard.error.value = dashboard.error.value || translate('attempt.judgeConnAbort')
    }
  }

  async function openSandbox(attempt) {
    trainingBusy.value = true
    trainingMessage.value = ''
    dashboard.error.value = ''
    try {
      const result = await fetchJson(`/api/training/attempts/${attempt.id}/open-sandbox`, {
        method: 'POST',
        body: '{}'
      })
      trainingMessage.value = result.message || translate('attempt.openSandboxSuccess', { path: result.sandboxPath })
    } catch (exception) {
      dashboard.error.value = exception.message || translate('attempt.openSandboxFail')
    } finally {
      trainingBusy.value = false
    }
  }

  async function loadSessionFromHistory(entry) {
    trainingBusy.value = true
    trainingMessage.value = ''
    dashboard.error.value = ''
    try {
      const session = await fetchJson(`/api/training/sessions/${entry.attempt.sessionId}`)
      activeSession.value = session
      initializeSubmitForms(session)
      persistSession(session)
      trainingMessage.value = translate('session.loadSuccess', { id: entry.attempt.questionId })
    } catch (exception) {
      dashboard.error.value = exception.message || translate('session.loadFail')
    } finally {
      trainingBusy.value = false
    }
  }

  async function createSessionFromQuestionIds(track, questionIds) {
    trainingBusy.value = true
    trainingMessage.value = ''
    dashboard.error.value = ''
    try {
      const session = await fetchJson('/api/training/sessions', {
        method: 'POST',
        body: JSON.stringify({
          track,
          count: questionIds.length,
          prioritizeWrongAnswers: false,
          dueReviewOnly: false,
          questionIds
        })
      })
      activeSession.value = session
      initializeSubmitForms(session)
      persistSession(session)
      await dashboard.loadStats()
      await dashboard.loadHistory()
      trainingMessage.value = session.attempts.length === 1
        ? translate('session.singleCreated', { id: session.attempts[0].questionId })
        : translate('session.retrainCreated', { count: session.attempts.length })
    } catch (exception) {
      dashboard.error.value = exception.message || translate('session.createRetrainFailed')
    } finally {
      trainingBusy.value = false
    }
  }

  function formatDate(value) {
    if (!value) return '-'
    return new Date(value).toLocaleString('zh-CN', { hour12: false })
  }

  if (getCurrentScope()) {
    onScopeDispose(closeJudgeStream)
  }

  return {
    trainingBusy, trainingMessage, activeSession, submitForms, judgeResults, judgeProgress,
    sessionForm, trainingTracks, scoreDimensions, activeSessionProgress,
    createTrainingSession, submitAttempt, judgeAttempt, openSandbox,
    loadSessionFromHistory, createSessionFromQuestionIds,
    scoreTotal, formatDate, focusDimensionsLabel,
    initSubmitForms: initializeSubmitForms,
    abortJudge
  }
}
