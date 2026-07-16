import {useState} from 'react'
import ReactMarkdown from 'react-markdown'

interface PlanningDetailsCardProps {
    summary: string
    content: string
}

/**
 * 以流程节点形式展示出题方向或最终计划，点击后展开结构化 Markdown 明细。
 */
export function PlanningDetailsCard({summary, content}: PlanningDetailsCardProps) {
    const [expanded, setExpanded] = useState(false)

    return (
        <div className="my-2">
            <div className="flex justify-center">
                <button
                    type="button"
                    aria-expanded={expanded}
                    onClick={() => setExpanded((current) => !current)}
                    className="inline-flex max-w-full items-center gap-2 rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-500 transition hover:bg-gray-200 hover:text-gray-700"
                >
                    <span className="truncate">{summary}</span>
                    <span className="shrink-0 font-medium text-blue-600">{expanded ? '收起' : '展开'}</span>
                </button>
            </div>

            {expanded && (
                <div className="mx-auto mt-2 max-h-[45vh] w-[min(920px,calc(100%_-_2rem))] overflow-y-auto break-words rounded-lg border border-blue-200 bg-blue-50 px-5 py-4 text-sm leading-7 text-gray-700 [&_h3]:mt-4 [&_h3]:mb-2 [&_h3]:text-sm [&_h3]:font-semibold [&_h3]:text-blue-800 [&_li]:pl-1 [&_ol]:my-2 [&_ol]:list-decimal [&_ol]:space-y-2 [&_ol]:pl-5 [&_strong]:font-semibold [&_strong]:text-gray-900 [&_ul]:my-1 [&_ul]:list-disc [&_ul]:pl-5">
                    <ReactMarkdown>{content}</ReactMarkdown>
                </div>
            )}
        </div>
    )
}
