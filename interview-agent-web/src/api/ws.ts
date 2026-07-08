/**
 * @author: 陈龙强
 */

import type { ClientMessage, ServerMessage } from '../types/message'
import { useAuthStore } from '../store/authStore'

export class WSClient {
  private ws: WebSocket | null = null
  private onMessage: (msg: ServerMessage) => void
  private onStatusChange: (connected: boolean) => void
  private reconnectTimer: number | null = null
  private token: string | null = null

  constructor(
    onMessage: (msg: ServerMessage) => void,
    onStatusChange: (connected: boolean) => void,
  ) {
    this.onMessage = onMessage
    this.onStatusChange = onStatusChange
  }

  connect(token?: string) {
    if (token) {
      this.token = token
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const params = this.token ? `?token=${encodeURIComponent(this.token)}` : ''
    const url = `${protocol}//${window.location.host}/ws${params}`
    this.ws = new WebSocket(url)

    this.ws.onopen = () => {
      this.onStatusChange(true)
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer)
        this.reconnectTimer = null
      }
    }

    this.ws.onmessage = (event) => {
      const msg: ServerMessage = JSON.parse(event.data)
      
      // 检查是否是认证错误消息
      if (msg.type === 'error' && msg.message && 
          (msg.message.includes('Token') || msg.message.includes('认证') || msg.message.includes('过期'))) {
        console.warn('[WS] Token 验证失败，自动退出登录:', msg.message)
        this.handleAuthError()
        return
      }
      
      this.onMessage(msg)
    }

    this.ws.onclose = (event) => {
      this.onStatusChange(false)
      
      // 检查关闭原因，如果是认证问题（code 1008 POLICY_VIOLATION），不重连
      // 1008 表示策略违规，我们在后端用于token过期/无效的情况
      if (event.code === 1008) {
        console.warn('[WS] 连接因认证问题关闭 (code=1008)，不自动重连')
        this.handleAuthError()
        return
      }
      
      // 有 token 才自动重连（已登录状态）
      if (this.token) {
        this.reconnectTimer = window.setTimeout(() => this.connect(), 3000)
      }
    }

    this.ws.onerror = () => {
      this.ws?.close()
    }
  }

  /**
   * 处理认证错误：清除本地token并跳转到登录页
   */
  private handleAuthError() {
    const authStore = useAuthStore.getState()
    authStore.logout()
    // 断开当前连接
    this.disconnect()
    // 跳转到登录页面
    window.location.href = '/'
  }

  send(msg: ClientMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg))
    }
  }

  disconnect() {
    this.token = null
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
    }
    this.ws?.close()
  }
}
