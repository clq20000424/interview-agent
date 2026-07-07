/**
 * @author: 陈龙强
 */
import {useAuthStore} from '../store/authStore'

const API_BASE = '/api'

interface AuthResponse {
    token?: string
    username?: string
    error?: string
}

/**
 * 封装 fetch 请求，统一处理 401 响应
 */
async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
    const token = localStorage.getItem('token')

    // 如果有 token，添加到请求头
    if (token) {
        options.headers = {
            ...options.headers,
            'Authorization': `Bearer ${token}`,
        }
    }

    const response = await fetch(url, options)

    // 处理 401 未授权响应
    if (response.status === 401) {
        console.warn('[Auth] Token 已过期或无效，自动退出登录')
        const authStore = useAuthStore.getState()
        authStore.logout()
        // 跳转到登录页面
        window.location.href = '/'
        throw new Error('认证已过期，请重新登录')
    }

    return response
}

export async function register(username: string, password: string): Promise<AuthResponse> {
    const res = await fetch(`${API_BASE}/register`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username, password}),
    })
    return res.json()
}

export async function login(username: string, password: string): Promise<AuthResponse> {
    const res = await fetch(`${API_BASE}/login`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username, password}),
    })
    return res.json()
}

/**
 * 带认证的 GET 请求
 */
export async function getWithAuth<T = unknown>(url: string): Promise<T> {
    const res = await fetchWithAuth(url)
    return res.json() as Promise<T>
}

/**
 * 带认证的 POST 请求
 */
export async function postWithAuth<T = unknown>(url: string, data?: Record<string, unknown>): Promise<T> {
    const res = await fetchWithAuth(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: data ? JSON.stringify(data) : undefined,
    })
    return res.json() as Promise<T>
}
