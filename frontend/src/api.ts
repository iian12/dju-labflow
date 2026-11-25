import { publishSessionChange, sessionStamp, withSessionLock } from './sessionCoordinator.ts';

export class ApiError extends Error {
  status: number; code: string;
  constructor(message: string, status = 0, code = '') { super(message); this.status = status; this.code = code; }
}
export function parseJson(text: string): unknown {
  return JSON.parse(text.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token =>
    /^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token)) ? JSON.stringify(token) : token),
    (key, value: unknown) => typeof value === 'number' && (key === 'id' || key.endsWith('Id')) ? String(value) : value);
}
function csrf() {
  const cookie = document.cookie.split(';').map(value => value.trim()).find(value => value.startsWith('XSRF-TOKEN='));
  try { return cookie ? decodeURIComponent(cookie.slice(11)) : ''; } catch { return ''; }
}
let csrfFlight: Promise<void> | null = null;
let refreshFlight: Promise<void> | null = null;
let generation = 0;
async function ensureCsrf() {
  if (csrf()) return;
  if (!csrfFlight) csrfFlight = api<void>('/api/v1/auth/csrf', { retryAuth: false }).finally(() => { csrfFlight = null; });
  await csrfFlight;
  if (!csrf()) throw new ApiError('보안 정보를 준비하지 못했습니다. 쿠키 설정을 확인하고 다시 시도해 주세요.', 403);
}
type Options = { method?: string; body?: unknown; signal?: AbortSignal; retryAuth?: boolean };
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const authExempt = ['/api/v1/auth/login', '/api/v1/auth/sign-up'].includes(path);
  if (!['GET', 'HEAD'].includes(method) && !authExempt) await ensureCsrf();
  const epoch = generation;
  const session = sessionStamp();
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (csrf() && method !== 'GET') headers['X-XSRF-TOKEN'] = csrf();
  let response: Response;
  try {
    response = await fetch(path, { method, headers, credentials: 'include', cache: 'no-store', redirect: 'error',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000) });
  } catch (issue) {
    if (options.signal?.aborted) throw issue;
    throw new ApiError('서버에 연결하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
  }
  if (response.status === 401 && options.retryAuth !== false && !authExempt && path !== '/api/v1/auth/refresh') {
    try {
      if (epoch === generation) {
        if (!refreshFlight) refreshFlight = withSessionLock(async () => {
          if (sessionStamp() !== session) throw new ApiError('다른 탭에서 로그인 상태가 변경되었습니다. 다시 시도해 주세요.', 401, 'SESSION_CHANGED');
          // Another tab may already have rotated the shared HttpOnly cookie while we waited.
          try { await api('/api/v1/auth/me', { retryAuth: false }); return; }
          catch (issue) { if (!(issue instanceof ApiError) || issue.status !== 401) throw issue; }
          try { await api<void>('/api/v1/auth/refresh', { method: 'POST', retryAuth: false }); }
          catch (issue) {
            if (!(issue instanceof ApiError) || issue.status !== 401) throw issue;
            // A storage-disabled tab may have refreshed concurrently; check the shared cookie.
            for (const delay of [0, 150, 500]) {
              if (delay) await new Promise(resolve => setTimeout(resolve, delay));
              try { await api('/api/v1/auth/me', { retryAuth: false }); return; }
              catch (probe) { if (!(probe instanceof ApiError) || probe.status !== 401) throw probe; }
            }
            publishSessionChange('expired');
            throw issue;
          }
        }).then(() => { generation++; }).finally(() => { refreshFlight = null; });
        await refreshFlight;
      }
      if (sessionStamp() !== session) throw new ApiError('로그인 상태가 변경되었습니다. 다시 시도해 주세요.', 401, 'SESSION_CHANGED');
      options.signal?.throwIfAborted();
      return api<T>(path, { ...options, retryAuth: false });
    } catch (issue) {
      throw issue;
    }
  }
  const text = await response.text();
  let data: unknown;
  if (text) {
    try { data = parseJson(text); }
    catch { throw new ApiError('서버 응답을 읽지 못했습니다. API 연결을 확인해 주세요.', response.status); }
  }
  if (!response.ok) {
    const value = data as { message?: string; code?: string; errors?: Record<string, string> } | undefined;
    const message = response.status === 401 ? '로그인이 필요합니다. 이메일과 비밀번호를 확인해 주세요.'
      : response.status === 403 ? '이 작업을 수행할 권한이 없습니다.'
      : response.status === 404 ? '프로젝트 또는 실험을 찾을 수 없습니다.'
      : response.status === 409 ? '정보가 변경되었습니다. 최신 정보를 불러온 후 다시 시도해 주세요.'
      : response.status === 429 ? '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
      : value?.message ?? '요청을 처리하지 못했습니다.';
    throw new ApiError(message + (value?.errors ? ' ' + Object.values(value.errors).join(' ') : ''), response.status, value?.code);
  }
  return data as T;
}
export async function logout() {
  if (refreshFlight) await refreshFlight.catch(() => {});
  await withSessionLock(async () => {
    await api('/api/v1/auth/logout', { method: 'POST', retryAuth: false });
    generation++; publishSessionChange('logout');
  });
}
export type Page<T> = { items: T[]; page: number; size: number; totalPages: number; totalElements: number };
export async function allPages<T>(path: string, signal?: AbortSignal): Promise<Page<T>> {
  const first = await api<Page<T>>(path, { signal }); const items = [...first.items];
  for (let index = first.page + 1; index < first.totalPages; index++) {
    const url = new URL(path, 'http://localhost'); url.searchParams.set('page', String(index));
    items.push(...(await api<Page<T>>(url.pathname + url.search, { signal })).items);
  }
  return { ...first, items };
}
export const projectPath = (id: string) => `/api/v1/projects/${encodeURIComponent(id)}`;
