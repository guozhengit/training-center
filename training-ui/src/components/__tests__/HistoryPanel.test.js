import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import HistoryPanel from '../HistoryPanel.vue'
import zh from '../../i18n/zh'
import en from '../../i18n/en'

function mountHistoryPanel() {
  const i18n = createI18n({
    legacy: false,
    locale: 'zh',
    fallbackLocale: 'zh',
    messages: { zh, en }
  })

  return mount(HistoryPanel, {
    global: {
      plugins: [i18n]
    },
    props: {
      historyFilters: {
        track: 'ALL',
        status: 'ALL',
        verdict: 'ALL',
        failedOnly: false
      },
      historyRows: [],
      failedRetrainCount: 0,
      trainingBusy: false,
      formatDate: (value) => value || '-',
      latestJudgement: () => null
    }
  })
}

describe('HistoryPanel', () => {
  it('renders translated filter options instead of iterating i18n keys as characters', () => {
    const wrapper = mountHistoryPanel()

    const selects = wrapper.findAll('select')
    expect(selects).toHaveLength(3)
    expect(selects[0].findAll('option').map((option) => option.text())).toEqual(['全部', '机试题', '口述题', '项目答辩'])
    expect(selects[1].findAll('option').map((option) => option.text())).toEqual(['全部', '进行中', '已完成', '已跳过'])
    expect(selects[2].findAll('option').map((option) => option.text())).toEqual(['全部', '通过', '失败', '无结果'])
  })
})
