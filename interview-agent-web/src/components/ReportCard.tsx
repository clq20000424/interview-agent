/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useState } from 'react'
import ReactMarkdown from 'react-markdown'

export function ReportCard({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="mx-4 my-3 border border-blue-200 bg-blue-50 rounded-xl overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-blue-100 transition"
      >
        <span className="font-medium text-blue-800">面试评估报告</span>
        <span className="text-blue-600 text-sm">{expanded ? '收起' : '展开'}</span>
      </button>
      {expanded && (
        <div className="px-4 pb-4 prose prose-sm max-w-none">
          <ReactMarkdown>{content}</ReactMarkdown>
        </div>
      )}
    </div>
  )
}
