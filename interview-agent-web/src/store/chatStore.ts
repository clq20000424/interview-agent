/**
 * 聊天状态管理 Store
 * 
 * 使用 Zustand 管理全局聊天状态，包括：
 * - messages: 当前会话的消息列表
 * - connected: WebSocket 连接状态
 * - isInterviewing: 是否正在进行面试
 * - currentStage: 当前面试阶段（如 jd_analysis, interview 等）
 * - currentSessionId: 当前会话的唯一标识，用于关联 Redis 缓存和数据库记录
 * 
 * @author: 陈龙强
 */

import {create} from 'zustand'
import type {ChatMessage, ServerMessage} from '../types/message'

/**
 * 聊天状态接口定义
 */
interface ChatState {
    /** 当前会话的消息列表，用于 UI 渲染 */
    messages: ChatMessage[]
    /** WebSocket 连接状态，true 表示已连接 */
    connected: boolean
    /** 是否正在进行面试，控制 UI 显示“终止面试”按钮等 */
    isInterviewing: boolean
    /** 当前面试阶段，用于 StageIndicator 组件显示进度 */
    currentStage: string
    /** 
     * 当前会话的唯一标识
     * - 普通聊天首次持久化或面试开始时由后端通过 session_started 消息设置
     * - 加载历史会话时由 App.tsx 设置
     * - 页面刷新恢复时由 App.tsx 从 getActiveSession 接口获取
     * - 用于将前端消息与后端 Redis 缓存/数据库记录关联
     */
    currentSessionId: string | null

    /** 添加单条消息到消息列表 */
    addMessage: (msg: ChatMessage) => void
    /** 替换整个消息列表（用于加载历史会话或恢复缓存） */
    replaceMessages: (messages: ChatMessage[]) => void
    /** 设置 WebSocket 连接状态 */
    setConnected: (v: boolean) => void
    /** 设置面试进行中状态 */
    setInterviewing: (v: boolean) => void
    /** 设置当前会话 ID */
    setCurrentSessionId: (id: string | null) => void
    /** 处理服务端推送的 WebSocket 消息 */
    handleServerMessage: (msg: ServerMessage) => void
    /** 清空消息列表并重置 currentSessionId */
    clearMessages: () => void
}

/** 消息 ID 自增计数器，用于生成唯一消息 ID */
let msgId = 0
/** 生成下一个消息 ID */
const nextId = () => String(++msgId)

/**
 * 聊天状态 Store 实例
 * 使用 Zustand create 函数创建，支持在组件中通过 useChatStore(selector) 订阅特定状态
 */
export const useChatStore = create<ChatState>((set, get) => ({
    messages: [],
    connected: false,
    isInterviewing: false,
    currentStage: '',
    currentSessionId: null,

    addMessage: (msg) => set((s) => ({messages: [...s.messages, msg]})),

    replaceMessages: (messages) => set({messages, isInterviewing: false, currentStage: ''}),

    setConnected: (connected) => set({connected}),

    setInterviewing: (v) => set({isInterviewing: v}),

    setCurrentSessionId: (id) => set({currentSessionId: id}),

    clearMessages: () => set({messages: [], currentSessionId: null}),

    /**
     * 将服务端消息分发到对应 UI 状态；收到 session_started 时同步当前 Session ID，
     * 保证普通聊天升级为面试以及后续回答都关联同一条会话记录。
     */
    handleServerMessage: (msg) => {
        const now = Date.now()
        switch (msg.type) {
            case 'chat_reply':
                get().addMessage({
                    id: nextId(), role: 'assistant', content: msg.content,
                    messageType: 'text', timestamp: now,
                })
                break

            case 'stage_change':
                set({currentStage: msg.stage})
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.message,
                    messageType: 'stage', stage: msg.stage, timestamp: now,
                })
                break

            case 'question':
                get().addMessage({
                    id: nextId(), role: 'assistant', content: msg.content,
                    messageType: 'question', questionNum: msg.question_num, timestamp: now,
                })
                break

            case 'review_item':
                // 巩固内容不是一道新的面试题，单独记录类型并由内容中的序号展示进度。
                get().addMessage({
                    id: nextId(), role: 'assistant', content: msg.content,
                    messageType: 'review_item', timestamp: now,
                })
                break

            case 'question_directions':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.content,
                    messageType: 'question_directions', summary: msg.message, timestamp: now,
                })
                break

            case 'memory_weak_points':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.content,
                    messageType: 'memory_weak_points', summary: msg.message, timestamp: now,
                })
                break

            case 'question_plan_details':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.content,
                    messageType: 'question_plan_details', summary: msg.message, timestamp: now,
                })
                break

            case 'score':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.feedback,
                    messageType: 'score', score: msg.score, feedback: msg.feedback,
                    keyPointsHit: msg.key_points_hit, keyPointsMissed: msg.key_points_missed,
                    timestamp: now,
                })
                break

            case 'report':
                get().addMessage({
                    id: nextId(), role: 'assistant', content: msg.content,
                    messageType: 'report', timestamp: now,
                })
                break

            case 'review_plan':
                get().addMessage({
                    id: nextId(), role: 'assistant', content: msg.content,
                    messageType: 'review_plan', timestamp: now,
                })
                break

            case 'interview_complete':
                set({isInterviewing: false, currentStage: ''})
                break

            case 'session_started':
                // 后端通知当前会话 ID，普通聊天升级为面试时继续复用。
                set({currentSessionId: msg.content})
                console.log('[ChatStore] 当前会话:', msg.content)
                break

            case 'sessions_changed':
                window.dispatchEvent(new Event('sessions_changed'))
                break

            case 'upload_result':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.content,
                    messageType: 'upload_result', timestamp: now,
                    feedback: msg.message, // 校验失败详情
                })
                break

            case 'rag_evaluation':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.rag_evaluation.summary,
                    messageType: 'rag_evaluation', timestamp: now,
                    ragEvaluation: msg.rag_evaluation,
                })
                break

            case 'resume_match_result':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.content,
                    messageType: 'resume_match_result', timestamp: now,
                })
                break

            case 'error':
                get().addMessage({
                    id: nextId(), role: 'system', content: msg.message,
                    messageType: 'text', timestamp: now,
                })
                break
        }
    },
}))
