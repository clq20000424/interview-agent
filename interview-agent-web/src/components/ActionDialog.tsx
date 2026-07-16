import {useEffect, useRef} from 'react'

interface ActionDialogProps {
    open: boolean
    title: string
    message?: string
    confirmLabel: string
    cancelLabel?: string
    destructive?: boolean
    busy?: boolean
    confirmDisabled?: boolean
    inputValue?: string
    inputLabel?: string
    onInputChange?: (value: string) => void
    onConfirm: () => void
    onClose: () => void
}

/**
 * 展示统一的操作对话框，支持通知、确认及文本输入三种会话管理场景。
 */
export function ActionDialog({
    open,
    title,
    message,
    confirmLabel,
    cancelLabel,
    destructive = false,
    busy = false,
    confirmDisabled = false,
    inputValue,
    inputLabel,
    onInputChange,
    onConfirm,
    onClose,
}: ActionDialogProps) {
    const inputRef = useRef<HTMLInputElement>(null)
    const confirmRef = useRef<HTMLButtonElement>(null)
    const closeRef = useRef(onClose)
    const busyRef = useRef(busy)
    const hasInput = inputValue !== undefined

    useEffect(() => {
        closeRef.current = onClose
    }, [onClose])

    useEffect(() => {
        busyRef.current = busy
    }, [busy])

    useEffect(() => {
        if (!open) return

        if (hasInput) {
            inputRef.current?.focus()
            inputRef.current?.select()
        } else {
            confirmRef.current?.focus()
        }
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape' && !busyRef.current) {
                closeRef.current()
            }
        }
        window.addEventListener('keydown', handleKeyDown)
        return () => window.removeEventListener('keydown', handleKeyDown)
    }, [hasInput, open])

    if (!open) return null

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/45 px-4"
            onMouseDown={() => !busy && onClose()}
        >
            <form
                role="dialog"
                aria-modal="true"
                aria-labelledby="action-dialog-title"
                onSubmit={(event) => {
                    event.preventDefault()
                    if (!busy && !confirmDisabled) onConfirm()
                }}
                onMouseDown={(event) => event.stopPropagation()}
                className="w-full max-w-md overflow-hidden rounded-lg border border-gray-200 bg-white shadow-2xl"
            >
                <div className="border-b border-gray-100 px-6 py-5">
                    <h2 id="action-dialog-title" className="text-lg font-semibold text-gray-900">
                        {title}
                    </h2>
                    {message && <p className="mt-2 text-sm leading-6 text-gray-600">{message}</p>}
                    {inputValue !== undefined && onInputChange && (
                        <label className="mt-4 block text-sm font-medium text-gray-700">
                            {inputLabel || '名称'}
                            <input
                                ref={inputRef}
                                value={inputValue}
                                maxLength={200}
                                onChange={(event) => onInputChange(event.target.value)}
                                className="mt-2 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                            />
                        </label>
                    )}
                </div>
                <div className="flex justify-end gap-2 bg-gray-50 px-6 py-4">
                    {cancelLabel && (
                        <button
                            type="button"
                            disabled={busy}
                            onClick={onClose}
                            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {cancelLabel}
                        </button>
                    )}
                    <button
                        ref={confirmRef}
                        type="submit"
                        disabled={busy || confirmDisabled}
                        className={`rounded-lg px-4 py-2 text-sm font-medium text-white transition focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${
                            destructive
                                ? 'bg-red-600 hover:bg-red-700 focus:ring-red-500'
                                : 'bg-blue-600 hover:bg-blue-700 focus:ring-blue-500'
                        }`}
                    >
                        {busy ? '处理中...' : confirmLabel}
                    </button>
                </div>
            </form>
        </div>
    )
}
