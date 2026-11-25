import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';
import type { BrowserContext } from 'playwright';

const base = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
}
async function backend(context: BrowserContext, initialAccess: boolean) {
  const entered = deferred(), release = deferred();
  const state = { access: initialAccess, refresh: true, refreshes: 0, logouts: 0, expired: 0, entered, release };
  await context.addInitScript(() => window.addEventListener('labflow-session-expired', () => {
    document.documentElement.dataset.expired = 'true';
  }));
  await context.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/csrf')) return route.fulfill({ headers: { 'Set-Cookie': 'XSRF-TOKEN=csrf; Path=/' }, json: {} });
    if (path.endsWith('/refresh')) {
      state.refreshes++;
      const accepted = state.refresh;
      state.refresh = false; // A refresh token is consumed exactly once.
      entered.resolve();
      await release.promise;
      if (!accepted) return route.fulfill({ status: 401, json: {} });
      state.refresh = true; state.access = true;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith('/logout')) {
      state.logouts++; state.access = state.refresh = false;
      return route.fulfill({ status: 204 });
    }
    if (!state.access) return route.fulfill({ status: 401, json: {} });
    if (path.endsWith('/me')) return route.fulfill({ json: { userId: '1', name: '동시 사용자', email: 'reader@example.com', role: 'USER' } });
    return route.fulfill({ json: { items: [], page: 0, size: 100, totalPages: 0, totalElements: 0 } });
  });
  return state;
}

for (const fallback of [false, true]) {
  test(`two tabs refresh once without false expiry (${fallback ? 'IndexedDB' : 'Web Locks'})`, async () => {
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const context = await browser.newContext();
    if (fallback) await context.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: undefined }));
    const state = await backend(context, false);
    const first = await context.newPage(), second = await context.newPage();
    const errors: string[] = [];
    for (const page of [first, second]) page.on('pageerror', error => errors.push(error.message));
    try {
      await Promise.all([first.goto(`${base}/#/projects`), second.goto(`${base}/#/projects`)]);
      await state.entered.promise;
      // Both tabs have a stale access cookie while one refresh is in flight.
      await first.waitForTimeout(150);
      assert.equal(state.refreshes, 1);
      state.release.resolve();
      await Promise.all([first, second].map(page => page.getByRole('heading', { name: '첫 프로젝트를 만들어 보세요' }).waitFor()));
      assert.equal(state.refreshes, 1);
      for (const page of [first, second]) {
        assert.equal(await page.getByRole('button', { name: '로그아웃', exact: true }).count(), 1);
        assert.equal(await page.locator('html').getAttribute('data-expired'), null);
      }
      assert.deepEqual(errors, []);
    } finally { state.release.resolve(); await browser.close(); }
  });
}

test('logout waits for another tab refresh and clears both tabs without resurrecting the session', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext();
  const state = await backend(context, true);
  const first = await context.newPage(), second = await context.newPage();
  try {
    await Promise.all([first.goto(`${base}/#/projects`), second.goto(`${base}/#/projects`)]);
    await Promise.all([first, second].map(page => page.getByRole('heading', { name: '첫 프로젝트를 만들어 보세요' }).waitFor()));
    state.access = false;
    const request = first.evaluate(async () => {
      const modulePath = '/src/api.ts';
      const { api } = await import(modulePath);
      try { await api('/api/v1/projects'); } catch { /* Logout may invalidate the pending read. */ }
    });
    await state.entered.promise;
    await second.getByRole('button', { name: '로그아웃', exact: true }).click();
    await second.waitForTimeout(100);
    assert.equal(state.logouts, 0, 'Logout must wait for the refresh lock');
    state.release.resolve();
    await second.waitForURL('**/#/login');
    await request;
    await first.waitForFunction(() => !Array.from(document.querySelectorAll('button')).some(button => button.textContent === '로그아웃'));
    assert.equal(state.logouts, 1);
    assert.equal(state.access, false);
    assert.equal(state.refresh, false);
    const refreshes = state.refreshes;
    await first.waitForTimeout(1000);
    assert.ok(state.refreshes <= refreshes + 1, 'Expiry notifications must not create a refresh loop');
  } finally { state.release.resolve(); await browser.close(); }
});
