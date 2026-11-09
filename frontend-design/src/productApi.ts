import { object, parseResponse, readCsrf } from './apiClient.ts'
import type { MetricRecord } from './metrics.ts'

export type Project = { projectId: string; name: string; description: string | null; visibility: 'PUBLIC' | 'PRIVATE'; createdAt: string; updatedAt: string | null; version: string | number }
export type Run = { runId: string; projectId: string; name: string; status: 'RUNNING' | 'COMPLETED' | 'FAILED'; metadata: Record<string, string>; startedAt: string; endedAt: string | null; error: string | null }
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type RunPage = Page<Run> & { counts: Record<Run['status'], number> }
export type Metric = { id: string; key: string; value: number; step: number | string | null; epoch: number | null; elapsedTime: number | null; recordedAt: string }
export type Records = {
  parameters: { key: string; value: string }[]
  metrics: Metric[]
  logs: { id: string; message: string; level: string; source: string; recordedAt: string }[]
  texts: { id: string; key: string; text: string; step: number | string | null; recordedAt: string }[]
  systemMetrics: { id: string; cpuUtilization: number | null; ramUsed: number | string | null; ramTotal: number | string | null; gpus: { index: number; utilization: number | null; memoryUsed: number | string | null; memoryTotal: number | string | null }[]; elapsedTime: number | null; recordedAt: string }[]
  limit: number
}

export class HttpError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) { super(message); this.status = status; this.code = code }
}

let refreshFlight: Promise<void> | null = null
let csrfFlight: Promise<void> | null = null
let authGeneration = 0
async function ensureCsrf() {
  if (readCsrf(document.cookie)) return
  if (!csrfFlight) csrfFlight = fetch('/api/v1/auth/csrf', { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(15000) }).then(response => {
    if (!response.ok) throw new HttpError(response.status, 'CSRF_FAILED', '인증 보안 정보를 준비하지 못했습니다.')
  }).finally(() => { csrfFlight = null })
  await csrfFlight
}

export async function api<T>(path: string, options: { method?: string; body?: unknown; signal?: AbortSignal; retryAuth?: boolean } = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const generation = authGeneration
  const mutation = !['GET', 'HEAD'].includes(method)
  if (mutation) await ensureCsrf()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (mutation) headers['X-XSRF-TOKEN'] = readCsrf(document.cookie)
  let response: Response
  try {
    const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000)
    response = await fetch(path, { method, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), credentials: 'include', cache: 'no-store', redirect: 'error', signal })
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new Error('서버에 연결하지 못했습니다. 연결 상태를 확인한 후 다시 시도하세요. 요청은 자동 재시도하지 않았습니다.')
  }
  if (response.status === 401 && options.retryAuth !== false && !path.startsWith('/api/v1/auth/')) {
    if (generation === authGeneration) {
      if (!refreshFlight) refreshFlight = api<void>('/api/v1/auth/refresh', { method: 'POST', retryAuth: false }).then(() => { authGeneration++ }).finally(() => { refreshFlight = null })
      await refreshFlight
    }
    return api<T>(path, { ...options, retryAuth: false })
  }
  const raw = await response.text()
  let data: unknown
  if (raw) { try { data = parseResponse(raw) } catch { throw new Error('서버 응답을 읽지 못했습니다. 백엔드 연결을 확인하세요.') } }
  if (!response.ok) {
    const error = object(data)
    const fields = object(error?.errors)
    const message = response.status === 401 ? '로그인이 필요합니다. 다시 로그인해 주세요.' : response.status === 409 ? '다른 요청으로 정보가 변경되었습니다. 최신 정보를 다시 불러오세요.' : typeof error?.message === 'string' ? error.message : `요청을 처리하지 못했습니다. (${response.status})`
    throw new HttpError(response.status, String(error?.code ?? 'REQUEST_FAILED'), message + (fields ? ` ${Object.values(fields).filter(item => typeof item === 'string').join(' ')}` : ''))
  }
  return data as T
}

export async function allProjects(signal?: AbortSignal): Promise<Project[]> {
  const result = await api<Page<Project>>('/api/v1/projects?page=0&size=100', { signal })
  const rows = [...result.items]
  for (let page = 1; page < result.totalPages; page++) rows.push(...(await api<Page<Project>>(`/api/v1/projects?page=${page}&size=100`, { signal })).items)
  return rows
}

export const projectPath = (id: string) => `/api/v1/projects/${encodeURIComponent(id)}`

// Flat storage points are grouped by event context. Repeated keys start another event,
// so two writes at the same step/time never overwrite each other.
export function metricEvents(points: Metric[]): MetricRecord[] {
  const result: MetricRecord[] = []
  let previous = ''
  for (const point of points) {
    const identity = JSON.stringify([point.step, point.epoch, point.elapsedTime, point.recordedAt])
    let event = result.at(-1)
    if (!event || identity !== previous || Object.hasOwn(event.metrics, point.key)) {
      event = { recordedAt: point.recordedAt, metrics: Object.create(null) }
      if (typeof point.step === 'number' && Number.isSafeInteger(point.step)) event.step = point.step
      if (typeof point.epoch === 'number') event.epoch = point.epoch
      if (typeof point.elapsedTime === 'number') event.elapsedTime = point.elapsedTime
      result.push(event)
    }
    event.metrics[point.key] = point.value
    previous = identity
  }
  return result
}
