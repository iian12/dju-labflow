import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { chromium } from 'playwright';

const root = fileURLToPath(new URL('../', import.meta.url));
const backend = process.env.LABFLOW_BACKEND_URL;
const project = process.env.LABFLOW_RECORDS_PROJECT;
const run = process.env.LABFLOW_RECORDS_RUN;
const key = process.env.LABFLOW_RECORDS_KEY;
const cookie = process.env.LABFLOW_RECORDS_COOKIE;
if (!backend || !project || !run || !key || !cookie) throw new Error('Run through RecordCursorBrowserIntegrationTests');
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0, open: false } });
const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  await server.listen();
  const address = server.httpServer?.address();
  if (!address || typeof address === 'string') throw new Error('No frontend port');
  const base = `http://127.0.0.1:${address.port}`;
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.addCookies([{ name: 'access_token', value: cookie, url: base, httpOnly: true, sameSite: 'Lax' }]);
  const page = await context.newPage();
  const errors: string[] = [], queries: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (request.url().includes('/records/logs?')) queries.push(request.url()); });
  await page.goto(`${base}/#/records/${project}/${run}/logs`);
  await page.getByText('log-1004', { exact: true }).waitFor();
  assert.equal(await page.getByText('log-805', { exact: true }).count(), 1);
  assert.equal(await page.getByRole('alert').count(), 0);
  await page.getByRole('button', { name: '이전 기록', exact: true }).click();
  await page.getByText('log-804', { exact: true }).waitFor();
  assert.match(queries.at(-1)!, /before=/);
  const write = async (message: string) => {
    const response = await fetch(`${backend}/api/v1/sdk/runs/${run}/logs`, {
      method: 'POST', headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json', 'X-Request-ID': crypto.randomUUID() },
      body: JSON.stringify({ message, source: 'USER', level: 'INFO' }),
    });
    assert.equal(response.status, 204);
  };
  const historicalRequests = queries.length;
  await write('new-live-log');
  await page.waitForTimeout(5500);
  assert.equal(queries.length, historicalRequests);
  assert.equal(await page.getByText('log-804', { exact: true }).count(), 1);
  await page.getByRole('button', { name: '다음 기록', exact: true }).click();
  await page.getByText('log-1004', { exact: true }).waitFor();
  await page.getByRole('button', { name: '최신 기록 보기', exact: true }).click();
  await page.getByText('new-live-log', { exact: true }).waitFor();
  await write('arrived-by-poll');
  await page.getByText('arrived-by-poll', { exact: true }).waitFor({ timeout: 12000 });
  await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
  await page.screenshot({ path: fileURLToPath(new URL('../test-results/records-real-desktop.png', import.meta.url)), fullPage: true });
  await page.getByRole('link', { name: 'Metrics', exact: true }).click();
  await page.getByRole('img', { name: 'loss 현재 페이지 학습 곡선' }).waitFor();
  await page.getByRole('cell', { name: '0.125', exact: true }).waitFor();
  await page.getByRole('link', { name: 'Texts', exact: true }).click();
  await page.getByText('실제 서버 텍스트 🐈', { exact: true }).waitFor();
  await page.getByRole('link', { name: 'System', exact: true }).click();
  await page.getByRole('cell', { name: '25%', exact: true }).waitFor();
  assert.match(await page.locator('main').innerText(), /GPU 0: 50%/);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.screenshot({ path: fileURLToPath(new URL('../test-results/records-real-mobile.png', import.meta.url)), fullPage: true });
  assert.deepEqual(errors, []);
  console.log('PASS: record cursors and latest through actual HTTP');
} finally {
  await browser.close();
  await server.close();
}
