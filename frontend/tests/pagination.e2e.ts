import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';

const base = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
const when = '2026-11-25T00:00:00Z';
const project = { projectId: '1', createdBy: '1', name: 'Paged project', description: '', visibility: 'PRIVATE', createdAt: when, version: 0 };
const runs = Array.from({ length: 60 }, (_, index) => ({ runId: String(index + 1), projectId: '1', name: `run-${String(index + 1).padStart(3, '0')}`, status: index % 2 ? 'FAILED' : 'COMPLETED', metadata: {}, startedAt: when, endedAt: when, error: null }));
const point = (id: string) => ({ id, key: 'off_page_score', value: Number(id), step: 1, recordedAt: when });

test('server pages bound summary reads and preserve selections across search, sorting and navigation', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  const lists: URL[] = [], batches: string[][] = [], errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.clock.install();
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({ json: { userId: '1', name: 'Reader', email: 'reader@example.com' } });
    if (path === '/api/v1/projects') return route.fulfill({ json: { items: [project], totalPages: 1 } });
    if (path === '/api/v1/projects/1') return route.fulfill({ json: project });
    if (path.endsWith('/runs')) {
      lists.push(url);
      const ids = url.searchParams.get('runIds')?.split(',');
      let matches = runs.filter(run => run.name.includes(url.searchParams.get('search') ?? '') && (!ids || ids.includes(run.runId)) && (!url.searchParams.has('status') || run.status === url.searchParams.get('status')));
      if (url.searchParams.get('sort') === 'high') matches = [...matches].reverse();
      const index = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      return route.fulfill({ json: { items: matches.slice(index * size, (index + 1) * size), page: index, size, totalPages: Math.ceil(matches.length / size), totalElements: matches.length, projectTotal: 60, counts: { RUNNING: 0, COMPLETED: 30, FAILED: 30 }, availableMetrics: ['off_page_score'] } });
    }
    if (path.endsWith('/summaries')) {
      const ids = url.searchParams.get('runIds')!.split(','); batches.push(ids);
      return route.fulfill({ json: ids.map(runId => ({ runId, lastStep: 1, metrics: [point(runId)] })) });
    }
    if (path.endsWith('/compare')) {
      const ids = url.searchParams.get('runIds')!.split(',');
      return route.fulfill({ json: { metricKey: 'off_page_score', baselineRunId: ids[0], commonStep: 1, availableMetrics: ['off_page_score'], parameters: [], runs: ids.map(id => ({ ...runs.find(run => run.runId === id), lastStep: 1, latestMetric: point(id), commonStepMetric: point(id), deltaFromBaseline: Number(id) - Number(ids[0]), series: { points: [point(id)], sampled: false, totalPoints: 1 } })) } });
    }
    if (path.endsWith('/api-keys')) return route.fulfill({ json: { items: [], totalPages: 0 } });
    if (path.endsWith('/runs/1')) return route.fulfill({ json: runs[0] });
    if (path.endsWith('/records')) return route.fulfill({ json: { parameters: [] } });
    return route.fulfill({ status: 404, json: { message: `Unexpected ${path}` } });
  });
  try {
    await page.goto(`${base}/#/projects/1/runs`);
    await page.getByRole('link', { name: 'run-001', exact: true }).waitFor();
    assert.equal(await page.locator('tbody tr').count(), 20);
    assert.equal(lists.length, 1);
    assert.deepEqual(batches[0], runs.slice(0, 20).map(run => run.runId));
    await page.getByLabel('run-001 선택', { exact: true }).check();
    await page.getByRole('button', { name: '다음 페이지', exact: true }).click();
    await page.getByLabel('run-021 선택', { exact: true }).waitFor();
    await page.getByLabel('run-021 선택', { exact: true }).check();
    await page.getByText('2개 Run 선택', { exact: true }).waitFor();
    assert.equal(lists.at(-1)!.searchParams.get('page'), '1');
    await page.getByLabel('Run 검색').fill('run-059');
    await page.clock.runFor(300);
    await page.getByRole('link', { name: 'run-059', exact: true }).waitFor();
    assert.equal(await page.locator('tbody tr').count(), 1);
    assert.equal(lists.at(-1)!.searchParams.get('page'), '0');
    await page.getByLabel('Run 검색').fill('no-match');
    await page.clock.runFor(300);
    await page.getByRole('heading', { name: '검색 결과가 없습니다' }).waitFor();
    await page.getByRole('button', { name: '필터 초기화' }).click();
    await page.clock.runFor(300);
    await page.getByRole('link', { name: 'run-001', exact: true }).waitFor();
    await page.getByLabel('정렬', { exact: true }).selectOption('high');
    await page.getByRole('link', { name: 'run-060', exact: true }).waitFor();
    assert.equal(lists.at(-1)!.searchParams.get('metricKey'), 'off_page_score');
    await page.getByRole('button', { name: '결과 비교 →' }).click();
    await page.getByLabel('기준 Run', { exact: true }).waitFor();
    assert.equal(lists.at(-1)!.searchParams.get('runIds'), '1,21');
    assert.ok(batches.every(ids => ids.length <= 20));
    const count = lists.length, summaryCount = batches.length;
    await page.clock.runFor(11000);
    assert.equal(lists.length, count, 'Completed comparisons stop polling');
    await page.locator('.project-tabs a[data-view="settings"]').click();
    await page.getByRole('heading', { name: '프로젝트 정보', exact: true }).waitFor();
    await page.clock.runFor(11000);
    assert.equal(lists.length, count, 'Settings do not fetch the Run list');
    await page.goto(`${base}/#/projects/1/runs/1/parameters`);
    await page.getByRole('heading', { name: '학습 설정', exact: true }).waitFor();
    await page.clock.runFor(11000);
    assert.equal(lists.length, count, 'Details do not fetch the Run list');
    assert.equal(batches.length, summaryCount);
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('terminal records load the final page and stop automatic polling', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  let completed = false, reads = 0, states = 0;
  await page.clock.install();
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/runs/1')) { states++; return route.fulfill({ json: { ...runs[0], status: completed ? 'COMPLETED' : 'RUNNING', endedAt: completed ? when : null } }); }
    if (path.endsWith('/records/logs')) { reads++; return route.fulfill({ json: { items: [{ id: String(reads), message: completed ? 'final log' : 'working', source: 'STDOUT', level: 'INFO', recordedAt: when }], limit: 200, hasMore: false, beforeCursor: null, afterCursor: null, nextCursor: null } }); }
    return route.fulfill({ json: { items: [], totalPages: 0 } });
  });
  try {
    await page.goto(`${base}/#/records/1/1/logs`);
    await page.getByText('working', { exact: true }).waitFor();
    completed = true;
    await page.clock.runFor(5100);
    await page.getByText('종료된 실험 · 자동 갱신 중지', { exact: false }).waitFor();
    await page.getByText('final log', { exact: true }).waitFor();
    const count = reads, stateCount = states;
    await page.clock.runFor(16000);
    assert.equal(reads, count);
    assert.equal(states, stateCount);
  } finally { await browser.close(); }
});
