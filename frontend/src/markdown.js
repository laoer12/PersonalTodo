import { Marked } from 'marked'
import DOMPurify from 'dompurify'

const escape = text => text.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
// Keep notes local: remote images and executable HTML are never loaded by preview.
const parser = new Marked({ gfm: true, breaks: false, renderer: {
  html: ({ text }) => escape(text),
  image: ({ text }) => `<span class="memo-image-alt">${escape(text || '图片')}</span>`,
} })
export function renderMarkdown(text) {
  return DOMPurify.sanitize(parser.parse(text), {
    ALLOWED_TAGS: ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'span', 'input'],
    ALLOWED_ATTR: ['href', 'title', 'class', 'type', 'checked', 'disabled', 'start'],
    ALLOW_DATA_ATTR: false,
  })
}
