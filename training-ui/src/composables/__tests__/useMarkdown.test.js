import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '../useMarkdown'

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
    const html = renderMarkdown('**bold** and *italic*')
    expect(html).toContain('<strong>bold</strong>')
    expect(html).toContain('<em>italic</em>')
  })
})
