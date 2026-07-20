/**
 * 历史会话侧边栏组件
 * @author 陈龙强
 */

import {useCallback, useEffect, useState} from 'react'
import {deleteSession, getSessions, renameSession, type SessionSummary, setSessionPinned,} from '../api/session'
import {ActionDialog} from './ActionDialog'

interface HistorySidebarProps {
    onLoadSession?: (sessionId: string) => void
    /** 删除会话成功后通知外层同步当前聊天状态。 */
    onSessionDeleted?: (sessionId: string) => void
}

export function HistorySidebar({onLoadSession, onSessionDeleted}: HistorySidebarProps) {
    const [sessions, setSessions] = useState<SessionSummary[]>([])
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [openMenuId, setOpenMenuId] = useState<string | null>(null)
    const [processingId, setProcessingId] = useState<string | null>(null)
    const [notice, setNotice] = useState<{title: string; message: string} | null>(null)
    const [renameTarget, setRenameTarget] = useState<SessionSummary | null>(null)
    const [renameValue, setRenameValue] = useState('')
    const [deleteTarget, setDeleteTarget] = useState<SessionSummary | null>(null)

    // 加载会话列表
    const loadSessions = useCallback(async (showLoading = true) => {
        if (showLoading) {
            setLoading(true)
            setError(null)
        }

        try {
            const data = await getSessions()
            setSessions(data)
            setError(null)
        } catch (err) {
            console.error('[HistorySidebar] 加载会话失败:', err)
            setError(err instanceof Error ? err.message : '加载失败')
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        let ignore = false

        getSessions()
            .then((data) => {
                if (ignore) return
                setSessions(data)
                setError(null)
            })
            .catch((err) => {
                if (ignore) return
                console.error('[HistorySidebar] 加载会话失败:', err)
                setError(err instanceof Error ? err.message : '加载失败')
            })
            .finally(() => {
                if (!ignore) {
                    setLoading(false)
                }
            })

        return () => {
            ignore = true
        }
    }, [])

    useEffect(() => {
        const refresh = () => {
            void loadSessions()
        }
        window.addEventListener('sessions_changed', refresh)
        return () => window.removeEventListener('sessions_changed', refresh)
    }, [loadSessions])

    useEffect(() => {
        const closeMenu = () => setOpenMenuId(null)
        window.addEventListener('click', closeMenu)
        return () => window.removeEventListener('click', closeMenu)
    }, [])

    /** 打开会话重命名对话框并填入当前名称。 */
    const handleRename = (e: React.MouseEvent, session: SessionSummary) => {
        e.stopPropagation()
        setOpenMenuId(null)

        setRenameTarget(session)
        setRenameValue(getSessionTitle(session))
    }

    /** 提交新的会话名称，并将接口错误显示为应用内通知。 */
    const confirmRename = async () => {
        if (!renameTarget || !renameValue.trim()) return

        setProcessingId(renameTarget.id)
        try {
            await renameSession(renameTarget.id, renameValue.trim())
            await loadSessions()
            setRenameTarget(null)
        } catch (err) {
            console.error('[HistorySidebar] 修改会话名称失败:', err)
            setRenameTarget(null)
            setNotice({title: '修改失败', message: err instanceof Error ? err.message : '修改失败'})
        } finally {
            setProcessingId(null)
        }
    }

    // 切换会话置顶状态
    const handleTogglePinned = async (e: React.MouseEvent, session: SessionSummary) => {
        e.stopPropagation()
        setOpenMenuId(null)

        setProcessingId(session.id)
        try {
            await setSessionPinned(session.id, !session.pinned)
            await loadSessions()
        } catch (err) {
            console.error('[HistorySidebar] 修改置顶状态失败:', err)
            setNotice({title: '操作失败', message: err instanceof Error ? err.message : '操作失败'})
        } finally {
            setProcessingId(null)
        }
    }

    /** 打开删除确认对话框，避免误删历史会话。 */
    const handleDelete = (e: React.MouseEvent, session: SessionSummary) => {
        e.stopPropagation()
        setOpenMenuId(null)
        setDeleteTarget(session)
    }

    /** 确认删除会话，并将进行中面试等业务错误显示为应用内通知。 */
    const confirmDelete = async () => {
        if (!deleteTarget) return

        setProcessingId(deleteTarget.id)
        try {
            await deleteSession(deleteTarget.id)
            onSessionDeleted?.(deleteTarget.id)
            await loadSessions()
            setDeleteTarget(null)
        } catch (err) {
            console.error('[HistorySidebar] 删除会话失败:', err)
            setDeleteTarget(null)
            setNotice({title: '暂时无法删除', message: err instanceof Error ? err.message : '删除失败'})
        } finally {
            setProcessingId(null)
        }
    }

    // 打开或关闭会话操作菜单
    const handleMenuClick = (e: React.MouseEvent, sessionId: string) => {
        e.stopPropagation()
        setOpenMenuId((current) => current === sessionId ? null : sessionId)
    }

    // 选择会话
    const handleSelect = (sessionId: string) => {
        if (onLoadSession) {
            onLoadSession(sessionId)
        }
    }

    // 格式化日期
    const formatDate = (dateStr: string) => {
        const date = new Date(dateStr)
        if (Number.isNaN(date.getTime())) {
            return '-'
        }
        const now = new Date()
        const diff = now.getTime() - date.getTime()
        const days = Math.floor(diff / (1000 * 60 * 60 * 24))

        if (days === 0) {
            return `今天 ${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`
        } else if (days === 1) {
            return '昨天'
        } else if (days < 7) {
            return `${days}天前`
        } else {
            return `${date.getMonth() + 1}/${date.getDate()}`
        }
    }

    // 获取状态标签
    const getStatusLabel = (status: string) => {
        switch (status) {
            case 'completed':
                return {text: '已完成', color: 'bg-green-600'}
            case 'terminated':
                return {text: '已终止', color: 'bg-yellow-600'}
            case 'interviewing':
                return {text: '进行中', color: 'bg-blue-600'}
            case 'chat':
                return {text: '对话', color: 'bg-gray-600'}
            default:
                return {text: status, color: 'bg-gray-600'}
        }
    }

    const getSessionTitle = (session: SessionSummary) => {
        return session.title || session.jd_analysis?.position || '未知会话'
    }

    return (
        <div className="flex flex-col h-full">
            <div className="p-3 border-b border-gray-700 flex items-center justify-between">
                <span className="text-xs font-semibold text-gray-300">历史会话</span>
                <button
                    onClick={() => void loadSessions()}
                    disabled={loading}
                    className="text-xs text-gray-400 hover:text-white transition"
                >
                    {loading ? '加载中...' : '刷新'}
                </button>
            </div>

            <div className="flex-1 overflow-y-auto">
                {error && (
                    <div className="p-3 text-xs text-red-400 text-center">{error}</div>
                )}

                {!loading && sessions.length === 0 && !error && (
                    <div className="p-4 text-xs text-gray-500 text-center">
                        暂无历史会话
                    </div>
                )}

                {sessions.map((session) => {
                    const statusLabel = getStatusLabel(session.status)
                    const title = getSessionTitle(session)
                    const score = session.report?.overall_score

                    return (
                        <div
                            key={session.id}
                            onClick={() => handleSelect(session.id)}
                            className="relative p-3 border-b border-gray-800 hover:bg-gray-800 cursor-pointer transition group"
                        >
                            <div className="flex items-start justify-between mb-1">
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                        {session.pinned && (
                                            <span
                                                className="text-[10px] px-1.5 py-0.5 rounded bg-amber-600 text-white flex-shrink-0">
                        置顶
                      </span>
                                        )}
                                        <div className="text-sm text-gray-200 truncate font-medium">
                                            {title}
                                        </div>
                                    </div>
                                    <div className="text-xs text-gray-500 mt-0.5">
                                        {formatDate(session.updated_at || session.created_at)}
                                    </div>
                                </div>
                                <div className="flex items-center gap-1.5 ml-2 shrink-0">
                  <span className={`text-[10px] px-1.5 py-0.5 rounded ${statusLabel.color} text-white`}>
                    {statusLabel.text}
                  </span>
                                    <button
                                        type="button"
                                        onClick={(e) => handleMenuClick(e, session.id)}
                                        disabled={processingId === session.id}
                                        className="w-7 h-7 rounded hover:bg-gray-700 text-gray-400 hover:text-white transition"
                                        title="更多操作"
                                    >
                                        ...
                                    </button>
                                </div>
                            </div>

                            {score !== undefined && (
                                <div className="text-xs text-gray-400 mt-1">
                                    综合得分：<span className="text-blue-400 font-medium">{score}</span>
                                </div>
                            )}

                            {openMenuId === session.id && (
                                <div
                                    onClick={(e) => e.stopPropagation()}
                                    className="absolute right-3 top-10 z-20 w-28 overflow-hidden rounded border border-gray-700 bg-gray-900 shadow-lg"
                                >
                                    <button
                                        type="button"
                                        onClick={(e) => handleRename(e, session)}
                                        disabled={processingId === session.id}
                                        className="block w-full px-3 py-2 text-left text-xs text-gray-200 hover:bg-gray-800 disabled:text-gray-500"
                                    >
                                        编辑名称
                                    </button>
                                    <button
                                        type="button"
                                        onClick={(e) => handleTogglePinned(e, session)}
                                        disabled={processingId === session.id}
                                        className="block w-full px-3 py-2 text-left text-xs text-gray-200 hover:bg-gray-800 disabled:text-gray-500"
                                    >
                                        {session.pinned ? '取消置顶' : '置顶'}
                                    </button>
                                    <button
                                        type="button"
                                        onClick={(e) => handleDelete(e, session)}
                                        disabled={processingId === session.id}
                                        className="block w-full px-3 py-2 text-left text-xs text-red-300 hover:bg-red-950 disabled:text-gray-500"
                                    >
                                        删除
                                    </button>
                                </div>
                            )}
                        </div>
                    )
                })}
            </div>
            <ActionDialog
                open={renameTarget !== null}
                title="编辑会话名称"
                message="名称会同步显示在历史会话列表中。"
                inputLabel="会话名称"
                inputValue={renameValue}
                onInputChange={setRenameValue}
                confirmLabel="保存"
                cancelLabel="取消"
                busy={renameTarget !== null && processingId === renameTarget.id}
                confirmDisabled={!renameValue.trim()}
                onConfirm={() => void confirmRename()}
                onClose={() => setRenameTarget(null)}
            />
            <ActionDialog
                open={deleteTarget !== null}
                title="删除会话"
                message={`确定删除“${deleteTarget ? getSessionTitle(deleteTarget) : ''}”吗？删除后无法恢复。`}
                confirmLabel="删除"
                cancelLabel="取消"
                destructive
                busy={deleteTarget !== null && processingId === deleteTarget.id}
                onConfirm={() => void confirmDelete()}
                onClose={() => setDeleteTarget(null)}
            />
            <ActionDialog
                open={notice !== null}
                title={notice?.title || ''}
                message={notice?.message || ''}
                confirmLabel="知道了"
                onConfirm={() => setNotice(null)}
                onClose={() => setNotice(null)}
            />
        </div>
    )
}
