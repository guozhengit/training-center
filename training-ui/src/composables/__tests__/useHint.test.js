import { describe, it, expect } from 'vitest'
import { useHint } from '../useHint'

const SAMPLE_SOURCE = `public class Solution {
    // TODO: implement two-pointer approach
    public int[] twoSum(int[] nums, int target) {
        int n = nums.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (nums[i] + nums[j] == target) {
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{};
    }
}`

describe('useHint', () => {
  it('initializes hint at level -1 with empty content', () => {
    const { getHint } = useHint()
    const hint = getHint('attempt-1')
    expect(hint.level).toBe(-1)
    expect(hint.content).toBe('')
  })

  it('setSource stores source and resets level', () => {
    const { setSource, getHint } = useHint()
    setSource('a1', SAMPLE_SOURCE)
    const hint = getHint('a1')
    expect(hint.source).toBe(SAMPLE_SOURCE)
    expect(hint.level).toBe(-1)
  })

  it('revealNext progresses through levels 0 → 1 → 2', () => {
    const { setSource, revealNext, getHint } = useHint()
    setSource('a1', SAMPLE_SOURCE)

    revealNext('a1')
    expect(getHint('a1').level).toBe(0)
    expect(getHint('a1').content).not.toBe('')

    revealNext('a1')
    expect(getHint('a1').level).toBe(1)

    revealNext('a1')
    expect(getHint('a1').level).toBe(2)
    expect(getHint('a1').content).toBe(SAMPLE_SOURCE)
  })

  it('revealNext does not exceed level 2', () => {
    const { setSource, revealNext, getHint } = useHint()
    setSource('a1', SAMPLE_SOURCE)
    revealNext('a1')
    revealNext('a1')
    revealNext('a1')
    revealNext('a1')
    expect(getHint('a1').level).toBe(2)
  })

  it('revealNext does nothing without source', () => {
    const { revealNext, getHint } = useHint()
    revealNext('a1')
    expect(getHint('a1').level).toBe(-1)
  })

  it('level 0 strips bodies and hint comments', () => {
    const { setSource, revealNext, getHint } = useHint()
    setSource('a1', SAMPLE_SOURCE)
    revealNext('a1')
    const content = getHint('a1').content
    expect(content).not.toContain('TODO')
    expect(content).toContain('// ...')
  })

  it('level 1 strips bodies but keeps structure', () => {
    const { setSource, revealNext, getHint } = useHint()
    setSource('a1', SAMPLE_SOURCE)
    revealNext('a1')
    revealNext('a1')
    const content = getHint('a1').content
    expect(content).toContain('// ...')
    expect(content).not.toContain('nums[i] + nums[j]')
  })

  it('resetHint returns to level -1', () => {
    const { setSource, revealNext, resetHint, getHint } = useHint()
    setSource('a1', SAMPLE_SOURCE)
    revealNext('a1')
    revealNext('a1')
    resetHint('a1')
    expect(getHint('a1').level).toBe(-1)
    expect(getHint('a1').content).toBe('')
  })

  it('LEVEL_LABELS has 3 entries', () => {
    const { LEVEL_LABELS } = useHint()
    expect(LEVEL_LABELS).toHaveLength(3)
    expect(LEVEL_LABELS[0]).toBe('仅签名')
    expect(LEVEL_LABELS[2]).toBe('完整源码')
  })
})
