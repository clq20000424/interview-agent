/**
 * 会话管理 API
 * @author 陈龙强
 */
import {deleteWithAuth, getWithAuth, patchWithAuth} from './auth'
import type {ChatMessage} from '../types/message'

export interface SessionSummary {
    id: string
    title?: string
    session_type?: 'chat' | 'interview'
    user_id: string
    status: string
    pinned?: boolean
    pinned_at?: string
    created_at: string
    updated_at: string
    jd_analysis?: {
        position?: string
        experience_level?: string
    }
    report?: {
        overall_score?: number
    }
}

export interface SessionQA {
    question?: {
        content?: string
        reference?: string
    }
    user_answer?: string
    score?: number
    feedback?: string
}

export interface ConversationMessage {
    role: 'user' | 'assistant' | 'system'
    content: string
    message_type?: ChatMessage['messageType']
    metadata?: Record<string, unknown>
    created_at?: string
}

export interface EvaluationReport {
    overall_score?: number
    overall_level?: string
    summary?: string
    strengths?: string[]
    weaknesses?: string[]
    detailed_review?: Array<{
        question_content?: string
        user_answer?: string
        score?: number
        comment?: string
    }>
}

export interface ReviewPlan {
    weak_areas?: Array<{
        topic?: string
        score?: number
        priority?: string
    }>
    study_plan?: Array<{
        topic?: string
        objective?: string
        actions?: string[]
        time_estimate?: string
    }>
    resources?: Array<{
        title?: string
        type?: string
        url?: string
        desc?: string
    }>
}

export interface SessionDetail extends SessionSummary {
    jd_analysis?: {
        position?: string
        experience_level?: string
    }
    resume?: Record<string, unknown>
    match_result?: Record<string, unknown>
    question_plan?: Record<string, unknown>
    interview_state?: {
        qa_history?: SessionQA[]
    }
    report?: EvaluationReport
    review_plan?: ReviewPlan
    chat_messages?: ConversationMessage[]
}

/**
 * 获取用户的所有会话列表
 */
export async function getSessions(): Promise<SessionSummary[]> {
    return getWithAuth<SessionSummary[]>('/api/sessions')
}

/**
 * 获取会话详情
 */
export async function getSessionDetail(sessionId: string): Promise<SessionDetail> {
    return getWithAuth<SessionDetail>(`/api/sessions/${sessionId}`)
}

/**
 * 修改会话名称
 */
export async function renameSession(sessionId: string, title: string): Promise<SessionSummary> {
    return patchWithAuth<SessionSummary>(`/api/sessions/${sessionId}/title`, {title})
}

/**
 * 修改会话置顶状态
 */
export async function setSessionPinned(sessionId: string, pinned: boolean): Promise<SessionSummary> {
    return patchWithAuth<SessionSummary>(`/api/sessions/${sessionId}/pin`, {pinned})
}

/**
 * 删除会话
 */
export async function deleteSession(sessionId: string): Promise<void> {
    return deleteWithAuth(`/api/sessions/${sessionId}`)
}

/**
 * 检查是否有进行中的面试会话（从 Redis 缓存中）
 *
 * 用于页面刷新时恢复未完成的面试。后端会检查：
 * 1. 数据库中状态为 interviewing 的会话
 * 2. Redis 中是否有该会话的缓存消息
 *
 * @returns 如果 has_cached=true，则包含 session 和 cached_messages；否则只返回 { has_cached: false }
 */
export async function getActiveSession(): Promise<{
    /** 是否有缓存的消息 */
    has_cached: boolean
    /** 活跃会话的摘要信息（仅当 has_cached=true 时存在） */
    session?: SessionSummary
    /** 缓存的消息列表（仅当 has_cached=true 时存在） */
    cached_messages?: ConversationMessage[]
}> {
    return getWithAuth('/api/sessions/active')
}
