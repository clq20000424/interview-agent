import {useCallback, useEffect} from 'react'
import {Sidebar} from './components/Sidebar'
import {ChatWindow} from './components/ChatWindow'
import {LoginPage} from './components/LoginPage'
import {useWebSocket} from './hooks/useWebSocket'
import {useAuthStore} from './store/authStore'
import {useChatStore} from './store/chatStore'
import {type EvaluationReport, getActiveSession, getSessionDetail, type ReviewPlan} from './api/session'
import type {ChatMessage} from './types/message'

type ChatMessageType = ChatMessage['messageType']

const KNOWN_MESSAGE_TYPES: ReadonlySet<ChatMessageType> = new Set([
    'text',
    'score',
    'report',
    'review_plan',
    'stage',
    'question',
    'file',
    'upload_result',
    'rag_evaluation',
])

export default function App() {
    const token = useAuthStore((s) => s.token)
    const wsRef = useWebSocket()

    /**
     * 页面加载后检查是否存在未完成的面试缓存。
     * 如果 Redis 中还有当前用户的会话消息，则恢复消息列表并重新挂接 WebSocket 会话。
     */
    useEffect(() => {
        if (!token) return

        const checkActiveSession = async () => {
            try {
                const result = await getActiveSession()
                if (result.has_cached && result.session && result.cached_messages?.length) {
                    console.log('[App] 发现可恢复的面试会话', result.session.id)

                    useChatStore.getState().setCurrentSessionId(result.session.id)
                    const now = Date.now()
                    const messages: ChatMessage[] = result.cached_messages.map((msg, idx) => ({
                        id: `cached-${idx}`,
                        role: msg.role,
                        content: msg.content || '',
                        messageType: toChatMessageType(msg.message_type),
                        timestamp: msg.created_at ? new Date(msg.created_at).getTime() : now + idx * 100,
                    }))

                    useChatStore.getState().replaceMessages(messages)
                    useChatStore.getState().setInterviewing(true)
                    wsRef.current?.send({type: 'load_session', sessionId: result.session.id})

                    alert('检测到您有未完成的面试，已自动恢复。您可以继续答题或选择其他操作。')
                }
            } catch (err) {
                console.error('[App] 检查活跃会话失败', err)
            }
        }

        checkActiveSession()
    }, [token, wsRef])

    // 加载历史会话。
    const handleLoadSession = useCallback(async (sessionId: string) => {
        try {
            const session = await getSessionDetail(sessionId)
            wsRef.current?.send({type: 'load_session', sessionId})

            const now = Date.now()
            const messages: ChatMessage[] = []

            messages.push({
                id: `session-${sessionId}-info`,
                role: 'system',
                content: `History session: ${getSessionTitle(session)} - ${formatStatus(session.status)}`,
                messageType: 'text',
                timestamp: now,
            })

            if (session.chat_messages?.length) {
                session.chat_messages.forEach((msg, idx) => {
                    messages.push({
                        id: `session-${sessionId}-chat-${idx}`,
                        role: msg.role,
                        content: msg.content,
                        messageType: toChatMessageType(msg.message_type),
                        timestamp: msg.created_at ? new Date(msg.created_at).getTime() : now + idx * 500,
                    })
                })
                useChatStore.getState().replaceMessages(messages)
                return
            }

            session.interview_state?.qa_history?.forEach((qa, idx) => {
                if (qa.question?.content) {
                    messages.push({
                        id: `session-${sessionId}-q-${idx}`,
                        role: 'assistant',
                        content: qa.question.content,
                        messageType: 'question',
                        questionNum: idx + 1,
                        timestamp: now + idx * 1000,
                    })
                }

                if (qa.user_answer) {
                    messages.push({
                        id: `session-${sessionId}-a-${idx}`,
                        role: 'user',
                        content: qa.user_answer,
                        messageType: 'text',
                        timestamp: now + idx * 1000 + 500,
                    })
                }

                if (qa.feedback || qa.score !== undefined) {
                    messages.push({
                        id: `session-${sessionId}-score-${idx}`,
                        role: 'system',
                        content: qa.feedback || '',
                        messageType: 'score',
                        score: qa.score,
                        feedback: qa.feedback,
                        timestamp: now + idx * 1000 + 800,
                    })
                }
            })

            if (session.report) {
                messages.push({
                    id: `session-${sessionId}-report`,
                    role: 'assistant',
                    content: formatReport(session.report),
                    messageType: 'report',
                    timestamp: now + 10000,
                })
            }

            if (session.review_plan) {
                messages.push({
                    id: `session-${sessionId}-plan`,
                    role: 'assistant',
                    content: formatReviewPlan(session.review_plan),
                    messageType: 'review_plan',
                    timestamp: now + 11000,
                })
            }

            useChatStore.getState().replaceMessages(messages)
        } catch (err) {
            console.error('[App] 加载会话失败', err)
            alert(err instanceof Error ? err.message : '加载会话失败')
        }
    }, [wsRef])

    if (!token) {
        return <LoginPage/>
    }

    return (
        <div className="flex h-screen bg-white">
            <Sidebar
                onLoadSession={handleLoadSession}
                onNewChat={() => {
                    useChatStore.getState().clearMessages()
                    wsRef.current?.send({type: 'new_chat'})
                    window.dispatchEvent(new Event('sessions_changed'))
                }}
            />
            <ChatWindow wsRef={wsRef}/>
        </div>
    )
}

function toChatMessageType(value?: string): ChatMessageType {
    if (value && KNOWN_MESSAGE_TYPES.has(value as ChatMessageType)) {
        return value as ChatMessageType
    }
    return 'text'
}

function getSessionTitle(session: { title?: string; jd_analysis?: { position?: string } }) {
    return session.title || session.jd_analysis?.position || 'Unknown session'
}

function formatStatus(status: string) {
    switch (status) {
        case 'chat':
            return 'Chat'
        case 'completed':
            return 'Completed'
        case 'terminated':
            return 'Terminated'
        case 'interviewing':
            return 'Interviewing'
        case 'evaluated':
            return 'Evaluated'
        default:
            return status
    }
}

function formatReport(report: EvaluationReport) {
    const lines = [
        '## Evaluation Report',
        '',
        `- Overall score: ${formatNumber(report.overall_score)}`,
        report.overall_level ? `- Level: ${report.overall_level}` : '',
        report.summary ? `\n${report.summary}` : '',
    ].filter(Boolean)

    if (report.strengths?.length) {
        lines.push('', '### Strengths', ...report.strengths.map((item) => `- ${item}`))
    }

    if (report.weaknesses?.length) {
        lines.push('', '### Improvements', ...report.weaknesses.map((item) => `- ${item}`))
    }

    if (report.detailed_review?.length) {
        lines.push('', '### Question Review')
        report.detailed_review.forEach((item, idx) => {
            lines.push(
                '',
                `${idx + 1}. ${item.question_content || 'Question'}`,
                `   - Score: ${formatNumber(item.score)}`,
                item.comment ? `   - Comment: ${item.comment}` : '',
            )
        })
    }

    return lines.filter(Boolean).join('\n')
}

function formatReviewPlan(plan: ReviewPlan) {
    const lines = ['## Review Plan']

    if (plan.weak_areas?.length) {
        lines.push('', '### Weak Areas')
        plan.weak_areas.forEach((area) => {
            lines.push(`- ${area.topic || 'Untitled'}: ${formatNumber(area.score)}${area.priority ? ` (${area.priority})` : ''}`)
        })
    }

    if (plan.study_plan?.length) {
        lines.push('', '### Study Plan')
        plan.study_plan.forEach((item, idx) => {
            lines.push('', `${idx + 1}. ${item.topic || 'Study item'}`)
            if (item.objective) lines.push(`   - Objective: ${item.objective}`)
            if (item.time_estimate) lines.push(`   - Time: ${item.time_estimate}`)
            item.actions?.forEach((action) => lines.push(`   - ${action}`))
        })
    }

    if (plan.resources?.length) {
        lines.push('', '### Resources')
        plan.resources.forEach((resource) => {
            const title = resource.url ? `[${resource.title || resource.url}](${resource.url})` : resource.title
            lines.push(`- ${title || 'Resource'}${resource.desc ? `: ${resource.desc}` : ''}`)
        })
    }

    return lines.join('\n')
}

function formatNumber(value?: number) {
    return value === undefined ? '-' : Math.round(value).toString()
}
