/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { create } from 'zustand'
import type { ChatMessage, ServerMessage } from '../types/message'

interface ChatState {
  messages: ChatMessage[]
  connected: boolean
  isInterviewing: boolean
  currentStage: string

  addMessage: (msg: ChatMessage) => void
  setConnected: (v: boolean) => void
  setInterviewing: (v: boolean) => void
  handleServerMessage: (msg: ServerMessage) => void
  clearMessages: () => void
}

let msgId = 0
const nextId = () => String(++msgId)

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  connected: false,
  isInterviewing: false,
  currentStage: '',

  addMessage: (msg) => set((s) => ({ messages: [...s.messages, msg] })),

  setConnected: (connected) => set({ connected }),

  setInterviewing: (v) => set({ isInterviewing: v }),

  clearMessages: () => set({ messages: [] }),

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
        set({ currentStage: msg.stage })
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
        set({ isInterviewing: false, currentStage: '' })
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

      case 'error':
        get().addMessage({
          id: nextId(), role: 'system', content: msg.message,
          messageType: 'text', timestamp: now,
        })
        if (get().isInterviewing) {
          set({ isInterviewing: false, currentStage: '' })
        }
        break
    }
  },
}))
