import { ref, computed, onUnmounted } from 'vue'
import { translate } from '../i18n'

/**
 * Provides per-attempt timer and audio recording for oral questions.
 * Uses the MediaRecorder API with graceful fallback when unavailable.
 */
export function useRecorder() {
  const recordingState = ref({})
  const timerState = ref({})

  const recordingSupported = computed(() =>
    typeof navigator !== 'undefined'
    && !!navigator.mediaDevices
    && typeof window.MediaRecorder !== 'undefined'
  )

  function getTimer(attemptId) {
    if (!timerState.value[attemptId]) {
      timerState.value[attemptId] = { running: false, elapsed: 0, startedAt: null }
    }
    return timerState.value[attemptId]
  }

  function getRecording(attemptId) {
    if (!recordingState.value[attemptId]) {
      recordingState.value[attemptId] = {
        active: false, url: null, duration: 0, error: null
      }
    }
    return recordingState.value[attemptId]
  }

  function startTimer(attemptId) {
    const timer = getTimer(attemptId)
    if (timer.running) return
    timer.running = true
    timer.startedAt = Date.now() - timer.elapsed * 1000
    timer._interval = setInterval(() => {
      timer.elapsed = Math.floor((Date.now() - timer.startedAt) / 1000)
    }, 1000)
  }

  function stopTimer(attemptId) {
    const timer = getTimer(attemptId)
    if (!timer.running) return
    timer.running = false
    timer.elapsed = Math.floor((Date.now() - timer.startedAt) / 1000)
    clearInterval(timer._interval)
    timer._interval = null
  }

  function resetTimer(attemptId) {
    stopTimer(attemptId)
    const timer = getTimer(attemptId)
    timer.elapsed = 0
    timer.startedAt = null
  }

  function formatElapsed(seconds) {
    const m = Math.floor(seconds / 60)
    const s = seconds % 60
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  }

  async function startRecording(attemptId) {
    if (!recordingSupported.value) {
      const rec = getRecording(attemptId)
      rec.error = translate('recorder.notSupported')
      return
    }
    const rec = getRecording(attemptId)
    rec.error = null
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const mediaRecorder = new MediaRecorder(stream, { mimeType: pickMimeType() })
      const chunks = []

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data)
      }
      mediaRecorder.onstop = () => {
        const blob = new Blob(chunks, { type: mediaRecorder.mimeType })
        rec.url = URL.createObjectURL(blob)
        rec.duration = getTimer(attemptId).elapsed
        stream.getTracks().forEach(track => track.stop())
      }

      mediaRecorder.start()
      rec.active = true
      rec._recorder = mediaRecorder
      startTimer(attemptId)
    } catch (exception) {
      rec.error = exception.name === 'NotAllowedError'
        ? translate('recorder.micDenied')
        : translate('recorder.startFailed', { msg: exception.message })
    }
  }

  function stopRecording(attemptId) {
    const rec = getRecording(attemptId)
    if (!rec.active || !rec._recorder) return
    rec._recorder.stop()
    rec.active = false
    rec._recorder = null
    stopTimer(attemptId)
  }

  function discardRecording(attemptId) {
    const rec = getRecording(attemptId)
    if (rec.url) {
      URL.revokeObjectURL(rec.url)
      rec.url = null
    }
    rec.duration = 0
    resetTimer(attemptId)
  }

  function pickMimeType() {
    const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']
    for (const type of candidates) {
      if (MediaRecorder.isTypeSupported(type)) return type
    }
    return ''
  }

  onUnmounted(() => {
    for (const id of Object.keys(timerState.value)) {
      stopTimer(id)
    }
    for (const id of Object.keys(recordingState.value)) {
      const rec = recordingState.value[id]
      if (rec._recorder && rec.active) {
        rec._recorder.stop()
      }
      if (rec.url) {
        URL.revokeObjectURL(rec.url)
      }
    }
  })

  return {
    recordingSupported, recordingState, timerState,
    getTimer, getRecording, formatElapsed,
    startTimer, stopTimer, resetTimer,
    startRecording, stopRecording, discardRecording
  }
}
