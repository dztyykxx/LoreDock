import type { ApiErrorBody } from './types'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: ApiErrorBody['fieldErrors'] = [],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export type UnauthorizedHandler = () => void

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? ''
let unauthorizedHandler: UnauthorizedHandler = () => undefined

export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

/**
 * 为浏览器原生流式客户端解析与 JSON 请求一致的 API 地址。
 */
export function resolveApiUrl(path: string): string {
  return `${apiBaseUrl}${path}`
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(resolveApiUrl(path), {
    ...init,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    const body = await readErrorBody(response)
    if (response.status === 401) {
      // 401 表示服务端已经否定当前会话，继续保留本地角色会让界面短暂暴露不应出现的写控件。
      unauthorizedHandler()
    }
    throw new ApiError(
      response.status,
      body?.code ?? `HTTP_${response.status}`,
      body?.message ?? '请求失败，请稍后重试。',
      body?.fieldErrors ?? [],
    )
  }

  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    return await response.json() as ApiErrorBody
  } catch {
    // 非 JSON 错误体只转换为安全通用提示，避免把代理或服务端内部页面透传给用户。
    return null
  }
}
