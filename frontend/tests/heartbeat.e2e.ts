import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const base = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
const projectId = '9223372036854775800', runId = '9223372036854775801';
const startedAt = '2026-11-19T00:00:00Z';

test('heartbeat disconnection recovers on polling and only the owner can manually end a run', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  let connectionStatus = 'DISCONNECTED', stopped = false, stopCalls = 0, userId = '1';
  const project = { projectId, createdBy: '1', name: 'Heartbeat project', description: null,
    visibility: 'PUBLIC', createdAt: startedAt, updatedAt: null, version: 0 };
  const run = () => ({ runId, projectId, name: 'network-interruption-training', metadata: {},
    status: stopped ? 'FAILED' : 'RUNNING', connectionStatus: stopped ? 'INACTIVE' : connectionStatus,
    startedAt, endedAt: stopped ? new Date().toISOString() : null,
    error: stopped ? 'Manually stopped by project owner' : null, lastHeartbeatAt: startedAt });
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/auth/me')) return route.fulfill({ json: { userId, name: 'Reader', email: 'reader@example.test' } });
    if (url.pathname.endsWith('/auth/csrf')) return route.fulfill({ headers: { 'Set-Cookie': 'XSRF-TOKEN=csrf; Path=/' }, json: {} });
    if (url.pathname === '/api/v1/projects') return route.fulfill({ json: { items: [project], page: 0, size: 100, totalPages: 1, totalElements: 1 } });
    if (url.pathname.endsWith(`/projects/${projectId}`)) return route.fulfill({ json: project });
    if (url.pathname.endsWith('/runs')) return route.fulfill({ json: { items: [run()], page: 0, size: 100, totalPages: 1, totalElements: 1,
      counts: { RUNNING: stopped ? 0 : 1, FAILED: stopped ? 1 : 0, COMPLETED: 0 } } });
    if (url.pathname.endsWith('/summaries')) return route.fulfill({ json: [{ runId, lastStep: 1, metrics: [] }] });
    if (url.pathname.endsWith(`/${runId}/stop`)) {
      stopCalls++;
      assert.equal(route.request().method(), 'POST');
      assert.equal(route.request().headers()['x-xsrf-token'], 'csrf');
      if (stopCalls === 1) return route.fulfill({ status: 503, json: { message: '잠시 후 다시 시도해 주세요.' } });
      stopped = true;
      return route.fulfill({ status: 204 });
    }
    if (url.pathname.endsWith(`/runs/${runId}`)) return route.fulfill({ json: run() });
    return route.fulfill({ status: 404, json: { message: 'unexpected request' } });
  });
  try {
    await page.goto(`${base}/#/projects/${projectId}/runs`);
    await page.locator('.runs-table .badge.DISCONNECTED').waitFor();
    await page.getByRole('button', { name: /^진행 중 1$/ }).waitFor();
    await page.goto(`${base}/#/projects/${projectId}/runs/${runId}/environment`);
    await page.getByText('클라이언트 연결이 끊겼습니다', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Run 종료 처리', exact: true }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
    await page.screenshot({ path: fileURLToPath(new URL('../test-results/heartbeat-mobile.png', import.meta.url)), fullPage: true });
    connectionStatus = 'CONNECTED';
    await page.locator('.run-name .badge.RUNNING').waitFor();
    assert.equal(await page.locator('.connection-notice').count(), 0);
    assert.equal(stopCalls, 0);
    await page.getByRole('button', { name: 'Run 종료 처리', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Run을 종료 처리할까요?' });
    await dialog.waitFor();
    assert.match(await dialog.innerText(), /학습 프로세스는 직접 중지/);
    await dialog.getByRole('button', { name: '종료 처리', exact: true }).click();
    await dialog.getByRole('alert').waitFor();
    await dialog.getByRole('button', { name: '종료 처리', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    await page.getByText('소유자가 실험을 종료 처리했습니다', { exact: true }).waitFor();
    assert.equal(stopCalls, 2);
    assert.equal(await page.getByRole('button', { name: 'Run 종료 처리', exact: true }).count(), 0);
    stopped = false; connectionStatus = 'DISCONNECTED'; userId = '2';
    await page.reload();
    await page.getByText('클라이언트 연결이 끊겼습니다', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Run 종료 처리', exact: true }).count(), 0);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});
