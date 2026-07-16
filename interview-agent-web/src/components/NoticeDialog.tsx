import {useEffect, useRef} from 'react'

interface NoticeDialogProps {
    open: boolean
    title: string
    message: string
    actionLabel: string
    onClose: () => void
}

/**
 * 展示应用内通知弹窗，支持遮罩关闭、Esc 关闭并在打开后聚焦主操作按钮。
 */
export function NoticeDialog({open, title, message, actionLabel, onClose}: NoticeDialogProps) {
    const actionRef = useRef<HTMLButtonElement>(null)

    useEffect(() => {
        if (!open) return

        actionRef.current?.focus()
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                onClose()
            }
        }
        window.addEventListener('keydown', handleKeyDown)
        return () => window.removeEventListener('keydown', handleKeyDown)
    }, [open, onClose])

    if (!open) return null

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/45 px-4"
            onMouseDown={onClose}
        >
            <section
                role="dialog"
                aria-modal="true"
                aria-labelledby="notice-dialog-title"
                aria-describedby="notice-dialog-message"
                onMouseDown={(event) => event.stopPropagation()}
                className="w-full max-w-md overflow-hidden rounded-lg border border-gray-200 bg-white shadow-2xl"
            >
                <div className="border-b border-gray-100 px-6 py-5">
                    <h2 id="notice-dialog-title" className="text-lg font-semibold text-gray-900">
                        {title}
                    </h2>
                    <p id="notice-dialog-message" className="mt-2 text-sm leading-6 text-gray-600">
                        {message}
                    </p>
                </div>
                <div className="flex justify-end bg-gray-50 px-6 py-4">
                    <button
                        ref={actionRef}
                        type="button"
                        onClick={onClose}
                        className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
                    >
                        {actionLabel}
                    </button>
                </div>
            </section>
        </div>
    )
}
