import { translate } from '../i18n'

export function useExport(dashboard, historyComposable, training) {
  function csvCell(value) {
    const text = value == null ? '' : String(value)
    return `"${text.replaceAll('"', '""')}"`
  }

  function markdownCell(value) {
    return ` ${String(value).replaceAll('|', '\\|')} `
  }

  function downloadText(filename, text, type) {
    const blob = new Blob([text], { type: `${type};charset=utf-8` })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.click()
    URL.revokeObjectURL(url)
  }

  function exportHistoryCsv() {
    const header = ['题号', '类型', '标题', '状态', '结果', '开始时间', '提交时间', '用时秒', '口述总分', '口述五维', '复盘备注', '优化答案', '最近判题', '通过数', '失败数', '沙箱']
    const rows = historyComposable.filteredHistoryRows.value.map((entry) => {
      const a = entry.attempt
      const j = historyComposable.latestJudgement(entry)
      const oral = entry.oralScore
      return [
        a.questionId, a.track, a.title, a.status, a.verdict,
        training.formatDate(a.startedAt), training.formatDate(a.submittedAt),
        a.durationSeconds ?? '', oral?.total ?? '', oral
          ? `${oral.correctness}/${oral.structure}/${oral.projectEvidence}/${oral.tradeoff}/${oral.factRestraint}`
          : '',
        a.notes ?? '', a.improvedAnswer ?? '',
        j?.status ?? '', j?.passedCount ?? '', j?.failedCount ?? '', a.sandboxPath ?? ''
      ]
    })
    downloadText('training-history.csv', '\uFEFF' + [header, ...rows].map((r) => r.map(csvCell).join(',')).join('\n'), 'text/csv')
  }

  function exportHistoryMarkdown() {
    const lines = [
      '# 面试训练复盘历史', '',
      `导出时间：${training.formatDate(new Date().toISOString())}`,
      `数据库：${dashboard.history.value?.databasePath ?? '-'}`, '',
      '| 题号 | 类型 | 标题 | 状态 | 结果 | 口述分 | 复盘备注 | 最近判题 | 通过/失败 | 沙箱 |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |'
    ]
    for (const entry of historyComposable.filteredHistoryRows.value) {
      const a = entry.attempt
      const j = historyComposable.latestJudgement(entry)
      const oral = entry.oralScore
      lines.push([
        a.questionId, a.track, a.title, a.status, a.verdict ?? '-',
        oral ? `${oral.total}/10` : '-', a.notes ?? '-',
        j?.status ?? '-', j ? `${j.passedCount ?? '-'}/${j.failedCount ?? '-'}` : '-', a.sandboxPath ?? '-'
      ].map(markdownCell).join('|'))
    }
    downloadText('training-history.md', `${lines.join('\n')}\n`, 'text/markdown')
  }

  async function copyText(text, label) {
    if (!text) return
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        fallbackCopyText(text)
      }
      training.trainingMessage.value = `${label}${translate('export.copied')}`
    } catch {
      fallbackCopyText(text)
      training.trainingMessage.value = `${label}${translate('export.copied')}`
    }
  }

  function fallbackCopyText(text) {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }

  function copySandboxPath(path) {
    return copyText(path, translate('export.copyPath'))
  }

  function copyCdCommand(path) {
    return copyText(`cd /d "${path}"`, translate('export.copyCd'))
  }

  return { exportHistoryCsv, exportHistoryMarkdown, copySandboxPath, copyCdCommand }
}
