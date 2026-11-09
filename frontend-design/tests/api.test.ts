import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { endpoints, initialValues } from '../src/apiCatalog.ts'
import { parseResponse, prepare, readCsrf, requestApi, sanitize } from '../src/apiClient.ts'
import type { Exchange } from '../src/apiClient.ts'

const endpoint = (id: string) => endpoints.find(item => item.id === id)!
const context = { projectId: '180000000000000001', runId: '180000000000000003', version: '0' }
const options = (fetcher: typeof fetch, onExchange: (entry: Exchange) => void = () => {}) => ({ apiKey: 'lfp_test-private-key', anonymous: false, autoCsrf: true, cookie: () => 'XSRF-TOKEN=csrf-cookie', fetcher, onExchange })

test('response parsing preserves 64-bit identifiers and text without rounding', () => {
  const parsed = parseResponse('{"projectId":180000000000000001,"createdBy":9223372036854775807,"version":0,"metric":0.25,"message":"180000000000000001 \\\"quoted\\\"","negative":-9223372036854775808}') as Record<string, unknown>
  assert.equal(parsed.projectId, context.projectId)
  assert.equal(parsed.createdBy, '9223372036854775807')
  assert.equal(parsed.negative, '-9223372036854775808')
  assert.equal(parsed.version, 0)
  assert.equal(parsed.metric, 0.25)
  assert.equal(parsed.message, '180000000000000001 "quoted"')
})

test('request encoding preserves raw JSON numbers and integer field precision', () => {
  const metric = endpoint('run-metrics')
  const result = prepare(metric, { ...initialValues(metric), metrics: '{"reward":-1,"custom":0.5}', step: '9223372036854775807' }, context)
  assert.equal(result.path, `/api/v1/sdk/runs/${context.runId}/metrics`)
  assert.match(result.body!, /"step": 9223372036854775807/)
  assert.equal(JSON.parse(result.body!).metrics.reward, -1)
  assert.equal('recordedAt' in JSON.parse(result.body!), false)
  const system = endpoint('run-system')
  const nested = prepare(system, { ...initialValues(system), system: '{"ramTotal":9223372036854775807,"gpus":[]}' }, context)
  assert.match(nested.body!, /"ramTotal":9223372036854775807/)
})

test('project versions and query parameters come from explicit context and fields', () => {
  const update = endpoint('project-update')
  assert.equal(JSON.parse(prepare(update, initialValues(update), context).body!).version, 0)
  assert.equal(prepare(endpoint('project-delete'), {}, context).path, `/api/v1/projects/${context.projectId}?version=0`)
  assert.equal(prepare(endpoint('project-list'), { page: '2', size: '10' }, context).path, '/api/v1/projects?page=2&size=10')
  assert.throws(() => prepare(update, initialValues(update), { ...context, version: '' }))
  assert.throws(() => prepare(endpoint('run-get'), {}, { ...context, runId: '' }))
})

test('raw mode sends the literal payload for server validation', () => {
  const raw = '{"step":9223372036854775807,"metrics":{"loss":"0.5"}}'
  assert.equal(prepare(endpoint('run-metrics'), {}, context, raw).body, raw)
  assert.throws(() => prepare(endpoint('run-metrics'), {}, context, '{broken'))
})

test('empty text remains present while absent optional timestamps are omitted', () => {
  const log = endpoint('run-logs')
  const body = JSON.parse(prepare(log, { ...initialValues(log), message: '' }, context).body!)
  assert.equal(body.message, '')
  assert.equal(body.source, 'USER')
  assert.equal('recordedAt' in body, false)
  assert.equal(prepare(endpoint('run-complete'), {}, context).body, undefined)
})

test('cookie parsing handles percent encoding and malformed cookie values', () => {
  assert.equal(readCsrf('other=value; XSRF-TOKEN=a%2Bb%3D; access_token=hidden'), 'a+b=')
  assert.equal(readCsrf('XSRF-TOKEN=%broken'), '')
  assert.equal(readCsrf('access_token=hidden'), '')
})

test('cookie mutations initialize CSRF and send credentials with the header', async () => {
  const calls: { path: string; init: RequestInit }[] = []
  const entries: Exchange[] = []
  let cookie = ''
  const fetcher = (async (path: string, init: RequestInit) => {
    calls.push({ path, init })
    if (path.endsWith('/csrf')) { cookie = 'XSRF-TOKEN=prepared'; return new Response(null, { status: 200 }) }
    return new Response(null, { status: 204 })
  }) as typeof fetch
  const result = await requestApi(endpoint('refresh'), { path: '/api/v1/auth/refresh' }, { ...options(fetcher, entry => entries.push(entry)), cookie: () => cookie })
  assert.deepEqual(calls.map(call => call.path), ['/api/v1/auth/csrf', '/api/v1/auth/refresh'])
  assert.equal(calls[1].init.credentials, 'include')
  assert.equal((calls[1].init.headers as Record<string, string>)['X-XSRF-TOKEN'], 'prepared')
  assert.equal(calls[1].init.body, undefined)
  assert.equal(result.exchange.ok, true)
  assert.equal(entries.length, 2)
})

test('SDK requests send only Bearer authentication and no cookies or CSRF', async () => {
  let sent: RequestInit | undefined
  const fetcher = (async (_path: string, init: RequestInit) => { sent = init; return new Response(null, { status: 204 }) }) as typeof fetch
  await requestApi(endpoint('run-complete'), { path: '/api/v1/sdk/runs/1/complete' }, options(fetcher))
  assert.equal(sent!.credentials, 'omit')
  assert.equal((sent!.headers as Record<string, string>).Authorization, 'Bearer lfp_test-private-key')
  assert.equal('X-XSRF-TOKEN' in sent!.headers!, false)
  assert.equal('Content-Type' in sent!.headers!, false)
})

test('anonymous and CSRF-off controls let users exercise authorization failures', async () => {
  const sent: RequestInit[] = []
  const fetcher = (async (_path: string, init: RequestInit) => { sent.push(init); return new Response(null, { status: 403 }) }) as typeof fetch
  await requestApi(endpoint('project-delete'), { path: '/api/v1/projects/1?version=0' }, { ...options(fetcher), anonymous: true })
  await requestApi(endpoint('refresh'), { path: '/api/v1/auth/refresh' }, { ...options(fetcher), autoCsrf: false })
  assert.equal(sent[0].credentials, 'omit')
  assert.equal(sent[1].credentials, 'include')
  assert.equal('X-XSRF-TOKEN' in sent[0].headers!, false)
  assert.equal('X-XSRF-TOKEN' in sent[1].headers!, false)
})

test('401 results are returned unchanged without automatic refresh or retry', async () => {
  let calls = 0
  const fetcher = (async () => { calls++; return new Response('{"code":"UNAUTHORIZED","message":"expired"}', { status: 401 }) }) as typeof fetch
  const result = await requestApi(endpoint('project-list'), { path: '/api/v1/projects' }, options(fetcher))
  assert.equal(calls, 1)
  assert.equal(result.exchange.status, 401)
  assert.equal(result.exchange.ok, false)
  assert.match(result.exchange.responseBody, /UNAUTHORIZED/)
})

test('network failures do not retry append operations or claim server failure', async () => {
  let calls = 0
  const fetcher = (async () => { calls++; throw new TypeError('secret connection details') }) as typeof fetch
  const result = await requestApi(endpoint('run-logs'), { path: '/api/v1/sdk/runs/1/logs', body: '{"message":"x"}' }, options(fetcher))
  assert.equal(calls, 1)
  assert.equal(result.exchange.status, null)
  assert.match(result.exchange.note!, /서버 처리 여부는 알 수 없으며/)
  assert.equal(JSON.stringify(result.exchange).includes('secret connection details'), false)
})

test('HTML fallback never counts as a successful API response', async () => {
  const fetcher = (async () => new Response('<!doctype html><html>Vite fallback</html>')) as typeof fetch
  const result = await requestApi(endpoint('project-get'), { path: '/api/v1/projects/1' }, options(fetcher))
  assert.equal(result.exchange.ok, false)
  assert.match(result.exchange.note!, /JSON이 아닌 응답/)
})

test('history masks secrets without hiding backend error codes', async () => {
  const secret = 'lfp_test-private-key'
  const cleaned = sanitize(`{"rawApiKey":"${secret}","password":"private-password","nested":{"Authorization":"Bearer ${secret}"},"code":"INVALID_INPUT"}`, [secret])
  assert.equal(cleaned.includes(secret), false)
  assert.equal(cleaned.includes('private-password'), false)
  assert.equal(JSON.parse(cleaned).code, 'INVALID_INPUT')
  const entries: Exchange[] = []
  const fetcher = (async () => new Response(null, { status: 204 })) as typeof fetch
  await requestApi(endpoint('verify'), { path: '/api/v1/auth/email-verifications/verify', body: '{"email":"a@example.com","code":"123456"}' }, options(fetcher, entry => entries.push(entry)))
  assert.equal(entries[0].requestBody.includes('123456'), false)
})

test('the manual UI covers every current business controller operation', () => {
  const root = resolve('../backend/src/main/java/com/dju/labflow')
  const operations: string[] = []
  for (const file of readdirSync(root, { recursive: true }).map(String).filter(file => file.endsWith('Controller.java'))) {
    const source = readFileSync(resolve(root, file), 'utf8')
    const prefix = /@RequestMapping\((?:value = )?"([^"]+)"/.exec(source)?.[1] ?? ''
    for (const match of source.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\("([^"]*)"\))?/g)) operations.push(`${match[1].toUpperCase()} ${prefix}${match[2] ?? ''}`)
  }
  assert.equal(new Set(endpoints.map(item => item.id)).size, endpoints.length)
  assert.deepEqual(endpoints.map(item => `${item.method} ${item.path}`).sort(), operations.sort())
})
