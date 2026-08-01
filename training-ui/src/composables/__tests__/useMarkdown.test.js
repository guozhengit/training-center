import { describe, it, expect } from 'vitest'
import { renderMarkdown, renderMarkdownCollapsible } from '../useMarkdown'

describe('renderMarkdown', () => {
  it('returns empty string for null/undefined input', () => {
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
    expect(renderMarkdown('')).toBe('')
  })

  it('renders headings', () => {
    const html = renderMarkdown('# Hello')
    expect(html).toContain('<h1')
    expect(html).toContain('Hello')
  })

  it('renders paragraphs with GFM breaks', () => {
    const html = renderMarkdown('line1\nline2')
    expect(html).toContain('<br')
  })

  it('renders code blocks with language class', () => {
    const md = '```java\npublic class Main {}\n```'
    const html = renderMarkdown(md)
    expect(html).toContain('<code')
    expect(html).toContain('java')
  })

  it('renders unordered lists', () => {
    const html = renderMarkdown('- item1\n- item2')
    expect(html).toContain('<ul')
    expect(html).toContain('<li')
    expect(html).toContain('item1')
  })

  it('renders inline code', () => {
    const html = renderMarkdown('use `Arrays.sort()` here')
    expect(html).toContain('<code')
    expect(html).toContain('Arrays.sort()')
  })

  it('renders bold and italic', () => {
    const md = '**bold** and *italic*'
    expect(renderMarkdown(md)).toContain('<strong>bold</strong>')
    expect(renderMarkdown(md)).toContain('<em>italic</em>')
  })
})

describe('renderMarkdownCollapsible', () => {
  const md = '## 题目描述\nbody A\n## 示例\nbody B\n## 解题思路\nbody C'

  it('wraps h2 sections in details', () => {
    const html = renderMarkdownCollapsible(md)
    expect(html.match(/<details/g)).toHaveLength(3)
    expect(html).toContain('<summary>题目描述</summary>')
    expect(html).toContain('<summary>解题思路</summary>')
  })

  it('expands all sections by default so 解题思路 is visible', () => {
    const html = renderMarkdownCollapsible(md)
    expect(html).toContain('<details class="q-section" open>')
    expect(html.match(/<details class="q-section" open>/g)).toHaveLength(3)
  })

  it('only expands leading sections when openCount is provided', () => {
    const html = renderMarkdownCollapsible(md, 2)
    expect(html.match(/<details class="q-section" open>/g)).toHaveLength(2)
    expect(html).toContain('<details class="q-section"><summary>解题思路</summary>')
  })

  it('returns empty string for empty input', () => {
    expect(renderMarkdownCollapsible('')).toBe('')
    expect(renderMarkdownCollapsible(null)).toBe('')
  })
})
