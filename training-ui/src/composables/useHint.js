import { computed, ref } from 'vue'
import { translate } from '../i18n'

/**
 * Client-side progressive hint reveal for starter source code.
 * Mirrors the backend StarterHintService logic for instant UI feedback.
 */
export function useHint() {
  const hintState = ref({})

  const LEVEL_LABELS = computed(() => [
    translate('hint.level0'),
    translate('hint.level1'),
    translate('hint.level2')
  ])

  function getHint(attemptId) {
    if (!hintState.value[attemptId]) {
      hintState.value[attemptId] = { level: -1, content: '', source: '' }
    }
    return hintState.value[attemptId]
  }

  function setSource(attemptId, source) {
    const hint = getHint(attemptId)
    hint.source = source
    hint.level = -1
    hint.content = ''
  }

  function revealNext(attemptId) {
    const hint = getHint(attemptId)
    if (!hint.source || hint.level >= 2) return
    hint.level++
    hint.content = processLevel(hint.source, hint.level)
  }

  function resetHint(attemptId) {
    const hint = getHint(attemptId)
    hint.level = -1
    hint.content = ''
  }

  function processLevel(source, level) {
    switch (level) {
      case 0:
        return signaturesOnly(source)
      case 1:
        return signaturesWithHints(source)
      default:
        return source
    }
  }

  function signaturesOnly(source) {
    const stripped = stripBodies(source)
    return removeHintComments(stripped)
  }

  function signaturesWithHints(source) {
    return stripBodies(source)
  }

  function stripBodies(source) {
    return source.replace(/\{\s*\n([\s\S]*?)(\n\s*\})/g, '{\n        // ...\n    $2')
  }

  function removeHintComments(source) {
    let result = source.replace(/^\s*(\/\/\s*(?:TODO|HINT|NOTE|FIXME).*)$/gm, '')
    result = result.replace(/\/\*\*[\s\S]*?\*\//g, '')
    return result.replace(/\n{3,}/g, '\n\n')
  }

  return {
    hintState, LEVEL_LABELS,
    getHint, setSource, revealNext, resetHint
  }
}
