import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

for (const trustCloudflare of [false, true]) {
  test(`gateway replaces spoofed forwarding headers (Cloudflare trust: ${trustCloudflare})`, async () => {
    const backend = http.createServer((request, response) => {
      response.setHeader('Content-Type', 'application/json')
      response.end(JSON.stringify(request.headers))
    })
    backend.listen(0, '127.0.0.1')
    await once(backend, 'listening')
    const child = spawn(process.execPath, [fileURLToPath(new URL('./serve-external.mjs', import.meta.url))], {
      windowsHide: true,
      env: { ...process.env, LABFLOW_PUBLIC_PORT: '0', LABFLOW_BACKEND_URL: `http://127.0.0.1:${backend.address().port}`, LABFLOW_TRUST_CLOUDFLARE_HEADERS: String(trustCloudflare) },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    try {
      const base = await new Promise((resolve, reject) => {
        let output = ''
        const timeout = setTimeout(() => reject(new Error('Gateway startup timed out')), 10000)
        child.once('error', error => { clearTimeout(timeout); reject(error) })
        child.once('exit', code => { clearTimeout(timeout); reject(new Error(`Gateway exited: ${code}`)) })
        child.stdout.on('data', data => {
          output += data
          const address = output.match(/http:\/\/127\.0\.0\.1:\d+/)?.[0]
          if (address) { clearTimeout(timeout); resolve(address) }
        })
      })
      for (const cloudflare of ['203.0.113.42', 'not-an-ip']) {
        const response = await fetch(`${base}/api/v1/auth/me`, {
          headers: { 'X-Forwarded-For': '1.2.3.4, 5.6.7.8', 'Forwarded': 'for=1.2.3.4', 'X-Real-IP': '1.2.3.4', 'CF-Connecting-IP': cloudflare },
        })
        assert.equal(response.status, 200)
        const headers = await response.json()
        assert.equal(headers['x-forwarded-for'], trustCloudflare && cloudflare === '203.0.113.42' ? cloudflare : '127.0.0.1')
        for (const removed of ['forwarded', 'x-real-ip', 'cf-connecting-ip']) assert.equal(headers[removed], undefined)
      }
    } finally {
      child.kill()
      if (child.exitCode === null) await once(child, 'exit')
      backend.closeAllConnections()
      await new Promise(resolve => backend.close(resolve))
    }
  })
}
