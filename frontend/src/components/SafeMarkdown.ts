import { defineComponent, h, type PropType, type VNodeChild } from 'vue'

const INLINE = /(\*\*([^*\n]+)\*\*|__([^_\n]+)__|`([^`\n]+)`|\[([^\]\n]+)\]\(([^)\s]+)\)|\*([^*\n]+)\*)/g

function safeLink(value: string): string | null {
  return /^(https?:|mailto:)/i.test(value) ? value : null
}

function inline(text: string): VNodeChild[] {
  const children: VNodeChild[] = []
  let cursor = 0
  for (const match of text.matchAll(INLINE)) {
    const index = match.index ?? 0
    if (index > cursor) children.push(text.slice(cursor, index))
    if (match[2] || match[3]) children.push(h('strong', match[2] || match[3]))
    else if (match[4]) children.push(h('code', match[4]))
    else if (match[5] && match[6]) {
      const link = safeLink(match[6])
      children.push(link
        ? h('a', { href: link, target: '_blank', rel: 'noopener noreferrer' }, match[5])
        : match[0])
    } else if (match[7]) children.push(h('em', match[7]))
    cursor = index + match[0].length
  }
  if (cursor < text.length) children.push(text.slice(cursor))
  return children
}

function inlineLines(lines: string[]): VNodeChild[] {
  return lines.flatMap((line, index) => index === 0 ? inline(line) : [h('br'), ...inline(line)])
}

function isBlockStart(line: string): boolean {
  return /^(#{1,6})\s+|^```|^>\s?|^[-*+]\s+|^\d+\.\s+|^\s*(---+|___+)\s*$/.test(line)
}

function blocks(source: string): VNodeChild[] {
  const lines = source.replace(/\r\n?/g, '\n').split('\n')
  const result: VNodeChild[] = []
  let index = 0
  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) { index += 1; continue }

    const fence = line.match(/^```([^`]*)$/)
    if (fence) {
      const code: string[] = []
      index += 1
      while (index < lines.length && !/^```\s*$/.test(lines[index])) code.push(lines[index++])
      if (index < lines.length) index += 1
      result.push(h('pre', [h('code', { class: fence[1].trim() ? `language-${fence[1].trim()}` : undefined }, code.join('\n'))]))
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/)
    if (heading) {
      result.push(h(`h${heading[1].length}`, inline(heading[2])))
      index += 1
      continue
    }

    if (/^\s*(---+|___+)\s*$/.test(line)) {
      result.push(h('hr'))
      index += 1
      continue
    }

    if (/^>\s?/.test(line)) {
      const quote: string[] = []
      while (index < lines.length && /^>\s?/.test(lines[index])) quote.push(lines[index++].replace(/^>\s?/, ''))
      result.push(h('blockquote', inlineLines(quote)))
      continue
    }

    const unordered = line.match(/^[-*+]\s+(.+)$/)
    if (unordered) {
      const items: VNodeChild[] = []
      while (index < lines.length) {
        const item = lines[index].match(/^[-*+]\s+(.+)$/)
        if (!item) break
        items.push(h('li', inline(item[1])))
        index += 1
      }
      result.push(h('ul', items))
      continue
    }

    const ordered = line.match(/^\d+\.\s+(.+)$/)
    if (ordered) {
      const items: VNodeChild[] = []
      while (index < lines.length) {
        const item = lines[index].match(/^\d+\.\s+(.+)$/)
        if (!item) break
        items.push(h('li', inline(item[1])))
        index += 1
      }
      result.push(h('ol', items))
      continue
    }

    const paragraph = [line]
    index += 1
    while (index < lines.length && lines[index].trim() && !isBlockStart(lines[index])) paragraph.push(lines[index++])
    result.push(h('p', inlineLines(paragraph)))
  }
  return result
}

export default defineComponent({
  name: 'SafeMarkdown',
  props: { source: { type: String as PropType<string>, required: true } },
  setup(props) {
    return () => h('div', { class: 'markdown-preview' }, blocks(props.source))
  },
})
