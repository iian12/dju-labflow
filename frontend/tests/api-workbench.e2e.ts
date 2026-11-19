import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';
import { endpoints } from '../src/apiCatalog.ts';
const base = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
const operations = endpoints.map(item => ({ method: item.method, path: item.path }));

test('only ADMIN sees the API test entry and can open the route', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    for (const role of ['USER', 'ADMIN', 'ANONYMOUS']) {
      const page = await browser.newPage();
      let gates = 0;
      await page.route('**/api/v1/**', route => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith('/auth/me')) return route.fulfill({ status: role === 'ANONYMOUS' ? 401 : 200, json: { userId: '1', name: 'Tester', email: 'tester@example.test', role } });
        if (path.endsWith('/admin/api-test')) { gates++; return route.fulfill({ status: role === 'ADMIN' ? 200 : 403, json: operations }); }
        return route.fulfill({ status: 401, json: {} });
      });
      await page.goto(`${base}/#/api-test`);
      if (role === 'ADMIN') {
        await page.getByRole('heading', { name: 'API 테스트', exact: true }).waitFor();
        assert.ok(gates > 0);
        assert.equal(await page.getByRole('link', { name: 'API 테스트', exact: true }).count(), 1);
      } else {
        await page.getByRole('heading', { name: 'ADMIN 전용 페이지입니다', exact: true }).waitFor();
        assert.equal(await page.getByRole('button', { name: '요청 보내기 ↗' }).count(), 0);
        assert.equal(await page.getByRole('link', { name: 'API 테스트', exact: true }).count(), 0);
        assert.equal(gates, 0);
      }
      await page.close();
    }
  } finally { await browser.close(); }
});

test('workbench preserves request precision, masks secrets, fits themes and blocks revoked admins before sending', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  let admin = true, writes = 0;
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({ json: { userId: '1', name: 'Admin', email: 'admin@example.test', role: 'ADMIN' } });
    if (path.endsWith('/admin/api-test')) return route.fulfill({ status: admin ? 200 : 403, json: admin ? operations : {} });
    if (path.endsWith('/auth/csrf')) return route.fulfill({ headers: { 'Set-Cookie': 'XSRF-TOKEN=test; Path=/' }, json: {} });
    if (path.endsWith('/metrics')) {
      writes++;
      assert.equal(route.request().headers().authorization, 'Bearer lfp_private-test-key');
      assert.equal(route.request().headers().cookie, undefined);
      assert.match(route.request().postData()!, /"step": 9223372036854775807/);
      return route.fulfill({ status: 204 });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  try {
    await page.goto(`${base}/#/api-test/run-metrics`);
    await page.getByRole('heading', { name: 'API 테스트', exact: true }).waitFor();
    await page.getByLabel('Run ID', { exact: true }).fill('9223372036854775801');
    await page.getByLabel('SDK API key', { exact: true }).fill('lfp_private-test-key');
    await page.getByLabel('Step', { exact: true }).fill('9223372036854775807');
    await page.getByRole('button', { name: '요청 보내기 ↗', exact: true }).click();
    await page.locator('.api-response-body').getByText('응답 본문 없음', { exact: true }).waitFor();
    assert.equal(writes, 1);
    assert.equal((await page.locator('.api-results').innerText()).includes('lfp_private-test-key'), false);
    await mkdir('test-results', { recursive: true });
    await page.screenshot({ path: 'test-results/api-workbench-desktop.png', fullPage: true });
    for (const theme of ['light', 'dark']) {
      await page.evaluate(value => document.documentElement.dataset.theme = value, theme);
      await page.setViewportSize({ width: 390, height: 844 });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${theme} mobile overflow`);
      await page.screenshot({ path: `test-results/api-workbench-mobile-${theme}.png`, fullPage: true });
    }
    admin = false;
    await page.getByRole('button', { name: '요청 보내기 ↗', exact: true }).click();
    await page.getByRole('heading', { name: 'ADMIN 전용 페이지입니다', exact: true }).waitFor();
    assert.equal(writes, 1);
    assert.equal(await page.getByLabel('SDK API key', { exact: true }).count(), 0);
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
