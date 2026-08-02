import { computed, reactive, ref } from 'vue'
import { useApi } from './useApi'
import { translate } from '../i18n'

export function useDashboard() {
  const { fetchJson } = useApi()

  const loading = ref(true)
  const error = ref('')
  const errors = reactive({ health: null, catalog: null, matrix: null, stats: null, history: null, questions: null })
  const questionsLoading = ref(false)
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

  const tracks = computed(() => [
    { value: 'ALL', label: translate('common.all') },
    { value: 'CODING', label: translate('dashboard.coding') },
    { value: 'ORAL', label: translate('dashboard.oral') },
    { value: 'PROJECT', label: translate('dashboard.project') }
  ])

  const trackCards = computed(() => {
    const counts = catalog.value?.trackCounts ?? {}
    return [
      { label: translate('dashboard.coding'), value: counts.CODING ?? 0, tone: 'blue' },
      { label: translate('dashboard.oral'), value: counts.ORAL ?? 0, tone: 'purple' },
      { label: translate('dashboard.project'), value: counts.PROJECT ?? 0, tone: 'green' }
    ]
  })

  const matrixCards = computed(() => {
    if (!matrix.value?.available) return []
    return [
      { label: translate('matrix.contract'), data: matrix.value.contract },
      { label: translate('matrix.expectedFailures'), data: matrix.value.starterExpectedFailures },
      { label: translate('matrix.referencePasses'), data: matrix.value.referencePasses }
    ]
  })

  const matrixReady = computed(() => {
    return matrix.value?.available && matrix.value.nonPassResults === 0 && matrix.value.totalResults > 0
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
    questionsLoading.value = true
    errors.questions = null
    const params = new URLSearchParams()
    if (selectedTrack.value !== 'ALL') params.set('track', selectedTrack.value)
    if (selectedGroup.value) params.set('group', selectedGroup.value)
    if (selectedTopic.value) params.set('topic', selectedTopic.value)
    if (selectedDifficulty.value) params.set('difficulty', selectedDifficulty.value)
    params.set('limit', '300')
    try {
      const data = await fetchJson(`/api/questions?${params.toString()}`)
      questions.value = data.questions ?? []
    } catch (exception) {
      errors.questions = exception.message || translate('questions.loadFailed')
    } finally {
      questionsLoading.value = false
    }
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
        errors[key] = result.reason?.message || translate('dashboard.loadFailed', { key })
      }
    })

    if (health.value || catalog.value) {
      await loadQuestions()
    }

    const allFailed = results.every((r) => r.status === 'rejected')
    if (allFailed) {
      error.value = translate('dashboard.allDown')
    }

    loading.value = false
  }

  return {
    loading, error, errors, errorList, dismissError,
    health, catalog, matrix, stats, history, questions, selectedTrack,
    selectedGroup, selectedTopic, selectedDifficulty, availableFilters,
    tracks, trackCards, matrixCards, matrixReady, questionsLoading,
    loadQuestions, loadFilters, loadStats, loadHistory, loadDashboard
  }
}
