import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import http from 'node:http';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

test('gateway regenerates its own connection lifetime and preserves auth, cookies and binary bytes', async () => {
  const payload = Buffer.from([0, 1, 2, 255, 128, 0, 3]);
  let received;
  const backend = http.createServer((request, response) => {
    received = request.headers;
    response.writeHead(200, {
      Connection: 'keep-alive, X-Upstream-Hop', 'Keep-Alive': 'timeout=60',
      'X-Upstream-Hop': 'must-not-pass', 'Content-Type': 'application/octet-stream',
      'Content-Length': String(payload.length), 'Set-Cookie': ['first=1; HttpOnly', 'second=2; Secure'],
      Location: '/api/v1/run/1',
    });
    response.end(payload);
  });
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const child = spawn(process.execPath, [fileURLToPath(new URL('./serve-external.mjs', import.meta.url))], {
    windowsHide: true,
    env: { ...process.env, LABFLOW_PUBLIC_PORT: '0', LABFLOW_BACKEND_URL: `http://127.0.0.1:${backend.address().port}` },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  try {
    const base = await new Promise((resolve, reject) => {
      let output = '';
      const timer = setTimeout(() => reject(new Error('Gateway startup timed out')), 10000);
      child.once('error', error => { clearTimeout(timer); reject(error); });
      child.once('exit', code => { clearTimeout(timer); reject(new Error(`Gateway exited ${code}`)); });
      child.stdout.on('data', chunk => {
        output += chunk;
        const address = output.match(/http:\/\/127\.0\.0\.1:\d+/)?.[0];
        if (address) { clearTimeout(timer); resolve(address); }
      });
    });
    const response = await new Promise((resolve, reject) => {
      const request = http.get(base + '/api/v1/artifact', { headers: {
        Connection: 'keep-alive, X-Client-Hop', 'X-Client-Hop': 'must-not-pass',
        Authorization: 'Bearer fixture', Cookie: 'fixture=1',
      } }, incoming => {
        const chunks = [], headers = new Headers();
        for (const [name, value] of Object.entries(incoming.headers)) {
          for (const item of Array.isArray(value) ? value : [value]) if (item !== undefined) headers.append(name, item);
        }
        incoming.on('data', chunk => chunks.push(chunk));
        incoming.on('error', reject);
        incoming.on('end', () => resolve({ status: incoming.statusCode, headers, body: Buffer.concat(chunks) }));
      });
      request.on('error', reject);
    });
    assert.equal(response.status, 200);
    assert.deepEqual(response.body, payload);
    assert.equal(received['x-client-hop'], undefined);
    assert.equal(received.authorization, 'Bearer fixture');
    assert.equal(received.cookie, 'fixture=1');
    assert.equal(response.headers.get('x-upstream-hop'), null);
    assert.equal(response.headers.get('keep-alive'), 'timeout=5');
    assert.deepEqual(response.headers.getSetCookie(), ['first=1; HttpOnly', 'second=2; Secure']);
    assert.equal(response.headers.get('location'), '/api/v1/run/1');
    assert.equal(response.headers.get('content-length'), String(payload.length));
  } finally {
    child.kill();
    if (child.exitCode === null) await once(child, 'exit');
    backend.closeAllConnections();
    await new Promise(resolve => backend.close(resolve));
  }
});
