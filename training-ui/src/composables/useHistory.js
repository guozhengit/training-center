import { computed, ref } from 'vue'

export function useHistory(dashboard, training) {
  const activeHistoryDetail = ref(null)
  const historyFilters = ref({
    track: 'ALL',
    status: 'ALL',
    verdict: 'ALL',
    failedOnly: false
  })

  function latestJudgement(entry) {
    const judgements = entry?.judgements ?? []
    return judgements.length ? judgements[judgements.length - 1] : null
  }

  const filteredHistoryRows = computed(() => {
    const filters = historyFilters.value
    return (dashboard.history.value?.attempts ?? []).filter((entry) => {
      const attempt = entry.attempt
      if (filters.track !== 'ALL' && attempt.track !== filters.track) return false
      if (filters.status !== 'ALL' && attempt.status !== filters.status) return false
      if (filters.verdict !== 'ALL' && (attempt.verdict ?? 'NONE') !== filters.verdict) return false
      if (filters.failedOnly && attempt.verdict !== 'FAILED' && latestJudgement(entry)?.status !== 'FAILED') return false
      return true
    })
  })

  const failedRetrainRows = computed(() => {
    const unique = new Map()
    for (const entry of filteredHistoryRows.value) {
      if (entry.attempt.verdict === 'FAILED' || latestJudgement(entry)?.status === 'FAILED') {
        unique.set(entry.attempt.questionId, entry)
      }
    }
    return [...unique.values()].slice(0, 20)
  })

  const failedRetrainTrack = computed(() => {
    const tracks = new Set(failedRetrainRows.value.map((e) => e.attempt.track))
    return tracks.size === 1 ? [...tracks][0] : ''
  })

  function showHistoryDetail(entry) {
    activeHistoryDetail.value = entry
  }

  function closeHistoryDetail() {
    activeHistoryDetail.value = null
  }

  async function createFailedRetrainSession() {
    const rows = failedRetrainRows.value
    if (!rows.length) {
      dashboard.error.value = '当前筛选结果里没有失败题'
      return
    }
    if (!failedRetrainTrack.value) {
      dashboard.error.value = '当前失败题包含多个类型，请先在历史筛选里选择单一类型'
      return
    }
    await training.createSessionFromQuestionIds(
      failedRetrainTrack.value,
      rows.map((e) => e.attempt.questionId)
    )
  }

  return {
    activeHistoryDetail, historyFilters,
    filteredHistoryRows, failedRetrainRows, failedRetrainTrack,
    latestJudgement, showHistoryDetail, closeHistoryDetail, createFailedRetrainSession
  }
}
