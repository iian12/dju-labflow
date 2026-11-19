export type RecordKind = 'metrics' | 'logs' | 'texts' | 'system-metrics';
export type RecordDirection = 'latest' | 'before' | 'after';
export type RecordLocation = { direction: RecordDirection; cursor?: string };
export type Identifier = string | number;
export type SavedProject = { projectId: Identifier; name: string };
export type SavedRun = { runId: Identifier; name: string; status: 'RUNNING' | 'COMPLETED' | 'FAILED'; startedAt: string; endedAt: string | null; error: string | null; lastHeartbeatAt: string | null };
export type ListPage<T> = { items: T[]; page: number; totalPages: number; totalElements: number };
export type RecordItem = {
  id: Identifier; recordedAt: string; key?: string; value?: number;
  step?: Identifier | null; epoch?: number | null; elapsedTime?: number | null;
  message?: string; source?: string; level?: string; text?: string;
  cpuUtilization?: number | null; ramUsed?: Identifier | null; ramTotal?: Identifier | null;
  gpus?: { index: number; utilization: number | null; memoryUsed: Identifier | null; memoryTotal: Identifier | null }[];
};
export type RecordPage = { items: RecordItem[]; limit: number; hasMore: boolean; beforeCursor: string | null; afterCursor: string | null; nextCursor: string | null };

export { ApiError as RecordReadError, parseJson as parseRecordJson } from './api.ts';
import { api } from './api.ts';
export function readRecordsApi<T>(path: string, signal?: AbortSignal, retryAuth = true): Promise<T> {
  return api<T>(path, { signal, retryAuth });
}
export const recordKinds: { id: RecordKind; label: string }[] = [
  { id: 'metrics', label: 'Metrics' }, { id: 'logs', label: 'Logs' },
  { id: 'texts', label: 'Texts' }, { id: 'system-metrics', label: 'System' },
];
export function recordKind(value: string): RecordKind {
  return recordKinds.find(kind => kind.id === value)?.id ?? 'metrics';
}
export function recordPageUrl(projectId: string, runId: string, kind: RecordKind, location: RecordLocation, limit = 200) {
  const query = new URLSearchParams({ limit: String(limit) });
  if (location.direction === 'latest') query.set('latest', 'true');
  else if (location.cursor) query.set(location.direction, location.cursor);
  else throw new Error('이동할 기록 위치가 없습니다.');
  return `/api/v1/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/records/${kind}?${query}`;
}
export function recordNavigation(page: RecordPage, location: RecordLocation) {
  const present = page.items.length > 0;
  return {
    older: present && !!page.beforeCursor && (location.direction === 'after' || page.hasMore),
    newer: present && !!page.afterCursor && location.direction !== 'latest' && (location.direction === 'before' || page.hasMore),
  };
}
