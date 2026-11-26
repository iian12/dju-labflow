import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { chromium } from 'playwright';
import { endpoints } from '../src/apiCatalog.ts';
import { parseJson } from '../src/api.ts';

const email = process.env.LABFLOW_ADMIN_EMAIL;
if (!email || !process.env.LABFLOW_BACKEND_URL) throw new Error('Run through AdminApiBrowserIntegrationTests');
const root = fileURLToPath(new URL('../', import.meta.url));
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0, open: false } });
const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  await server.listen();
  const address = server.httpServer?.address();
  if (!address || typeof address === 'string') throw new Error('No frontend port');
  const base = `http://127.0.0.1:${address.port}`;
  const context = await browser.newContext();
  const page = await context.newPage(); page.setDefaultTimeout(12000);
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  assert.equal((await context.request.get(`${base}/api/v1/admin/api-test`)).status(), 401);
  await page.goto(`${base}/#/login`);
  await page.getByLabel('이메일', { exact: true }).fill(email);
  await page.getByLabel('비밀번호', { exact: true }).fill('workbench-test-password');
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.getByRole('link', { name: 'API 테스트', exact: true }).waitFor();
  const catalog = await context.request.get(`${base}/api/v1/admin/api-test`);
  const operations = await catalog.json() as { method: string; path: string }[];
  assert.deepEqual(operations.map(item => `${item.method} ${item.path}`).sort(), endpoints.map(item => `${item.method} ${item.path}`).sort());
  async function go(id: string) {
    await page.goto(`${base}/#/api-test/${id}`);
    await page.getByRole('heading', { name: endpoints.find(item => item.id === id)!.title, exact: true }).waitFor();
  }
  async function send() {
    const response = page.waitForResponse(response => response.url().startsWith(base + '/api/v1/') && !response.url().endsWith('/admin/api-test') && !response.url().endsWith('/auth/csrf') && !response.url().endsWith('/auth/me'));
    await page.getByRole('button', { name: '요청 보내기 ↗', exact: true }).click();
    const result = await response;
    assert.ok(result.ok(), `${result.status()} ${new URL(result.url()).pathname}`);
    await page.getByRole('button', { name: '요청 보내기 ↗', exact: true }).waitFor({ state: 'visible' });
    await page.waitForFunction(() => !(document.querySelector('.api-submit button') as HTMLButtonElement)?.disabled);
    return result;
  }
  await go('project-create');
  await page.getByLabel('이름 *', { exact: true }).fill('Workbench integration');
  await send();
  const projectId = await page.getByLabel('Project ID', { exact: true }).inputValue();
  assert.match(projectId, /^\d+$/);
  // Hash navigation keeps the context and credentials only in memory.
  await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: 'GET 프로젝트 상세', exact: true }).click();
  await send();
  assert.equal(await page.getByLabel('Version', { exact: true }).inputValue(), '0');
  await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: 'POST 프로젝트 API key 발급', exact: true }).click();
  await send();
  assert.ok(await page.getByLabel('SDK API key', { exact: true }).inputValue());
  await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: 'POST Run 생성', exact: true }).click();
  await send();
  const runId = await page.getByLabel('Run ID', { exact: true }).inputValue();
  assert.match(runId, /^\d+$/);
  for (const [id, title] of [['run-metrics', 'POST 메트릭 기록'], ['run-heartbeat', 'POST Heartbeat 전송']] as const) {
    await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: title, exact: true }).click();
    await send();
    assert.ok(id);
  }
  await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: 'POST 파일 업로드', exact: true }).click();
  const payload = Buffer.from('LabFlow ADMIN artifact\n한글 파일');
  await page.getByLabel('업로드할 파일', { exact: true }).setInputFiles({ name: '학습 결과.bin', mimeType: 'application/octet-stream', buffer: payload });
  await page.getByLabel('Idempotency-Key (비우면 자동 생성)', { exact: true }).fill('workbench-artifact');
  await send();
  const artifactId = await page.getByLabel('Artifact ID', { exact: true }).inputValue();
  assert.match(artifactId, /^\d+$/);
  await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: 'GET 웹 파일 다운로드', exact: true }).click();
  const download = page.waitForEvent('download');
  await send();
  const received = await download;
  assert.equal(received.suggestedFilename(), '학습 결과.bin');
  assert.deepEqual(await readFile((await received.path())!), payload);
  const records = await context.request.get(`${base}/api/v1/projects/${projectId}/runs/${runId}/records`);
  const stored = parseJson(await records.text()) as { metrics: unknown[] };
  assert.equal(stored.metrics.length, 2);
  assert.equal(await page.getByRole('alert').count(), 0);
  await page.locator('nav[aria-label="API 선택"]').getByRole('link', { name: 'POST 로그아웃', exact: true }).click();
  await page.getByRole('button', { name: '요청 보내기 ↗', exact: true }).click();
  await page.getByRole('heading', { name: 'ADMIN 전용 페이지입니다', exact: true }).waitFor();
  assert.equal(await page.getByLabel('SDK API key', { exact: true }).count(), 0);
  assert.deepEqual(errors, []);
  console.log('PASS: ADMIN workbench through actual HTTP');
} finally { await browser.close(); await server.close(); }
