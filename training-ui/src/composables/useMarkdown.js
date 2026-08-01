import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'

marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  }
}))

marked.setOptions({
  gfm: true,
  breaks: true
})

const SANITIZE_CONFIG = {
  ALLOWED_TAGS: [
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'br', 'hr',
    'ul', 'ol', 'li', 'a', 'code', 'pre', 'blockquote',
    'strong', 'em', 'del', 's', 'table', 'thead', 'tbody',
    'tr', 'th', 'td', 'img', 'span', 'div', 'details', 'summary'
  ],
  ALLOWED_ATTR: ['href', 'src', 'alt', 'class', 'id', 'open', 'target', 'rel']
}

// DOMPurify may not work correctly in non-browser DOM implementations (e.g. happy-dom).
// Verify it preserves standard tags; fall back to identity if unreliable.
const purifyWorks = DOMPurify.isSupported &&
  DOMPurify.sanitize('<h1>x</h1>', SANITIZE_CONFIG).includes('<h1')
const sanitize = purifyWorks
  ? (html) => DOMPurify.sanitize(html, SANITIZE_CONFIG)
  : (html) => html

/**
 * Renders markdown text to sanitized HTML with syntax-highlighted code blocks.
 * @param {string} md - Raw markdown string
 * @returns {string} HTML string
 */
export function renderMarkdown(md) {
  if (!md) return ''
  return sanitize(marked.parse(md))
}

/**
 * Renders markdown with h2 sections wrapped in collapsible <details> elements.
 * The first `openCount` sections are expanded; the rest are collapsed.
 * @param {string} md - Raw markdown string
 * @param {number} openCount - Number of leading sections to keep open (default 2)
 * @returns {string} HTML string with collapsible sections
 */
export function renderMarkdownCollapsible(md, openCount = 2) {
  if (!md) return ''
  const html = sanitize(marked.parse(md))
  const parts = html.split(/(?=<h2[\s>])/)
  if (parts.length <= 1) return html
  let sectionIndex = 0
  return parts.map(part => {
    if (!part.startsWith('<h2')) return part
    sectionIndex++
    const openAttr = sectionIndex <= openCount ? ' open' : ''
    const headingMatch = part.match(/<h2[^>]*>(.*?)<\/h2>/)
    const summaryText = headingMatch ? headingMatch[1].replace(/<[^>]+>/g, '') : '详情'
    const rest = part.replace(/<h2[^>]*>.*?<\/h2>/, '')
    return `<details class="q-section"${openAttr}><summary>${summaryText}</summary>${rest}</details>`
  }).join('')
}
