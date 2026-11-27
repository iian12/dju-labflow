import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { observeHttp } from './observe-http.mjs';

const consume = async response => ({ status: response.status, text: await response.text() });
const reset = () => new TypeError('fetch failed Authorization: secret', { cause: Object.assign(new Error('cookie=secret'), { code: 'ECONNRESET' }) });

test('records the operation and nested code without credentials, query or raw error text', async () => {
  const records = [];
  await assert.rejects(observeHttp({
    url: 'http://user:secret@localhost/api/v1/auth/refresh?token=secret',
    options: { method: 'POST', headers: { Authorization: 'Bearer secret' }, body: 'password=secret' },
    phase: 'post-load-refresh', operation: 'control', consume,
    fetchImpl: async () => { throw reset(); }, onFailure: details => records.push(details),
  }), error => {
    assert.match(error.message, /post-load-refresh\/control\/request; ECONNRESET/);
    assert.equal(error.details.path, '/api/v1/auth/refresh');
    assert.equal(error.details.origin, 'http://localhost');
    assert.equal(error.details.error.cause.code, 'ECONNRESET');
    assert.equal(JSON.stringify(error.details).includes('secret'), false);
    assert.equal(error.stack.includes('secret'), false);
    return true;
  });
  assert.equal(records.length, 1);
});

test('an actual server reset is recorded and a POST is attempted only once', async t => {
  let calls = 0;
  const server = createServer(request => { calls++; request.socket.destroy(); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  await assert.rejects(observeHttp({
    url: `http://127.0.0.1:${server.address().port}/refresh`,
    options: { method: 'POST', body: 'fixture', signal: AbortSignal.timeout(5000) },
    phase: 'post-load-refresh', operation: 'control', consume,
  }), error => {
    assert.equal(error.details.stage, 'request');
    assert.equal(error.details.error.cause.code, 'UND_ERR_SOCKET');
    return true;
  });
  assert.equal(calls, 1);
});

test('a truncated response identifies the body stage and the HTTP status already received', async t => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Length': '100', 'Content-Type': 'application/json' });
    response.flushHeaders(); response.write('{');
    setTimeout(() => response.destroy(), 50);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  await assert.rejects(observeHttp({
    url: `http://127.0.0.1:${server.address().port}/api/v1/query`,
    options: { signal: AbortSignal.timeout(5000) },
    phase: 'post-load-metrics', operation: 'metric:cpu', consume,
  }), error => {
    assert.equal(error.details.stage, 'response-body');
    assert.equal(error.details.status, 200);
    assert.equal(error.details.error.cause.code, 'UND_ERR_SOCKET');
    return true;
  });
});

test('a diagnostic file error does not hide the original network failure', async () => {
  await assert.rejects(observeHttp({
    url: 'http://localhost/api/v1/query', phase: 'post-load-metrics', operation: 'metric:cpu', consume,
    fetchImpl: async () => { throw reset(); }, onFailure: async () => { throw new Error('disk full'); },
  }), error => {
    assert.equal(error.details.diagnosticWriteFailed, true);
    assert.equal(error.details.error.cause.code, 'ECONNRESET');
    return true;
  });
});

test('successful responses are consumed unchanged without a failure record', async () => {
  let recorded = false;
  const result = await observeHttp({
    url: 'http://localhost/api/v1/auth/me', phase: 'post-load-me', operation: 'control', consume,
    fetchImpl: async () => new Response('{"ok":true}', { status: 200 }),
    onFailure: () => { recorded = true; },
  });
  assert.deepEqual(result, { status: 200, text: '{"ok":true}' });
  assert.equal(recorded, false);
});
