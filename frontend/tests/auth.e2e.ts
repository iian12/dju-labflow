import assert from 'node:assert/strict';
import test from 'node:test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { chromium } from 'playwright';

const baseURL = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';

test('signup, email verification and login use backend contracts', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const calls: { action: string; body: Record<string, string>; csrf?: string }[] = [];
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/v1/auth/**', async route => {
    const request = route.request();
    const action = request.url().split('/auth/')[1];
    if (action === 'me') { await route.fulfill({ json: { userId: '1', name: '홍길동', email: 'researcher@example.com' } }); return; }
    if (action === 'csrf') {
      await route.fulfill({ status: 200, headers: { 'Set-Cookie': 'XSRF-TOKEN=test-csrf; Path=/; SameSite=Lax' }, json: {} });
      return;
    }
    calls.push({ action, body: request.postDataJSON(), csrf: request.headers()['x-xsrf-token'] });
    await route.fulfill({ status: action === 'sign-up' ? 201 : 204 });
  });
  try {
    for (const entry of ['/', '/#/', '/#']) {
      await page.goto(`${baseURL}${entry}`);
      await page.getByRole('heading', { name: '다시 만나 반가워요' }).waitFor();
      assert.equal(await page.locator('.projects-grid').count(), 0, 'Root entry opens login');
    }
    await page.route('**/api/v1/projects?*', route => route.fulfill({ json: { items: [], totalPages: 1, totalElements: 0, page: 0, size: 100 } }));
    await page.goto(`${baseURL}/#/signup`);
    await page.getByLabel('이름', { exact: true }).fill('홍길동');
    await page.getByLabel('이메일', { exact: true }).fill('researcher@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('password123');
    await page.getByLabel('비밀번호 확인', { exact: true }).fill('different123');
    await page.getByRole('button', { name: '계정 만들기' }).click();
    await page.getByRole('alert').waitFor();
    assert.match(await page.getByRole('alert').innerText(), /일치하지/);
    assert.equal(calls.length, 0);
    await page.getByLabel('비밀번호 확인', { exact: true }).fill('password123');
    await page.getByRole('button', { name: '비밀번호 보기', exact: true }).click();
    assert.equal(await page.getByLabel('비밀번호', { exact: true }).getAttribute('type'), 'text');
    await page.screenshot({ path: join(tmpdir(), 'labflow-auth-signup.png'), fullPage: true });
    await page.getByRole('button', { name: '계정 만들기' }).click();
    await page.waitForURL('**/#/verify');
    assert.deepEqual(calls[0], { action: 'sign-up', body: { name: '홍길동', email: 'researcher@example.com', password: 'password123', confirmPassword: 'password123' }, csrf: undefined });
    assert.equal(await page.getByLabel('이메일', { exact: true }).inputValue(), 'researcher@example.com');
    assert.equal(await page.getByRole('button', { name: /초 후 재발송/ }).isDisabled(), true);
    await page.getByLabel('인증 코드', { exact: true }).fill('abc123');
    await page.getByRole('button', { name: '이메일 인증하기' }).click();
    await page.waitForURL('**/#/login');
    assert.equal(calls[1].csrf, 'test-csrf');
    assert.deepEqual(calls[1].body, { email: 'researcher@example.com', code: 'ABC123' });
    await page.getByRole('status').waitFor();
    assert.match(await page.getByRole('status').innerText(), /인증이 완료/);
    assert.equal(await page.getByLabel('비밀번호', { exact: true }).inputValue(), '');
    await page.getByLabel('비밀번호', { exact: true }).fill('password123');
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    await page.waitForURL('**/#/projects');
    assert.equal(calls[2].action, 'login');
    assert.equal(await page.title(), 'LabFlow Studio');
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('login handles server errors, pending requests and unverified accounts', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  let attempt = 0;
  try {
    await page.route('**/api/v1/auth/login', async route => {
      attempt++;
      await new Promise(resolve => setTimeout(resolve, 250));
      await route.fulfill({ status: 401, json: attempt === 1 ? { message: '이메일 또는 비밀번호를 확인해 주세요.' } : { code: 'EMAIL_VERIFICATION_REQUIRED' } });
    });
    await page.goto(`${baseURL}/#/login`);
    await page.getByLabel('이메일', { exact: true }).fill('pending@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('password123');
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '처리 중…' }).isDisabled(), true);
    await page.getByRole('alert').waitFor();
    assert.match(await page.getByRole('alert').innerText(), /비밀번호를 확인/);
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    await page.waitForURL('**/#/verify');
    assert.equal(await page.getByLabel('이메일', { exact: true }).inputValue(), 'pending@example.com');
    await page.route('**/api/v1/auth/csrf', route => route.fulfill({ status: 200, headers: { 'Set-Cookie': 'XSRF-TOKEN=test-csrf; Path=/' }, json: {} }));
    let resent = false;
    await page.route('**/api/v1/auth/email-verifications/resend', async route => {
      resent = true;
      assert.equal(route.request().headers()['x-xsrf-token'], 'test-csrf');
      await route.fulfill({ status: 204 });
    });
    await page.getByRole('button', { name: '인증 코드 다시 받기' }).click();
    await page.getByRole('status').waitFor();
    assert.ok(resent);
    assert.equal(await page.getByRole('button', { name: /초 후 재발송/ }).isDisabled(), true);
    await page.getByRole('link', { name: '로그인으로 돌아가기' }).click();
    await page.getByRole('heading', { name: '다시 만나 반가워요' }).waitFor();
    await page.getByRole('status').waitFor({ state: 'hidden' });
    await page.unroute('**/api/v1/auth/login');
    await page.route('**/api/v1/auth/login', route => route.abort('failed'));
    await page.getByLabel('비밀번호', { exact: true }).fill('password123');
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    await page.getByRole('alert').waitFor();
    assert.match(await page.getByRole('alert').innerText(), /서버에 연결하지/);
  } finally { await browser.close(); }
});

test('auth pages fit mobile and desktop in both themes', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  try {
    for (const width of [360, 390, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      for (const mode of ['login', 'signup', 'verify']) {
        await page.goto(`${baseURL}/#/${mode}`);
        await page.locator('.auth-main h1').waitFor();
        for (const theme of ['light', 'dark']) {
          await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
          const dimensions = await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth }));
          assert.ok(dimensions.scrollWidth <= dimensions.width, `${width}/${mode}/${theme} must not overflow`);
          assert.ok(await page.getByRole('button', { name: mode === 'signup' ? '계정 만들기' : mode === 'verify' ? '이메일 인증하기' : '로그인', exact: true }).isVisible());
          assert.equal(await page.locator('.auth-submit').evaluate(button => getComputedStyle(button).backgroundColor), 'rgb(23, 104, 95)', 'Submit action retains the Studio green in both themes');
          if (width === 1440 && mode === 'login') await page.screenshot({ path: join(tmpdir(), `labflow-auth-login-${theme}.png`), fullPage: true });
          if (width === 390 && mode === 'signup' && theme === 'light') await page.screenshot({ path: join(tmpdir(), 'labflow-auth-signup-mobile.png'), fullPage: true });
        }
      }
    }
  } finally { await browser.close(); }
});
