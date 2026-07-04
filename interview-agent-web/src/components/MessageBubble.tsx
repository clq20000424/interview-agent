/**
 * @author: 陈龙强
 */

import ReactMarkdown from 'react-markdown'
import type { ChatMessage } from '../types/message'
import { ScoreCard } from './ScoreCard'
import { ReportCard } from './ReportCard'
import { ReviewPlanCard } from './ReviewPlanCard'

export function MessageBubble({ msg }: { msg: ChatMessage }) {
  if (msg.messageType === 'stage') {
    return (
      <div className="flex justify-center my-2">
        <span className="text-xs text-gray-400 bg-gray-100 px-3 py-1 rounded-full">
          {msg.content}
        </span>
      </div>
    )
  }

  if (msg.messageType === 'score') {
    return <ScoreCard msg={msg} />
  }

  if (msg.messageType === 'report') {
    return <ReportCard content={msg.content} />
  }

  if (msg.messageType === 'review_plan') {
    return <ReviewPlanCard content={msg.content} />
  }

  if (msg.messageType === 'upload_result') {
    return (
      <div className="my-3 mx-4 p-4 bg-purple-50 border border-purple-200 rounded-xl">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-purple-600 font-medium">题库导入结果</span>
        </div>
        <p className="text-sm text-gray-800">{msg.content}</p>
        {msg.feedback && (
          <details className="mt-2">
            <summary className="text-xs text-gray-500 cursor-pointer hover:text-gray-700">校验失败详情</summary>
            <pre className="mt-1 text-xs text-red-600 whitespace-pre-wrap">{msg.feedback}</pre>
          </details>
        )}
      </div>
    )
  }

  if (msg.messageType === 'rag_evaluation' && msg.ragEvaluation) {
    const eval_ = msg.ragEvaluation
    const pct = (v: number) => `${Math.round(v * 100)}%`
    const barColor = (v: number) => v >= 0.7 ? 'bg-green-500' : v >= 0.4 ? 'bg-yellow-500' : 'bg-red-500'
    return (
      <div className="my-3 mx-4 p-4 bg-blue-50 border border-blue-200 rounded-xl">
        <div className="flex items-center gap-2 mb-3">
          <span className="text-blue-600 font-medium">题库诊断报告</span>
        </div>
        <div className="grid grid-cols-4 gap-3 mb-3">
          {[
            { label: '精确率', value: eval_.precision ?? eval_.relevance },
            { label: '召回率', value: eval_.recall ?? eval_.completeness },
            { label: '相关性', value: eval_.relevance },
            { label: '综合评分', value: eval_.overall },
          ].map(({ label, value }) => (
            <div key={label} className="text-center">
              <div className="text-xs text-gray-500 mb-1">{label}</div>
              <div className="text-lg font-bold text-gray-800">{pct(value)}</div>
              <div className="w-full bg-gray-200 rounded-full h-1.5 mt-1">
                <div className={`h-1.5 rounded-full ${barColor(value)}`} style={{ width: pct(value) }} />
              </div>
            </div>
          ))}
        </div>
        {eval_.skill_coverage && eval_.skill_coverage.length > 0 && (
          <div className="mb-3">
            <div className="text-xs text-gray-500 mb-1">各技能方向</div>
            <div className="flex flex-wrap gap-1.5">
              {eval_.skill_coverage.map((sc) => (
                <span
                  key={sc.skill}
                  className={`text-xs px-2 py-0.5 rounded-full ${
                    sc.quality === '充足' ? 'bg-green-100 text-green-700' :
                    sc.quality === '偏少' ? 'bg-yellow-100 text-yellow-700' :
                    'bg-red-100 text-red-700'
                  }`}
                >
                  {sc.skill}: {sc.quality}
                </span>
              ))}
            </div>
          </div>
        )}
        {eval_.summary && (
          <p className="text-sm text-gray-700">{eval_.summary}</p>
        )}
      </div>
    )
  }

  const isUser = msg.role === 'user'

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-4`}>
      <div
        className={`max-w-[75%] rounded-2xl px-4 py-3 ${
          isUser
            ? 'bg-blue-600 text-white'
            : 'bg-gray-100 text-gray-900'
        }`}
      >
        {msg.messageType === 'question' && (
          <div className="text-xs font-medium opacity-70 mb-1">
            第 {msg.questionNum} 题
          </div>
        )}
        <div className="prose prose-sm max-w-none dark:prose-invert">
          <ReactMarkdown>{msg.content}</ReactMarkdown>
        </div>
      </div>
    </div>
  )
}