import {useCallback, useEffect, useState} from 'react'
import {Sidebar} from './components/Sidebar'
import {ChatWindow} from './components/ChatWindow'
import {LoginPage} from './components/LoginPage'
import {NoticeDialog} from './components/NoticeDialog'
import {useWebSocket} from './hooks/useWebSocket'
import {useAuthStore} from './store/authStore'
import {useChatStore} from './store/chatStore'
import {type ConversationMessage, type EvaluationReport, getActiveSession, getSessionDetail, type ReviewPlan} from './api/session'
import type {ChatMessage} from './types/message'

type ChatMessageType = ChatMessage['messageType']

const KNOWN_MESSAGE_TYPES: ReadonlySet<ChatMessageType> = new Set([
    'text',
    'score',
    'report',
    'review_plan',
    'review_item',
    'stage',
    'question',
    'resume_match_result',
    'file',
    'upload_result',
    'rag_evaluation',
])

interface NoticeState {
    title: string
    message: string
    actionLabel: string
}

/**
 * 组织登录后的会话恢复、历史会话切换及全局提示交互。
 */
export default function App() {
    const token = useAuthStore((s) => s.token)
    const wsRef = useWebSocket()
    const [notice, setNotice] = useState<NoticeState | null>(null)

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
                    const messages = result.cached_messages.map((msg, idx) =>
                        toStoredChatMessage(msg, `cached-${idx}`, now + idx * 100))

                    useChatStore.getState().replaceMessages(messages)
                    useChatStore.getState().setInterviewing(true)
                    wsRef.current?.send({type: 'load_session', sessionId: result.session.id})

                    setNotice({
                        title: '已恢复进行中的面试',
                        message: '上次面试的实时记录已经恢复，可以从当前题目继续作答。',
                        actionLabel: '继续面试',
                    })
                }
            } catch (err) {
                console.error('[App] 检查活跃会话失败', err)
                // 如果是网络错误（后端服务不可达），清除本地 token 并显示登录页
                if (err instanceof TypeError) {
                    console.warn('[App] 后端服务不可达，清除本地 token')
                    useAuthStore.getState().logout()
                }
            }
        }

        checkActiveSession()
    }, [token, wsRef])

    /**
     * 加载指定历史会话，并同步更新当前 Session ID。这样从历史普通聊天继续发起面试时，
     * start_interview 能携带正确 ID，让后端升级原会话而不是新建重复记录。
     */
    const handleLoadSession = useCallback(async (sessionId: string) => {
        try {
            const session = await getSessionDetail(sessionId)
            useChatStore.getState().setCurrentSessionId(sessionId)
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
                    messages.push(toStoredChatMessage(
                        msg,
                        `session-${sessionId}-chat-${idx}`,
                        now + idx * 500,
                    ))
                })
                useChatStore.getState().replaceMessages(messages)
                useChatStore.getState().setInterviewing(session.status === 'interviewing')
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
            useChatStore.getState().setInterviewing(session.status === 'interviewing')
        } catch (err) {
            console.error('[App] 加载会话失败', err)
            setNotice({
                title: '会话加载失败',
                message: err instanceof Error ? err.message : '加载会话失败，请稍后重试。',
                actionLabel: '知道了',
            })
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
            <NoticeDialog
                open={notice !== null}
                title={notice?.title || ''}
                message={notice?.message || ''}
                actionLabel={notice?.actionLabel || '确定'}
                onClose={() => setNotice(null)}
            />
        </div>
    )
}

/**
 * 把 Redis/MySQL 中的结构化会话消息还原为前端展示模型，确保刷新前后的组件类型、
 * 题号、阶段和评分详情保持一致。
 */
function toStoredChatMessage(msg: ConversationMessage, id: string, fallbackTimestamp: number): ChatMessage {
    const metadata = msg.metadata ?? {}
    const messageType = toChatMessageType(msg.message_type, msg.role, msg.content)
    return {
        id,
        role: msg.role,
        content: msg.content || '',
        messageType,
        timestamp: msg.created_at ? new Date(msg.created_at).getTime() : fallbackTimestamp,
        stage: readString(metadata.stage),
        questionNum: readNumber(metadata.question_num),
        score: readNumber(metadata.score),
        feedback: messageType === 'score' ? readString(metadata.feedback) ?? msg.content : undefined,
        keyPointsHit: readStringArray(metadata.key_points_hit),
        keyPointsMissed: readStringArray(metadata.key_points_missed),
    }
}

/**
 * 解析持久化消息类型。对修复前已按 text 保存的系统消息进行内容兼容，避免旧历史记录
 * 继续显示为普通左侧气泡。
 */
function toChatMessageType(value?: string, role?: ChatMessage['role'], content = ''): ChatMessageType {
    if (role === 'system' && (!value || value === 'text')) {
        if (content.includes('简历匹配分析完成')) {
            return 'resume_match_result'
        }
        if (isLegacyStageMessage(content)) {
            return 'stage'
        }
    }
    if (value && KNOWN_MESSAGE_TYPES.has(value as ChatMessageType)) {
        return value as ChatMessageType
    }
    return 'text'
}

/** 判断旧数据中的系统文本是否属于面试阶段进度消息。 */
function isLegacyStageMessage(content: string) {
    return [
        '正在',
        'JD 分析完成',
        '简历匹配完成',
        '已加载',
        '题库检索完成',
        '出题计划完成',
        '面试正式开始',
        '低分题目巩固完成',
        '用户主动终止面试',
        '面试提前终止',
        '面试未作答',
        '面试流程全部完成',
    ].some((prefix) => content.startsWith(prefix))
}

/** 从未知元数据值中安全读取字符串。 */
function readString(value: unknown) {
    return typeof value === 'string' ? value : undefined
}

/** 从未知元数据值中安全读取有限数字。 */
function readNumber(value: unknown) {
    return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** 从未知元数据值中安全读取字符串数组。 */
function readStringArray(value: unknown) {
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : undefined
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
