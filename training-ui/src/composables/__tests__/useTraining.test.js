import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useTraining } from '../useTraining'

function createMockDashboard() {
  return {
    error: { value: '' },
    loadStats: vi.fn().mockResolvedValue(undefined),
    loadHistory: vi.fn().mockResolvedValue(undefined)
  }
}

class MockEventSource {
  constructor(url) {
    this.url = url
    this.listeners = {}
    this.readyState = 0
    MockEventSource.instances.push(this)
  }

  addEventListener(event, handler) {
    if (!this.listeners[event]) this.listeners[event] = []
    this.listeners[event].push(handler)
  }

  set onerror(fn) { this._onerror = fn }
  get onerror() { return this._onerror }

  close() { this.readyState = 2 }

  emit(event, data) {
    for (const handler of this.listeners[event] ?? []) {
      handler({ data: JSON.stringify(data) })
    }
  }
}
MockEventSource.instances = []

describe('useTraining', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    global.EventSource = MockEventSource
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('judgeAttempt opens EventSource to correct URL', () => {
    const dashboard = createMockDashboard()
    const { judgeAttempt } = useTraining(dashboard)

    judgeAttempt({ id: 'att-1', questionId: 'q1' })

    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.instances[0].url).toBe('/api/training/attempts/att-1/judge-stream')
  })

  it('judgeAttempt sets judgeProgress on progress events', () => {
    const dashboard = createMockDashboard()
    const { judgeAttempt, judgeProgress, trainingBusy } = useTraining(dashboard)

    judgeAttempt({ id: 'att-1', questionId: 'q1' })
    expect(trainingBusy.value).toBe(true)
    expect(judgeProgress.value).toEqual({ stage: 'CONNECTING', message: '连接判题服务…' })

    const source = MockEventSource.instances[0]
    source.emit('progress', { stage: 'VALIDATING', message: '校验答题状态' })
    expect(judgeProgress.value).toEqual({ stage: 'VALIDATING', message: '校验答题状态' })

    source.emit('progress', { stage: 'JUDGE_RUNNING', message: 'Maven 编译并执行测试' })
    expect(judgeProgress.value.stage).toBe('JUDGE_RUNNING')
  })

  it('judgeAttempt processes result event and clears progress', async () => {
    const dashboard = createMockDashboard()
    const { judgeAttempt, judgeProgress, judgeResults, trainingBusy, trainingMessage } = useTraining(dashboard)

    judgeAttempt({ id: 'att-1', questionId: 'q1' })
    const source = MockEventSource.instances[0]

    const resultPayload = {
      status: 'PASSED',
      verdict: 'PASSED',
      sessionCompleted: false,
      session: { id: 's1', attempts: [] }
    }
    source.emit('result', resultPayload)

    await vi.waitFor(() => {
      expect(trainingBusy.value).toBe(false)
    })
    expect(judgeProgress.value).toBeNull()
    expect(judgeResults.value['att-1']).toEqual(resultPayload)
    expect(trainingMessage.value).toContain('判题通过')
    expect(dashboard.loadStats).toHaveBeenCalled()
    expect(dashboard.loadHistory).toHaveBeenCalled()
  })

  it('judgeAttempt handles error event', async () => {
    const dashboard = createMockDashboard()
    const { judgeAttempt, trainingBusy } = useTraining(dashboard)

    judgeAttempt({ id: 'att-1', questionId: 'q1' })
    const source = MockEventSource.instances[0]

    source.emit('error', { message: 'Attempt is not in progress' })

    await vi.waitFor(() => {
      expect(trainingBusy.value).toBe(false)
    })
    expect(dashboard.error.value).toBe('Attempt is not in progress')
  })

  it('judgeAttempt streams with API key header when configured', async () => {
    localStorage.setItem('training:apiKey', 'secret-key')
    const encoder = new TextEncoder()
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('event: progress\ndata: {"stage":"VALIDATING","message":"校验答题状态"}\n\n'))
          controller.enqueue(encoder.encode('event: result\ndata: {"status":"PASSED","verdict":"PASSED","sessionCompleted":false,"session":{"id":"s1","attempts":[]}}\n\n'))
          controller.close()
        }
      })
    })
    const dashboard = createMockDashboard()
    const { judgeAttempt, trainingBusy, judgeResults } = useTraining(dashboard)

    await judgeAttempt({ id: 'att-1', questionId: 'q1' })

    expect(global.fetch).toHaveBeenCalledWith('/api/training/attempts/att-1/judge-stream', expect.objectContaining({
      headers: expect.objectContaining({
        Accept: 'text/event-stream',
        'X-API-Key': 'secret-key'
      })
    }))
    expect(trainingBusy.value).toBe(false)
    expect(judgeResults.value['att-1'].status).toBe('PASSED')
  })

  it('judgeAttempt handles CRLF-delimited fetch stream events', async () => {
    localStorage.setItem('training:apiKey', 'secret-key')
    const encoder = new TextEncoder()
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('event: result\r\ndata: {"status":"PASSED","verdict":"PASSED","sessionCompleted":false,"session":{"id":"s1","attempts":[]}}\r\n\r\n'))
          controller.close()
        }
      })
    })
    const dashboard = createMockDashboard()
    const { judgeAttempt, judgeResults } = useTraining(dashboard)

    await judgeAttempt({ id: 'att-1', questionId: 'q1' })

    expect(judgeResults.value['att-1'].status).toBe('PASSED')
  })

  it('sessionForm defaults to CODING track with 3 questions', () => {
    const dashboard = createMockDashboard()
    const { sessionForm } = useTraining(dashboard)
    expect(sessionForm.value.track).toBe('CODING')
    expect(sessionForm.value.count).toBe(3)
  })

  it('activeSessionProgress computes finished/total', () => {
    const dashboard = createMockDashboard()
    const { activeSession, activeSessionProgress } = useTraining(dashboard)
    activeSession.value = {
      id: 's1',
      attempts: [
        { id: 'a1', status: 'FINISHED' },
        { id: 'a2', status: 'IN_PROGRESS' },
        { id: 'a3', status: 'SKIPPED' }
      ]
    }
    expect(activeSessionProgress.value).toBe('2/3')
  })

  it('scoreTotal sums all dimensions', () => {
    const dashboard = createMockDashboard()
    const { scoreTotal } = useTraining(dashboard)
    const form = { correctness: 2, structure: 1, projectEvidence: 1, tradeoff: 2, factRestraint: 1 }
    expect(scoreTotal(form)).toBe(7)
  })
})
