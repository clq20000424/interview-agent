/**
 * @author: 陈龙强
 */

// 客户端发送的消息
export type ClientMessage =
    | { type: 'chat'; content: string }
    | { type: 'new_chat' }
    | { type: 'load_session'; sessionId: string }
    | { type: 'start_interview'; jd: string; resume: string; sessionId?: string }
    | { type: 'answer'; content: string; sessionId?: string }
    | { type: 'quit_interview'; sessionId?: string }
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
    | { type: 'review_item'; content: string }
    | { type: 'score'; score: number; feedback: string; key_points_hit: string[]; key_points_missed: string[] }
    | { type: 'report'; content: string }
    | { type: 'review_plan'; content: string }
    | { type: 'upload_result'; content: string; message: string }
    | { type: 'rag_evaluation'; rag_evaluation: RAGEvaluation }
    | { type: 'error'; message: string }
    | { type: 'sessions_changed' }
    | { type: 'interview_complete' }
    | { type: 'session_started'; content: string }
    | { type: 'resume_match_result'; content: string }

// UI 展示用的消息
export interface ChatMessage {
    id: string
    role: 'user' | 'assistant' | 'system'
    content: string
    messageType: 'text' | 'score' | 'report' | 'review_plan' | 'review_item' | 'stage' | 'question' | 'resume_match_result' | 'file' | 'upload_result' | 'rag_evaluation'
    timestamp: number
    score?: number
    feedback?: string
    keyPointsHit?: string[]
    keyPointsMissed?: string[]
    questionNum?: number
    stage?: string
    ragEvaluation?: RAGEvaluation
}
