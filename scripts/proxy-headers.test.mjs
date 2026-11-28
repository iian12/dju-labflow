import assert from 'node:assert/strict';
import { test } from 'node:test';
import { withoutHopByHop } from './proxy-headers.mjs';

test('removes connection-specific headers and mixed-case connection options', () => {
  const original = {
    Connection: ['Keep-Alive, X-Temporary', 'UPGRADE'],
    'Keep-Alive': 'timeout=60', 'X-TEMPORARY': 'private-hop',
    Upgrade: 'websocket', 'Transfer-Encoding': 'chunked', TE: 'trailers',
    Trailer: 'X-End', 'Proxy-Connection': 'keep-alive',
    'Proxy-Authorization': 'proxy-only', 'Proxy-Authenticate': 'Basic',
    'Content-Type': 'application/json', 'Content-Length': '2',
  };
  assert.deepEqual(withoutHopByHop(original), { 'Content-Type': 'application/json', 'Content-Length': '2' });
  assert.equal(original['Keep-Alive'], 'timeout=60');
});

test('preserves application authentication and multiple response cookies', () => {
  const original = { authorization: 'Bearer fixture', cookie: 'session=fixture', 'set-cookie': ['a=1', 'b=2'], location: '/run/1' };
  assert.deepEqual(withoutHopByHop(original), original);
});
