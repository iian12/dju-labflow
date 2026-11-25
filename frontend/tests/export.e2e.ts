import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { chromium } from 'playwright';
import type { Download, Page, Route } from 'playwright';

const base = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
const projectId = '9223372036854775800', runId = '9223372036854775801';
const runPath = `/api/v1/projects/${projectId}/runs/${runId}`;
const when = '2026-11-24T00:00:00Z';
const project = { projectId, createdBy: '1', name: 'Export project', description: '', visibility: 'PRIVATE', createdAt: when, version: 0 };
const run = { runId, projectId, name: 'Export run', status: 'COMPLETED', metadata: {}, startedAt: when, endedAt: when, error: null, lastHeartbeatAt: null };
const emptyPage = { items: [], limit: 1000, hasMore: false, nextCursor: null };

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
}

async function mockRun(page: Page, parameters: (route: Route) => Promise<void>, records: (route: Route) => Promise<void>) {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({ json: { userId: '1', name: 'Reader', email: 'reader@example.com', role: 'USER' } });
    if (path === '/api/v1/projects') return route.fulfill({ json: { items: [project], page: 0, size: 100, totalPages: 1, totalElements: 1 } });
    if (path === `/api/v1/projects/${projectId}`) return route.fulfill({ json: project });
    if (path.endsWith('/runs')) return route.fulfill({ json: { items: [run], page: 0, size: 100, totalPages: 1, totalElements: 1, counts: { COMPLETED: 1, RUNNING: 0, FAILED: 0 } } });
    if (path.endsWith('/summaries')) return route.fulfill({ json: [{ runId, lastStep: null, metrics: [] }] });
    if (path === runPath) return route.fulfill({ json: run });
    if (path === `${runPath}/records`) return parameters(route);
    if (path.startsWith(`${runPath}/records/`)) return records(route);
    if (path.endsWith('/artifacts')) return route.fulfill({ json: { items: [], page: 0, size: 50, totalPages: 0, totalElements: 0 } });
    return route.fulfill({ status: 404, json: {} });
  });
}

async function readExport(download: Download) {
  assert.equal(download.suggestedFilename(), `run-${runId}.json`);
  return JSON.parse(await readFile((await download.path())!, 'utf8'));
}

test('export waits for parameters and includes every record kind and cursor page', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  const requested = deferred(), release = deferred();
  const downloads: Download[] = [];
  page.on('download', download => downloads.push(download));
  const parameters = [{ key: 'learning_rate', value: '0.001' }, { key: 'note', value: '' }];
  const metrics = [
    { id: '9223372036854775802', key: 'loss', step: 1, value: 0.5, recordedAt: when },
    { id: '9223372036854775803', key: 'loss', step: 2, value: 0.25, recordedAt: when },
  ];
  const records = {
    logs: [{ id: '4', message: 'training complete', recordedAt: when }],
    texts: [{ id: '5', key: 'prediction', text: '결과', recordedAt: when }],
    'system-metrics': [{ id: '6', cpuUtilization: 25, gpus: [], recordedAt: when }],
  };
  const metricQueries: string[] = [];
  await mockRun(page, async route => {
    requested.resolve();
    await release.promise;
    await route.fulfill({ json: { parameters } });
  }, async route => {
    const url = new URL(route.request().url());
    const kind = url.pathname.split('/').at(-1)!;
    if (kind === 'metrics') {
      metricQueries.push(url.search);
      const next = url.searchParams.has('after');
      return route.fulfill({ json: { ...emptyPage, items: [metrics[next ? 1 : 0]], hasMore: !next, nextCursor: next ? null : 'review-cursor' } });
    }
    await route.fulfill({ json: { ...emptyPage, items: records[kind as keyof typeof records] } });
  });
  try {
    await page.goto(`${base}/#/projects/${projectId}/runs/${runId}/artifacts`);
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: '전체 기록 다운로드', exact: true }).click();
    await requested.promise;
    assert.equal(downloads.length, 0, 'No partial file is downloaded while parameters are pending');
    assert.equal(await page.getByRole('button', { name: '기록을 모으는 중…', exact: true }).isDisabled(), true);
    release.resolve();
    const output = await readExport(await download);
    assert.deepEqual(output, { run, parameters, metrics, ...records });
    assert.equal(metricQueries.length, 2);
    assert.equal(new URLSearchParams(metricQueries[1]).get('after'), 'review-cursor');
    assert.equal(downloads.length, 1);
  } finally {
    release.resolve();
    await browser.close();
  }
});

for (const failure of ['parameters', 'logs']) {
  test(`export stops on ${failure} failure and supports retry with empty parameters`, async () => {
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const page = await browser.newPage();
    const downloads: Download[] = [];
    const recordRequests: string[] = [];
    page.on('download', download => downloads.push(download));
    let fail = true;
    await mockRun(page, async route => {
      if (fail && failure === 'parameters') return route.fulfill({ status: 503, json: { message: 'Parameter lookup failed' } });
      await route.fulfill({ json: { parameters: [] } });
    }, async route => {
      const kind = new URL(route.request().url()).pathname.split('/').at(-1)!;
      recordRequests.push(kind);
      if (fail && kind === failure) return route.fulfill({ status: 503, json: { message: 'Log lookup failed' } });
      await route.fulfill({ json: emptyPage });
    });
    try {
      await page.goto(`${base}/#/projects/${projectId}/runs/${runId}/artifacts`);
      await page.getByRole('button', { name: '전체 기록 다운로드', exact: true }).click();
      const alert = page.getByRole('alert');
      await alert.waitFor();
      assert.match(await alert.innerText(), /lookup failed/);
      assert.equal(downloads.length, 0, 'A failed lookup must not produce an incomplete download');
      assert.equal(await page.getByRole('button', { name: '전체 기록 다운로드', exact: true }).isEnabled(), true);
      assert.deepEqual(recordRequests, failure === 'parameters' ? [] : ['metrics', 'logs']);
      fail = false;
      const download = page.waitForEvent('download');
      await alert.getByRole('button', { name: '다시 불러오기' }).click();
      const output = await readExport(await download);
      assert.deepEqual(output, { run, parameters: [], metrics: [], logs: [], texts: [], 'system-metrics': [] });
      assert.equal(downloads.length, 1);
      assert.equal(await page.getByRole('alert').count(), 0);
    } finally { await browser.close(); }
  });
}
