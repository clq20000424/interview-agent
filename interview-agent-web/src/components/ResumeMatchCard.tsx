import { useState } from 'react'
import ReactMarkdown from 'react-markdown'

/**
 * 将新旧版本的简历匹配文本统一为标准 Markdown，兼容历史消息中的圆点列表和普通章节标题。
 */
function normalizeResumeMatchMarkdown(content: string) {
  return content
    .replace(/\r\n/g, '\n')
    .replace(/^简历匹配分析完成：\s*\n?/, '')
    .replace(/^•\s*(综合匹配度：[^\n]+)$/m, '**$1**')
    .replace(/^(候选人优势|待提升方面|面试重点考察方向|简历可深挖点)：\s*$/gm, '### $1')
    .replace(/^•\s*/gm, '- ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/**
 * 以默认收起的面板展示简历匹配分析，展开后按标题和列表排版详细内容。
 */
export function ResumeMatchCard({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false)
  const markdown = normalizeResumeMatchMarkdown(content)
  const score = markdown.match(/综合匹配度：([^*\n]+)/)?.[1]?.trim()

  return (
    <div className="my-2">
      <div className="flex justify-center">
        <button
          type="button"
          aria-expanded={expanded}
          onClick={() => setExpanded((current) => !current)}
          className="inline-flex max-w-full items-center gap-2 rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-500 transition hover:bg-gray-200 hover:text-gray-700"
        >
          <span className="truncate">简历匹配完成{score ? `（${score}）` : ''}</span>
          <span className="shrink-0 font-medium text-green-600">{expanded ? '收起' : '展开'}</span>
        </button>
      </div>

      {expanded && (
        <div className="mx-4 mt-2 break-words rounded-lg border border-green-200 bg-green-50 px-5 py-4 text-sm leading-7 text-gray-700 [&_h3]:mt-5 [&_h3]:mb-2 [&_h3]:text-sm [&_h3]:font-semibold [&_h3]:text-green-800 [&_li]:pl-1 [&_p]:my-3 [&_strong]:font-semibold [&_strong]:text-gray-900 [&_ul]:my-2 [&_ul]:list-disc [&_ul]:space-y-2 [&_ul]:pl-5">
          <ReactMarkdown>{markdown}</ReactMarkdown>
        </div>
      )}
    </div>
  )
}
