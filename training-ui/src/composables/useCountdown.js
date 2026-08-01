import { reactive, getCurrentScope, onScopeDispose } from 'vue'

/**
 * Countdown timer composable for timed training mode.
 * OD exam rules: 100分题(一星/二星) → 40min, 200分题(三星) → 60min.
 */
export function useCountdown() {
  // { [attemptId]: { total, remaining, running, expired, intervalId } }
  const timers = reactive({})

  function timeLimitFor(difficulty) {
    return difficulty === '三星' ? 60 * 60 : 40 * 60
  }

  function getTimer(attemptId) {
    if (!timers[attemptId]) {
      timers[attemptId] = { total: 0, remaining: 0, running: false, expired: false, intervalId: null }
    }
    return timers[attemptId]
  }

  function start(attemptId, difficulty) {
    const timer = getTimer(attemptId)
    if (timer.running) return
    const total = timeLimitFor(difficulty)
    timer.total = total
    timer.remaining = total
    timer.running = true
    timer.expired = false
    timer.intervalId = setInterval(() => {
      timer.remaining--
      if (timer.remaining <= 0) {
        timer.remaining = 0
        timer.running = false
        timer.expired = true
        clearInterval(timer.intervalId)
        timer.intervalId = null
      }
    }, 1000)
  }

  function pause(attemptId) {
    const timer = getTimer(attemptId)
    if (!timer.running) return
    timer.running = false
    clearInterval(timer.intervalId)
    timer.intervalId = null
  }

  function resume(attemptId) {
    const timer = getTimer(attemptId)
    if (timer.running || timer.expired || timer.remaining <= 0) return
    timer.running = true
    timer.intervalId = setInterval(() => {
      timer.remaining--
      if (timer.remaining <= 0) {
        timer.remaining = 0
        timer.running = false
        timer.expired = true
        clearInterval(timer.intervalId)
        timer.intervalId = null
      }
    }, 1000)
  }

  function reset(attemptId) {
    const timer = getTimer(attemptId)
    if (timer.intervalId) clearInterval(timer.intervalId)
    timer.total = 0
    timer.remaining = 0
    timer.running = false
    timer.expired = false
    timer.intervalId = null
  }

  function formatRemaining(seconds) {
    const m = Math.floor(seconds / 60)
    const s = seconds % 60
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  }

  function isWarning(attemptId) {
    const timer = getTimer(attemptId)
    return timer.running && timer.remaining <= 300 && timer.remaining > 0
  }

  function cleanup() {
    for (const attemptId of Object.keys(timers)) {
      const timer = timers[attemptId]
      if (timer.intervalId) {
        clearInterval(timer.intervalId)
        timer.intervalId = null
      }
      timer.running = false
    }
  }

  if (getCurrentScope()) {
    onScopeDispose(cleanup)
  }

  return { timers, getTimer, start, pause, resume, reset, formatRemaining, isWarning, timeLimitFor, cleanup }
}
