import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { api, metricEvents, HttpError } from '../src/productApi.ts'
import type { Metric } from '../src/productApi.ts'

const originalFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = originalFetch })

test('pending login preserves verification error code without attempting token refresh', async () => {
  globalThis.document = { cookie: 'XSRF-TOKEN=csrf' } as Document
  const calls: string[] = []
  globalThis.fetch = (async (path: string) => {
    calls.push(path)
    return new Response('{"code":"EMAIL_VERIFICATION_REQUIRED","message":"Email verification is required"}', { status: 401 })
  }) as typeof fetch
  await assert.rejects(api('/api/v1/auth/login', { method: 'POST', body: { email: 'pending@example.test', password: 'test-password' }, retryAuth: false }),
    (error: unknown) => error instanceof HttpError && error.code === 'EMAIL_VERIFICATION_REQUIRED')
  assert.deepEqual(calls, ['/api/v1/auth/login'])
})

test('flat metric events retain all keys and split duplicate writes without inventing values', () => {
  const base = { step: 18, epoch: 1, elapsedTime: 1.2, recordedAt: '2026-11-01T00:00:00Z' }
  const points: Metric[] = [
    { ...base, id: '1', key: 'loss', value: 0.5 },
    { ...base, id: '2', key: 'accuracy', value: 0.8 },
    { ...base, id: '3', key: 'loss', value: 0.4 },
    { ...base, id: '4', key: '__proto__', value: 1 },
  ]
  const rows = metricEvents(points)
  assert.equal(rows.length, 2)
  assert.equal(rows[0].metrics.loss, 0.5)
  assert.equal(rows[0].metrics.accuracy, 0.8)
  assert.equal(rows[1].metrics.loss, 0.4)
  assert.equal(rows[1].metrics.accuracy, undefined)
  assert.equal(rows[1].metrics.__proto__, 1)
})

test('unsafe integer steps are never rounded into chart coordinates', () => {
  const rows = metricEvents([{ id: '1', key: 'loss', value: 0, step: '9223372036854775807', epoch: 1, elapsedTime: null, recordedAt: 'now' }])
  assert.equal(rows[0].step, undefined)
  assert.equal(rows[0].epoch, 1)
  assert.equal(rows[0].metrics.loss, 0)
})

test('parallel unauthorized queries refresh once and preserve response ID precision', async () => {
  globalThis.document = { cookie: 'XSRF-TOKEN=csrf' } as Document
  let refreshes = 0
  let access = false
  globalThis.fetch = (async (path: string) => {
    if (path.endsWith('/refresh')) { refreshes++; await new Promise(resolve => setTimeout(resolve, 10)); access = true; return new Response(null, { status: 204 }) }
    if (!access) return new Response('{"code":"UNAUTHORIZED"}', { status: 401 })
    return new Response('{"runId":9223372036854775807,"status":"COMPLETED"}')
  }) as typeof fetch
  const values = await Promise.all([api<{ runId: string }>('/api/v1/projects/1/runs/1'), api<{ runId: string }>('/api/v1/projects/1/runs/2')])
  assert.equal(refreshes, 1)
  assert.equal(values[0].runId, '9223372036854775807')
})

test('project writes include browser cookies and fresh CSRF but no Bearer key', async () => {
  globalThis.document = { cookie: 'XSRF-TOKEN=csrf-value' } as Document
  let request: RequestInit | undefined
  globalThis.fetch = (async (_path: string, init: RequestInit) => { request = init; return new Response(null, { status: 204 }) }) as typeof fetch
  await api('/api/v1/projects/1', { method: 'PUT', body: { name: 'updated', version: 0 } })
  assert.equal(request?.credentials, 'include')
  assert.equal((request?.headers as Record<string, string>)['X-XSRF-TOKEN'], 'csrf-value')
  assert.equal((request?.headers as Record<string, string>).Authorization, undefined)
})

test('write network failures are not retried; stale versions remain a user-visible conflict', async () => {
  globalThis.document = { cookie: 'XSRF-TOKEN=csrf-value' } as Document
  let calls = 0
  globalThis.fetch = (async () => { calls++; throw new TypeError('network failure') }) as typeof fetch
  await assert.rejects(api('/api/v1/projects', { method: 'POST', body: {} }), /자동 재시도하지 않았습니다/)
  assert.equal(calls, 1)
  globalThis.fetch = (async () => new Response('{"code":"STALE_STATE"}', { status: 409 })) as typeof fetch
  await assert.rejects(api('/api/v1/projects/1', { method: 'PUT', body: {} }), (error: unknown) => error instanceof HttpError && error.status === 409)
})
