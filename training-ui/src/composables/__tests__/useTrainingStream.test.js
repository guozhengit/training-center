import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useTraining } from '../useTraining'
import { translate } from '../../i18n'

const attempt = { id: 'att-stream', questionId: 'q-stream' }
const result = {
  status: 'PASSED',
  sessionCompleted: false,
  session: { id: 's-stream', attempts: [] }
}
const encoder = new TextEncoder()

function createTraining() {
  const dashboard = {
    error: { value: '' },
    loadStats: vi.fn().mockResolvedValue(undefined),
    loadHistory: vi.fn().mockResolvedValue(undefined)
  }
  return { ...useTraining(dashboard), dashboard }
}

function mockStream(chunks) {
  const body = new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    }
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body }))
  return body
}

describe('authenticated judge stream lifecycle', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    localStorage.setItem('training:apiKey', 'test-stream-key')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('preserves CRLF event boundaries and UTF-8 text split across network chunks', async () => {
    const payload = { ...result, message: '判题完成' }
    const bytes = encoder.encode(`event: result\r\ndata: ${JSON.stringify(payload)}\r\n\r\n`)
    mockStream(Array.from(bytes, (byte) => Uint8Array.of(byte)))
    const training = createTraining()

    await training.judgeAttempt(attempt)

    expect(training.judgeResults.value[attempt.id]).toEqual(payload)
    expect(training.dashboard.error.value).toBe('')
  })

  it('reports a disconnected stream when it ends before a result', async () => {
    mockStream([encoder.encode('event: progress\ndata: {"stage":"JUDGE_RUNNING"}\n\n')])
    const training = createTraining()

    await training.judgeAttempt(attempt)

    expect(training.dashboard.error.value).toBe(translate('attempt.judgeDisconnected'))
    expect(training.judgeProgress.value).toBeNull()
    expect(training.trainingBusy.value).toBe(false)
    expect(training.judgeResults.value[attempt.id]).toBeUndefined()
  })

  it('finishes on a result even when the server keeps the connection open', async () => {
    let canceled = false
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify(result)}\n\n`))
      },
      cancel() { canceled = true }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body }))
    const training = createTraining()
    const judging = training.judgeAttempt(attempt)

    await vi.waitFor(() => expect(training.trainingBusy.value).toBe(false))
    await judging

    expect(training.judgeResults.value[attempt.id]).toEqual(result)
    expect(training.dashboard.error.value).toBe('')
    expect(canceled).toBe(true)
    expect(body.locked).toBe(false)
  })

  it('does not show a connection error after the user cancels', async () => {
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    const training = createTraining()
    const judging = training.judgeAttempt(attempt)

    training.abortJudge()
    await judging

    expect(training.dashboard.error.value).toBe('')
    expect(training.trainingBusy.value).toBe(false)
    expect(training.judgeProgress.value).toBeNull()
  })

  it('keeps the replacement request busy and preserves its timeout after cancellation', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    const training = createTraining()
    const first = training.judgeAttempt(attempt)
    const second = training.judgeAttempt({ id: 'att-replacement', questionId: 'q2' })
    await first
    const stateAfterFirst = {
      busy: training.trainingBusy.value,
      error: training.dashboard.error.value
    }

    await vi.advanceTimersByTimeAsync(155_000)
    const timeoutError = training.dashboard.error.value
    training.abortJudge()
    await second

    expect(stateAfterFirst).toEqual({ busy: true, error: '' })
    expect(timeoutError).toBe(translate('attempt.judgeTimeout'))
  })
})
