const BASE = import.meta.env.VITE_API_BASE_URL || ''
export async function api(path, { token, method = 'GET', body, raw = false, signal } = {}) {
  const response = await fetch(`${BASE}/api${path}`, {
    method, signal,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    if (response.status === 401 && token) window.dispatchEvent(new Event('loadsense:expired'))
    const details = Object.entries(problem.fields || {}).map(([key, value]) => `${key}: ${value}`).join('; ')
    throw new Error(details || problem.message || `Dịch vụ trả về lỗi ${response.status}`)
  }
  if (response.status === 204) return null
  return raw ? response.text() : response.json()
}
export function websocketUrl() {
  const base = new URL(BASE || window.location.origin)
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  base.pathname = '/ws'
  return base.toString()
}
export const terminal = (status) => ['COMPLETED', 'STOPPED', 'FAILED'].includes(status)
export const statusLabels = { PENDING: 'Đang chờ', RUNNING: 'Đang chạy', COMPLETED: 'Hoàn thành', STOPPED: 'Đã dừng', FAILED: 'Thất bại' }
export const number = (value, digits = 0) => new Intl.NumberFormat('vi-VN', { maximumFractionDigits: digits }).format(value || 0)
export const date = (value) => value ? new Date(value).toLocaleString('vi-VN') : '—'
