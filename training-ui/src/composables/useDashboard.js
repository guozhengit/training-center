import { computed, reactive, ref } from 'vue'
import { useApi } from './useApi'

export function useDashboard() {
  const { fetchJson } = useApi()

  const loading = ref(true)
  const error = ref('')
  const errors = reactive({ health: null, catalog: null, matrix: null, stats: null, history: null })
  const health = ref(null)
  const catalog = ref(null)
  const matrix = ref(null)
  const stats = ref(null)
  const history = ref(null)
  const questions = ref([])
  const selectedTrack = ref('ALL')
  const selectedGroup = ref('')
  const selectedTopic = ref('')
  const selectedDifficulty = ref('')
  const availableFilters = ref({ groups: [], topics: [], difficulties: [] })

  const tracks = [
    { value: 'ALL', label: '全部' },
    { value: 'CODING', label: '机试题' },
    { value: 'ORAL', label: '口述题' },
    { value: 'PROJECT', label: '项目答辩' }
  ]

  const trackCards = computed(() => {
    const counts = catalog.value?.trackCounts ?? {}
    return [
      { label: '机试题', value: counts.CODING ?? 0, tone: 'blue' },
      { label: '口述题', value: counts.ORAL ?? 0, tone: 'purple' },
      { label: '项目答辩', value: counts.PROJECT ?? 0, tone: 'green' }
    ]
  })

  const matrixCards = computed(() => {
    if (!matrix.value?.available) return []
    return [
      { label: '契约测试', data: matrix.value.contract },
      { label: 'Starter 预期失败', data: matrix.value.starterExpectedFailures },
      { label: '参考答案通过', data: matrix.value.referencePasses }
    ]
  })

  const matrixReady = computed(() => {
    return matrix.value?.available && matrix.value.nonPassResults === 0 && matrix.value.totalResults === 360
  })

  const errorList = computed(() => {
    return Object.entries(errors)
      .filter(([, msg]) => msg)
      .map(([key, msg]) => ({ key, msg }))
  })

  function dismissError(key) {
    errors[key] = null
  }

  async function loadQuestions() {
    const params = new URLSearchParams()
    if (selectedTrack.value !== 'ALL') params.set('track', selectedTrack.value)
    if (selectedGroup.value) params.set('group', selectedGroup.value)
    if (selectedTopic.value) params.set('topic', selectedTopic.value)
    if (selectedDifficulty.value) params.set('difficulty', selectedDifficulty.value)
    params.set('limit', '300')
    const data = await fetchJson(`/api/questions?${params.toString()}`)
    questions.value = data.questions ?? []
  }

  async function loadFilters() {
    const params = new URLSearchParams()
    if (selectedTrack.value !== 'ALL') params.set('track', selectedTrack.value)
    try {
      availableFilters.value = await fetchJson(`/api/questions/filters?${params.toString()}`)
    } catch { /* non-critical */ }
  }

  async function loadStats() {
    stats.value = await fetchJson('/api/training/stats')
  }

  async function loadHistory() {
    history.value = await fetchJson('/api/training/history?limit=30')
  }

  async function loadDashboard() {
    loading.value = true
    error.value = ''
    Object.keys(errors).forEach((k) => { errors[k] = null })

    const endpoints = [
      { key: 'health', fn: () => fetchJson('/api/health') },
      { key: 'catalog', fn: () => fetchJson('/api/catalog/summary') },
      { key: 'matrix', fn: () => fetchJson('/api/matrix-report') },
      { key: 'stats', fn: () => fetchJson('/api/training/stats') },
      { key: 'history', fn: () => fetchJson('/api/training/history?limit=30') }
    ]

    const results = await Promise.allSettled(endpoints.map((e) => e.fn()))

    results.forEach((result, index) => {
      const key = endpoints[index].key
      if (result.status === 'fulfilled') {
        if (key === 'health') health.value = result.value
        else if (key === 'catalog') catalog.value = result.value
        else if (key === 'matrix') matrix.value = result.value
        else if (key === 'stats') stats.value = result.value
        else if (key === 'history') history.value = result.value
      } else {
        errors[key] = result.reason?.message || `${key} 加载失败`
      }
    })

    if (health.value || catalog.value) {
      try { await loadQuestions() } catch { /* non-critical */ }
    }

    const allFailed = results.every((r) => r.status === 'rejected')
    if (allFailed) {
      error.value = '所有接口均不可用，请确认后端已启动（localhost:8080）'
    }

    loading.value = false
  }

  return {
    loading, error, errors, errorList, dismissError,
    health, catalog, matrix, stats, history, questions, selectedTrack,
    selectedGroup, selectedTopic, selectedDifficulty, availableFilters,
    tracks, trackCards, matrixCards, matrixReady,
    loadQuestions, loadFilters, loadStats, loadHistory, loadDashboard
  }
}
