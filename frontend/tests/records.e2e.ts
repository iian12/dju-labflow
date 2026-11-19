import assert from 'node:assert/strict';
import test from 'node:test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { chromium } from 'playwright';
import { parseRecordJson, readRecordsApi, recordNavigation, recordPageUrl } from '../src/runRecordsApi.ts';
import type { RecordPage } from '../src/runRecordsApi.ts';

const baseURL = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
const project = '9223372036854775806', run = '9223372036854775807';
const timestamp = '2026-11-18T16:00:00Z';
function log(index: number) { return { id: String(9007199254740993n + BigInt(index)), message: `log-${index}`, level: 'INFO', source: 'STDOUT', recordedAt: timestamp }; }
function payload(items: unknown[], older: boolean, first: number, end: number) {
  return { items, limit: 200, hasMore: older, beforeCursor: items.length ? `position-${first}` : null, afterCursor: items.length ? `position-${end}` : null, nextCursor: older ? `position-${first}` : null };
}

test('record helpers preserve Long precision, encode cursors and handle paging boundaries', () => {
  assert.deepEqual(parseRecordJson('{"id":9223372036854775807,"message":"9223372036854775807","value":0.5}'), { id: run, message: run, value: 0.5 });
  assert.match(recordPageUrl(project, run, 'logs', { direction: 'before', cursor: 'a+b/c=' }), /before=a%2Bb%2Fc%3D/);
  assert.throws(() => recordPageUrl(project, run, 'logs', { direction: 'after' }));
  const page: RecordPage = { items: [log(1)], limit: 200, hasMore: false, beforeCursor: 'before', afterCursor: 'after', nextCursor: null };
  assert.deepEqual(recordNavigation(page, { direction: 'latest' }), { older: false, newer: false });
  assert.deepEqual(recordNavigation(page, { direction: 'before', cursor: 'old' }), { older: false, newer: true });
  assert.deepEqual(recordNavigation(page, { direction: 'after', cursor: 'old' }), { older: true, newer: false });
  assert.deepEqual(recordNavigation({ ...page, items: [] }, { direction: 'before', cursor: 'old' }), { older: false, newer: false });
});

test('parallel reads refresh a cookie session once and retain numeric ID precision', async () => {
  const originalFetch = globalThis.fetch;
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const document = { cookie: '' };
  Object.defineProperty(globalThis, 'document', { configurable: true, value: document });
  let authenticated = false, refreshes = 0;
  try {
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      assert.equal(init?.credentials, 'include');
      if (path.endsWith('/csrf')) { document.cookie = 'XSRF-TOKEN=csrf-value'; return new Response('{}'); }
      if (path.endsWith('/refresh')) {
        refreshes++;
        assert.equal((init?.headers as Record<string, string>)['X-XSRF-TOKEN'], 'csrf-value');
        await new Promise(resolve => setTimeout(resolve, 10));
        authenticated = true;
        return new Response(null, { status: 204 });
      }
      return authenticated ? new Response(`{"id":${run}}`) : new Response(null, { status: 401 });
    }) as typeof fetch;
    const results = await Promise.all([readRecordsApi<{ id: string }>('/api/v1/projects/1'), readRecordsApi<{ id: string }>('/api/v1/projects/2')]);
    assert.equal(refreshes, 1);
    assert.equal(results[0].id, run);
    assert.equal(results[1].id, run);
  } finally {
    globalThis.fetch = originalFetch;
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else Reflect.deleteProperty(globalThis, 'document');
  }
});

test('latest records beyond 1000 navigate both ways, pause history polling and resume latest', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  let total = 1005;
  const queries: string[] = [];
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.clock.install();
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/projects') { await route.fulfill({ body: `{"items":[{"projectId":${project},"name":"실제 실험 프로젝트"}],"totalPages":1}`, contentType: 'application/json' }); return; }
    if (url.pathname.endsWith(`/runs/${run}`)) { await route.fulfill({ json: { runId: run, name: 'Long training', status: 'RUNNING', startedAt: timestamp, endedAt: null, error: null } }); return; }
    if (url.pathname.endsWith('/records/logs')) {
      queries.push(url.search);
      const before = url.searchParams.get('before'), after = url.searchParams.get('after');
      const first = before ? Math.max(0, Number(before.split('-')[1]) - 200) : after ? Number(after.split('-')[1]) + 1 : Math.max(0, total - 200);
      const end = before ? Number(before.split('-')[1]) : Math.min(total, first + 200);
      const data = payload(Array.from({ length: end - first }, (_, offset) => log(first + offset)), after ? end < total : first > 0, first, end - 1);
      // Exercise numeric JSON Long IDs rather than string-only mocks.
      await route.fulfill({ body: JSON.stringify(data).replace(/"id":"(\d+)"/g, '"id":$1'), contentType: 'application/json' }); return;
    }
    await route.fulfill({ json: { items: [], totalPages: 1, totalElements: 0 } });
  });
  try {
    await page.goto(`${baseURL}/#/records/${project}/${run}/logs`);
    await page.getByText('log-1004', { exact: true }).waitFor();
    assert.ok(await page.locator('.record-console').evaluate(element => element.scrollHeight - element.scrollTop - element.clientHeight <= 1));
    assert.equal(await page.getByText('log-805', { exact: true }).count(), 1);
    assert.equal(await page.getByText('log-0', { exact: true }).count(), 0);
    assert.match(queries[0], /latest=true/);
    await page.getByRole('button', { name: '이전 기록', exact: true }).click();
    await page.getByText('log-804', { exact: true }).waitFor();
    assert.match(queries.at(-1)!, /before=position-805/);
    const count = queries.length;
    total = 1006;
    await page.clock.runFor(11000);
    assert.equal(queries.length, count);
    assert.equal(await page.getByText('log-1005', { exact: true }).count(), 0);
    await page.getByRole('button', { name: '다음 기록', exact: true }).click();
    await page.getByText('log-1004', { exact: true }).waitFor();
    assert.match(queries.at(-1)!, /after=position-804/);
    await page.getByRole('button', { name: '최신 기록 보기', exact: true }).click();
    await page.getByText('log-1005', { exact: true }).waitFor();
    total = 1007;
    await page.clock.runFor(5100);
    await page.getByText('log-1006', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '다음 기록', exact: true }).isDisabled(), true);
    await page.screenshot({ path: join(tmpdir(), 'labflow-records-latest.png'), fullPage: true });
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('record tabs show real metric/text/system data and reset to latest on stream changes', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.addInitScript(() => localStorage.setItem('lf-theme', 'dark'));
  const queries: string[] = [];
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/projects') { await route.fulfill({ json: { items: [], totalPages: 1 } }); return; }
    if (url.pathname.endsWith(`/runs/${run}`)) { await route.fulfill({ json: { runId: run, name: 'CPU classifier', status: 'COMPLETED', startedAt: timestamp, endedAt: timestamp } }); return; }
    if (url.pathname.includes('/records/')) {
      queries.push(url.pathname + url.search);
      const item = url.pathname.endsWith('/metrics') ? { id: '1', key: 'validation_loss', value: 0.125, step: run, recordedAt: timestamp }
        : url.pathname.endsWith('/texts') ? { id: '2', key: 'prediction', text: '고양이 🐈\n원문', step: '20', recordedAt: timestamp }
        : { id: '3', cpuUtilization: 25, ramUsed: '1024', ramTotal: '2048', gpus: [{ index: 0, utilization: 50, memoryUsed: '64', memoryTotal: '128' }], recordedAt: timestamp };
      await route.fulfill({ json: payload([item], false, 0, 0) }); return;
    }
    await route.fulfill({ json: { items: [], totalPages: 1 } });
  });
  try {
    await page.goto(`${baseURL}/#/records/${project}/${run}/metrics`);
    await page.getByRole('img', { name: 'validation_loss 현재 페이지 학습 곡선' }).waitFor();
    assert.equal(await page.getByRole('cell', { name: run, exact: true }).count(), 1);
    await page.getByRole('link', { name: 'Texts', exact: true }).click();
    await page.getByText('고양이 🐈\n원문', { exact: true }).waitFor();
    await page.getByRole('link', { name: 'System', exact: true }).click();
    await page.getByRole('cell', { name: '25%', exact: true }).waitFor();
    assert.match(await page.locator('main').innerText(), /GPU 0: 50%/);
    assert.ok(queries.every(value => value.endsWith('limit=200&latest=true')));
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({ path: join(tmpdir(), 'labflow-records-mobile.png'), fullPage: true });
  } finally { await browser.close(); }
});

test('empty and unauthorized record pages show honest states and an available latest action', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  let forbidden = false;
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/records/')) { await route.fulfill(forbidden ? { status: 403 } : { json: payload([], false, 0, 0) }); return; }
    await route.fulfill({ json: url.pathname.endsWith(`/runs/${run}`) ? { runId: run, name: 'Empty', status: 'RUNNING', startedAt: timestamp } : { items: [], totalPages: 1 } });
  });
  try {
    await page.goto(`${baseURL}/#/records/${project}/${run}/logs`);
    await page.getByText('조회할 기록이 없습니다.', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '이전 기록', exact: true }).isDisabled(), true);
    forbidden = true;
    await page.getByRole('button', { name: '최신 기록 보기', exact: true }).click();
    await page.getByRole('alert').waitFor();
    assert.match(await page.getByRole('alert').innerText(), /권한이 없습니다/);
  } finally { await browser.close(); }
});
