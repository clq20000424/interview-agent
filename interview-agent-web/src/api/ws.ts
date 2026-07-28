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
  private heartbeatTimer: number | null = null
  private pongTimeoutTimer: number | null = null
  private awaitingPong = false
  private token: string | null = null

  /** 心跳间隔（毫秒） */
  private static readonly HEARTBEAT_INTERVAL = 30_000
  /** ping 发出后等待 pong 的最长时间（毫秒） */
  private static readonly PONG_TIMEOUT = 10_000

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
      this.startHeartbeat()
    }

    this.ws.onmessage = (event) => {
      const msg: ServerMessage = JSON.parse(event.data)

      // pong 仅用于确认连接健康，不进入业务消息和界面渲染流程。
      if (msg.type === 'pong') {
        this.handlePong()
        return
      }
      
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
      this.stopHeartbeat()
      
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

  /**
   * 启动心跳：定时发送 ping，既防止代理层空闲断开，也检测半开连接。
   */
  private startHeartbeat() {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(
      () => this.sendHeartbeat(),
      WSClient.HEARTBEAT_INTERVAL,
    )
  }

  /**
   * 发送一次 ping 并启动 pong 超时检测；超时后主动关闭连接，由现有逻辑重新连接。
   */
  private sendHeartbeat() {
    const socket = this.ws
    if (!socket || socket.readyState !== WebSocket.OPEN || this.awaitingPong) {
      return
    }

    this.awaitingPong = true
    socket.send(JSON.stringify({ type: 'ping' }))
    this.pongTimeoutTimer = window.setTimeout(() => {
      if (!this.awaitingPong || this.ws !== socket) {
        return
      }
      console.warn('[WS] pong 响应超时，关闭连接并准备重连')
      socket.close(4000, 'pong_timeout')
    }, WSClient.PONG_TIMEOUT)
  }

  /**
   * 收到 pong 后确认本轮心跳成功，并取消对应的超时任务。
   */
  private handlePong() {
    this.awaitingPong = false
    if (this.pongTimeoutTimer) {
      clearTimeout(this.pongTimeoutTimer)
      this.pongTimeoutTimer = null
    }
  }

  /**
   * 停止心跳并清理等待中的 pong 超时状态。
   */
  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    if (this.pongTimeoutTimer) {
      clearTimeout(this.pongTimeoutTimer)
      this.pongTimeoutTimer = null
    }
    this.awaitingPong = false
  }

  /**
   * 主动断开连接，同时取消心跳和待执行的自动重连。
   */
  disconnect() {
    this.token = null
    this.stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
  }
}
