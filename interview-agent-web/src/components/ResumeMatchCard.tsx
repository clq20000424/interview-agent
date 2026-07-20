import {PlanningDetailsCard} from './PlanningDetailsCard'

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
  const markdown = normalizeResumeMatchMarkdown(content)
  const score = markdown.match(/综合匹配度：([^*\n]+)/)?.[1]?.trim()

  return <PlanningDetailsCard summary={`简历匹配完成${score ? `（${score}）` : ''}`} content={markdown} />
}
