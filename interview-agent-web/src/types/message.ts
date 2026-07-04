/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

// 客户端发送的消息
export type ClientMessage =
  | { type: 'chat'; content: string }
  | { type: 'start_interview'; jd: string; resume: string }
  | { type: 'answer'; content: string }
  | { type: 'quit_interview' }
  | { type: 'upload_file'; filename: string; data: string }
  | { type: 'upload_questions'; filename: string; data: string }

// RAG 题库诊断结果
export interface SkillCoverage {
  skill: string
  covered: boolean
  quality: string
}

export interface RAGEvaluation {
  precision: number
  recall: number
  relevance: number
  completeness: number
  overall: number
  summary: string
  skill_coverage: SkillCoverage[]
  question_evals?: { index: number; relevant: boolean; reason: string }[]
}

// 服务端推送的消息
export type ServerMessage =
  | { type: 'chat_reply'; content: string }
  | { type: 'stage_change'; stage: string; message: string }
  | { type: 'question'; question_num: number; content: string }
  | { type: 'score'; score: number; feedback: string; key_points_hit: string[]; key_points_missed: string[] }
  | { type: 'report'; content: string }
  | { type: 'review_plan'; content: string }
  | { type: 'upload_result'; content: string; message: string }
  | { type: 'rag_evaluation'; rag_evaluation: RAGEvaluation }
  | { type: 'error'; message: string }
  | { type: 'interview_complete' }

// UI 展示用的消息
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  messageType: 'text' | 'score' | 'report' | 'review_plan' | 'stage' | 'question' | 'file' | 'upload_result' | 'rag_evaluation'
  timestamp: number
  score?: number
  feedback?: string
  keyPointsHit?: string[]
  keyPointsMissed?: string[]
  questionNum?: number
  stage?: string
  ragEvaluation?: RAGEvaluation
}
