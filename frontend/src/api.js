// 与后端通信的唯一入口：统一超时、错误提示与弱网处理（班级公约第十三条：无稳定网络用户）。

const TIMEOUT_MS = 15000

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

function fallbackMessage(status) {
  if (status === 401) return '请先登录'
  if (status === 403) return '无权进行此操作'
  if (status === 404) return '内容不存在或已被删除'
  if (status === 429) return '操作过于频繁，请稍后再试'
  if (status === 502 || status === 503) return '后端服务未启动或正在重启，请稍后刷新'
  if (status >= 500) return '服务器出错了，请稍后再试'
  return '请求失败'
}

export async function api(path, method = 'GET', data) {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    throw new ApiError('当前处于离线状态，请连接网络后重试', 0)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  let res
  try {
    res = await fetch('/api' + path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: data === undefined ? undefined : JSON.stringify(data),
      signal: controller.signal
    })
  } catch (e) {
    throw new ApiError(e.name === 'AbortError' ? '网络较慢，请求超时，请稍后重试' : '网络连接失败，请检查网络后重试', 0)
  } finally {
    clearTimeout(timer)
  }
  let value = {}
  try {
    value = await res.json()
  } catch {
    // 非 JSON 响应（如网关错误页）
  }
  if (!res.ok) throw new ApiError(value.error || fallbackMessage(res.status), res.status)
  return value
}
