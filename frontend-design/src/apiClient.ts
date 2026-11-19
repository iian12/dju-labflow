import type { Context, Endpoint, Field } from './apiCatalog.ts'

export type JsonObject = Record<string, unknown>
export type Exchange = {
  id: number
  endpointId: string
  title: string
  method: string
  path: string
  status: number | null
  ok: boolean
  elapsed: number
  time: string
  requestBody: string
  responseBody: string
  auth: string
  csrf: boolean
  note?: string
}

// Consume JSON strings first so digits inside user text never become ID tokens.
export function parseResponse(text: string): unknown {
  return JSON.parse(text.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token => {
    if (/^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token))) return JSON.stringify(token)
    return token
  }))
}

export function object(value: unknown): JsonObject | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : undefined
}

export function identifier(value: unknown): string {
  return typeof value === 'string' || (typeof value === 'number' && Number.isSafeInteger(value)) ? String(value) : ''
}

function fieldJson(field: Field, value: string): string | undefined {
  if (value === '' && field.required) throw new Error(`${field.label}을(를) 입력하세요.`)
  if (value === '' && ['json', 'number', 'integer'].includes(field.type ?? 'text')) return undefined
  if (value === '' && field.name === 'recordedAt') return undefined
  if (field.type === 'json') {
    try { JSON.parse(value) } catch { throw new Error(`${field.label}: 올바른 JSON을 입력하세요.`) }
    return value // Preserve raw numeric literals, including 64-bit steps and memory values.
  }
  if (field.type === 'integer') {
    if (!/^-?(0|[1-9]\d*)$/.test(value)) throw new Error(`${field.label}: 정수를 입력하세요.`)
    return value
  }
  if (field.type === 'number') {
    if (!Number.isFinite(Number(value))) throw new Error(`${field.label}: 유한한 숫자를 입력하세요.`)
    return JSON.stringify(Number(value))
  }
  return JSON.stringify(value)
}

export function prepare(endpoint: Endpoint, values: Record<string, string>, context: Context, raw?: string) {
  let path = endpoint.path.replace(/\{(projectId|runId)\}/g, (_, key: 'projectId' | 'runId') => {
    if (!context[key].trim()) throw new Error(`${key}를 입력하거나 먼저 생성하세요.`)
    return encodeURIComponent(context[key].trim())
  })
  if (endpoint.query) {
    const query = new URLSearchParams()
    for (const field of endpoint.fields ?? []) {
      const value = field.binding ? context[field.binding] : values[field.name] ?? ''
      if (field.required && !value.trim()) throw new Error(`${field.label}을(를) 입력하세요.`)
      if (value !== '') query.set(field.name, value)
    }
    if (query.size) path += `?${query}`
    return { path, body: undefined }
  }
  if (raw !== undefined) {
    try { JSON.parse(raw) } catch { throw new Error('요청 본문이 올바른 JSON이 아닙니다.') }
    return { path, body: raw }
  }
  if (!endpoint.fields) return { path, body: undefined }
  const entries = endpoint.fields.flatMap(field => {
    const value = fieldJson(field, field.binding ? context[field.binding] : values[field.name] ?? '')
    return value === undefined ? [] : [`${JSON.stringify(field.name)}: ${value}`]
  })
  return { path, body: `{\n${entries.map(entry => `  ${entry}`).join(',\n')}\n}` }
}

export function readCsrf(cookie: string): string {
  const item = cookie.split(';').map(value => value.trim()).find(value => value.startsWith('XSRF-TOKEN='))
  if (!item) return ''
  try { return decodeURIComponent(item.slice('XSRF-TOKEN='.length)) } catch { return '' }
}

export function sanitize(body: string, secrets: string[] = [], hideCode = false): string {
  const replaceSecrets = (value: string) => secrets.filter(Boolean).reduce((result, secret) => result.split(secret).join('[마스킹]'), value)
  try {
    const scrub = (value: unknown): unknown => {
      if (typeof value === 'string') return replaceSecrets(value)
      if (Array.isArray(value)) return value.map(scrub)
      const fields = object(value)
      if (!fields) return value
      return Object.fromEntries(Object.entries(fields).map(([key, item]) => [key,
        /password|rawApiKey|authorization|cookie|access.?token|refresh.?token/i.test(key) || (hideCode && key === 'code') ? '[마스킹]' : scrub(item),
      ]))
    }
    return JSON.stringify(scrub(parseResponse(body)), null, 2)
  } catch { return replaceSecrets(body) }
}

export async function requestApi(
  endpoint: Endpoint,
  request: { path: string; body?: string },
  options: { apiKey: string; anonymous: boolean; autoCsrf: boolean; cookie: () => string; fetcher?: typeof fetch; onExchange: (entry: Exchange) => void },
): Promise<{ data: unknown; exchange: Exchange }> {
  const fetcher = options.fetcher ?? fetch
  const isKey = endpoint.auth === 'key'
  const credentials = isKey || options.anonymous ? 'omit' : 'include'
  if (endpoint.csrf && options.autoCsrf && !options.anonymous && !readCsrf(options.cookie())) {
    const csrfEndpoint: Endpoint = { ...endpoint, id: 'csrf', title: 'CSRF 자동 초기화', method: 'GET', path: '/api/v1/auth/csrf', status: 200, auth: 'public', csrf: false }
    const result = await requestApi(csrfEndpoint, { path: csrfEndpoint.path }, options)
    if (!result.exchange.ok) throw new Error('CSRF 초기화에 실패했습니다. 응답 기록을 확인하세요.')
  }
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (request.body !== undefined) headers['Content-Type'] = 'application/json'
  if (isKey && options.apiKey.trim()) headers.Authorization = `Bearer ${options.apiKey.trim()}`
  const csrf = !isKey && endpoint.csrf && options.autoCsrf && !options.anonymous ? readCsrf(options.cookie()) : ''
  if (csrf) headers['X-XSRF-TOKEN'] = csrf
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15_000)
  const start = performance.now()
  let status: number | null = null
  let responseBody = ''
  let note: string | undefined
  let data: unknown
  try {
    const response = await fetcher(request.path, { method: endpoint.method, headers, body: request.body, credentials, signal: controller.signal, redirect: 'error', cache: 'no-store' })
    status = response.status
    responseBody = await response.text()
    if (responseBody) {
      try { data = parseResponse(responseBody) } catch { note = 'JSON이 아닌 응답입니다. 백엔드 주소와 프록시 설정을 확인하세요.' }
    }
  } catch {
    note = '응답을 받지 못했습니다. 백엔드 실행·프록시 연결을 확인하세요. 서버 처리 여부는 알 수 없으며 자동 재시도하지 않았습니다.'
  } finally { clearTimeout(timer) }
  const secrets = [options.apiKey, ...Object.entries(safeRequestObject(request.body)).filter(([key]) => /password|^code$/i.test(key)).map(([, value]) => typeof value === 'string' ? value : '')]
  const exchange: Exchange = {
    id: Date.now() + Math.random(), endpointId: endpoint.id, title: endpoint.title, method: endpoint.method, path: request.path,
    status, ok: status === endpoint.status && !note, elapsed: Math.round(performance.now() - start), time: new Date().toLocaleTimeString('ko-KR'),
    requestBody: request.body === undefined ? '' : sanitize(request.body, secrets, endpoint.id === 'verify'),
    responseBody: sanitize(responseBody, secrets), auth: isKey ? (headers.Authorization ? 'Bearer ••••••••' : 'API key 미설정') : credentials === 'include' ? '브라우저 쿠키' : '쿠키 없음', csrf: Boolean(csrf), note,
  }
  options.onExchange(exchange)
  return { data, exchange }
}

function safeRequestObject(body?: string): JsonObject {
  try { return object(JSON.parse(body ?? '{}')) ?? {} } catch { return {} }
}
