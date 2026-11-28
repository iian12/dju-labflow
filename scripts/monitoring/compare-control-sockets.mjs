import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import http from 'node:http';
import diagnostics from 'node:diagnostics_channel';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { runCommand } from './run-command.mjs';

// Bounded experiment: only the fixture account is changed; each refresh has one attempt.
const root = process.cwd();
const directory = (await readFile(path.join(root, '.external-access/monitoring/current-cycle.txt'), 'utf8')).trim();
assert.ok(path.resolve(directory).startsWith(path.join(root, '.external-access/load-tests') + path.sep));
const fixturePath = path.join(root, '.external-access/deployment-test');
const credentials = JSON.parse(await readFile(path.join(fixturePath, 'credentials.json'), 'utf8'));
assert.equal(credentials.id, '9876543210001');
assert.match(credentials.email, /^deployment-smoke-\d+@example\.invalid$/);
const gapMilliseconds = 7200;
const ssh = 'C:/Windows/System32/OpenSSH/ssh.exe';
const sshArgs = ['-p', '2222', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', 'ian@iian12.ddns.net'];
const dbCommand = 'mariadb --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian labflow --batch --skip-column-names';
const dbQueries = [
  `SELECT COUNT(*) FROM users WHERE id=${credentials.id};`,
  `SELECT COUNT(*) FROM project_entity WHERE created_by=${credentials.id};`,
  `SELECT COUNT(*) FROM refresh_tokens WHERE user_id=${credentials.id};`,
];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

const sql = async text => (await runCommand(ssh, [...sshArgs, dbCommand], { input: text })).trim();
const jar = new Map();
const cookies = () => [...jar].map(([name, value]) => `${name}=${value}`).join('; ');
function updateCookies(headers) {
  for (const cookie of headers.getSetCookie()) {
    const first = cookie.split(';', 1)[0], separator = first.indexOf('=');
    if (first.slice(separator + 1)) jar.set(first.slice(0, separator), first.slice(separator + 1));
    else jar.delete(first.slice(0, separator));
  }
}
async function pooled(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(10000) });
  updateCookies(response.headers);
  return { status: response.status, headers: response.headers, text: await response.text() };
}
function fresh(url, options = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request(url, { ...options, agent: false, timeout: 10000 }, response => {
      const headers = new Headers();
      for (const [name, value] of Object.entries(response.headers)) {
        for (const item of Array.isArray(value) ? value : [value]) if (item !== undefined) headers.append(name, item);
      }
      updateCookies(headers);
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('error', reject);
      response.on('end', () => resolve({ status: response.statusCode, headers, text: Buffer.concat(chunks).toString('utf8'), localPort: request.socket?.localPort }));
    });
    request.on('timeout', () => request.destroy(new Error('Fresh connection timed out')));
    request.on('error', reject);
    request.end(options.body);
  });
}
const original = 'http://127.0.0.1:15174', candidate = 'http://127.0.0.1:15175';
let needsLogin = true, currentTrial, requestPhase, logins = 0;
const socketEvents = [];
const channel = diagnostics.channel('undici:client:sendHeaders');
const listener = ({ request, socket }) => {
  if (currentTrial) socketEvents.push({ trial: currentTrial, phase: requestPhase, method: request.method, path: request.path.split('?', 1)[0], localPort: socket.localPort, remotePort: socket.remotePort });
};
channel.subscribe(listener);
async function login() {
  assert.ok(logins < 8, 'Bound account-login attempts');
  jar.clear(); logins++;
  assert.equal((await fresh(original + '/api/v1/auth/csrf')).status, 200);
  assert.equal((await fresh(original + '/api/v1/auth/login', {
    method: 'POST', headers: { Cookie: cookies(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: credentials.email, password: credentials.password }),
  })).status, 204);
  needsLogin = false;
}

const report = { startedAt: new Date().toISOString(), gapMilliseconds, repetitions: 3, rows: [], cleanup: {}, experimentValid: false };
const variants = {
  'sync-pooled': { base: original, asynchronous: false, newConnection: false },
  'async-pooled': { base: original, asynchronous: true, newConnection: false },
  'sync-fresh': { base: original, asynchronous: false, newConnection: true },
  'fixed-proxy-sync-pooled': { base: candidate, asynchronous: false, newConnection: false },
};
const orders = [
  ['sync-pooled', 'async-pooled', 'sync-fresh', 'fixed-proxy-sync-pooled'],
  ['fixed-proxy-sync-pooled', 'sync-fresh', 'async-pooled', 'sync-pooled'],
  ['async-pooled', 'sync-pooled', 'fixed-proxy-sync-pooled', 'sync-fresh'],
];
const tunnelLog = createWriteStream(path.join(directory, 'comparison-ssh.log'));
const tunnel = spawn(ssh, ['-N', '-T', '-p', '2222', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'ExitOnForwardFailure=yes',
  '-L', '127.0.0.1:15174:127.0.0.1:5174', '-L', '127.0.0.1:15175:127.0.0.1:15175', 'ian@iian12.ddns.net'], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
tunnel.stderr.pipe(tunnelLog);
try {
  let ready = false;
  for (let attempt = 0; attempt < 30; attempt++) {
    if (tunnel.exitCode !== null) throw new Error('Comparison tunnel exited');
    try { if ((await fresh(original + '/api/v1/auth/csrf')).status === 200 && (await fresh(candidate + '/api/v1/auth/csrf')).status === 200) { ready = true; break; } } catch {}
    await delay(300);
  }
  assert.ok(ready, 'Both loopback gateways must be ready');
  assert.equal(await sql(`SELECT COUNT(*) FROM users WHERE id=${credentials.id};`), '1');
  for (let repetition = 0; repetition < orders.length; repetition++) {
    for (const name of orders[repetition]) {
      const variant = variants[name];
      if (needsLogin) await login();
      currentTrial = `${repetition + 1}:${name}`; requestPhase = 'warm';
      assert.equal((await pooled(variant.base + '/api/v1/auth/csrf', { headers: { Cookie: cookies() } })).status, 200);
      let advertised;
      for (let i = 0; i < 4; i++) {
        const response = await pooled(variant.base + '/api/v1/auth/me', { headers: { Cookie: cookies() } });
        assert.equal(response.status, 200); advertised = response.headers.get('keep-alive');
      }
      const warmPorts = socketEvents.filter(event => event.trial === currentTrial && event.phase === 'warm').map(event => event.localPort);
      const idleStarted = performance.now();
      for (const query of dbQueries) {
        if (variant.asynchronous) await sql(query);
        else execFileSync(ssh, [...sshArgs, dbCommand], { input: query, encoding: 'utf8', timeout: 30000, windowsHide: true });
      }
      const remaining = Math.max(0, gapMilliseconds - (performance.now() - idleStarted));
      if (variant.asynchronous) await delay(remaining);
      else if (remaining > 0) execFileSync(process.execPath, ['-e', `Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,${Math.ceil(remaining)})`], { windowsHide: true });
      const row = { repetition: repetition + 1, variant: name, advertisedKeepAlive: advertised, idleMilliseconds: performance.now() - idleStarted, attempts: 1, warmPorts };
      requestPhase = 'refresh';
      const sentAt = performance.now();
      try {
        const response = await (variant.newConnection ? fresh : pooled)(variant.base + '/api/v1/auth/refresh', {
          method: 'POST', headers: { Cookie: cookies(), 'X-XSRF-TOKEN': decodeURIComponent(jar.get('XSRF-TOKEN') ?? '') },
        });
        row.status = response.status; row.success = response.status === 204;
        row.refreshPort = variant.newConnection ? response.localPort : socketEvents.filter(event => event.trial === currentTrial && event.phase === 'refresh').at(-1)?.localPort;
        if (!row.success) needsLogin = true;
      } catch (error) {
        row.success = false; row.error = { name: error.name, code: error.cause?.code ?? error.code, causeName: error.cause?.name };
        if (error.cause?.socket) row.socket = Object.fromEntries(['localPort', 'remotePort', 'bytesRead', 'bytesWritten'].filter(key => Number.isFinite(error.cause.socket[key])).map(key => [key, error.cause.socket[key]]));
        row.refreshPort = socketEvents.filter(event => event.trial === currentTrial && event.phase === 'refresh').at(-1)?.localPort;
        needsLogin = true; // A new session for the next trial, never retry this refresh.
      }
      row.requestMilliseconds = performance.now() - sentAt;
      row.reusedWarmSocket = row.refreshPort !== undefined ? warmPorts.includes(row.refreshPort) : null;
      report.rows.push(row);
      await writeFile(path.join(directory, 'socket-comparison.json'), JSON.stringify(report, null, 2));
      console.log(JSON.stringify(row));
      currentTrial = undefined;
      await delay(100);
    }
  }
  report.completed = true;
} catch (error) {
  report.error = { name: error.name, message: error.message.replaceAll(credentials.password, '[REDACTED]').replaceAll(credentials.email, '[FIXTURE]') };
  console.error(JSON.stringify(report.error));
} finally {
  channel.unsubscribe(listener);
  try {
    if (!needsLogin) report.cleanup.loggedOut = (await fresh(original + '/api/v1/auth/logout', { method: 'POST', headers: { Cookie: cookies(), 'X-XSRF-TOKEN': decodeURIComponent(jar.get('XSRF-TOKEN') ?? '') } })).status === 204;
    const guard = `u.id=${credentials.id} AND u.email='${credentials.email}'`;
    await sql(`START TRANSACTION; DELETE t FROM refresh_tokens t JOIN users u ON u.id=t.user_id WHERE ${guard}; DELETE v FROM email_verifications v JOIN users u ON u.id=v.user_id WHERE ${guard}; DELETE u FROM users u WHERE ${guard} AND NOT EXISTS(SELECT 1 FROM project_entity p WHERE p.created_by=u.id); COMMIT;`);
    report.cleanup.testUserDeleted = await sql(`SELECT COUNT(*) FROM users WHERE id=${credentials.id};`) === '0';
    if (report.cleanup.testUserDeleted) for (const name of ['credentials.json', 'seed.sql']) await rm(path.join(fixturePath, name), { force: true });
  } catch (error) { report.cleanup.error = { name: error.name, message: 'Fixture cleanup requires review' }; }
  if (tunnel.exitCode === null && tunnel.signalCode === null) { const closed = once(tunnel, 'close'); tunnel.kill(); await closed; }
  await new Promise(resolve => tunnelLog.end(resolve));
  report.finishedAt = new Date().toISOString(); report.logins = logins;
  report.summary = Object.fromEntries(Object.keys(variants).map(name => { const rows = report.rows.filter(row => row.variant === name); return [name, { attempts: rows.length, successes: rows.filter(row => row.success).length, errors: rows.filter(row => !row.success).map(row => row.error?.code ?? `HTTP_${row.status}`) }]; }));
  report.experimentValid = report.completed === true && report.cleanup.testUserDeleted && Object.values(report.summary).every(result=>result.attempts===3) && report.rows.every(row=>row.attempts===1&&(row.status===204||['UND_ERR_SOCKET','ECONNRESET'].includes(row.error?.code)));
  report.headerOnlyFixSucceeded = report.summary['fixed-proxy-sync-pooled'].successes===3;
  await writeFile(path.join(directory, 'socket-events.json'), JSON.stringify(socketEvents, null, 2));
  await writeFile(path.join(directory, 'socket-comparison.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ comparisonCompleted: report.completed, experimentValid: report.experimentValid, headerOnlyFixSucceeded: report.headerOnlyFixSucceeded, summary: report.summary, cleanup: report.cleanup, report: path.join(directory, 'socket-comparison.json') }, null, 2));
  process.exitCode = report.experimentValid ? 0 : 1;
}
