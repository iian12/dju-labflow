import http from 'node:http'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { dirname, extname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { isIP } from 'node:net'

// Serve compiled assets only; development sources and environment files stay private.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../frontend/dist')
const upstream = new URL(process.env.LABFLOW_BACKEND_URL ?? 'http://127.0.0.1:8080')
if (upstream.protocol !== 'http:') throw new Error('The local backend must use HTTP')
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' }

const server = http.createServer(async (request, response) => {
  response.setHeader('X-Content-Type-Options', 'nosniff')
  response.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
  try {
    if (!request.url.startsWith('/') || request.url.startsWith('//') || request.url.includes('\\')) {
      response.writeHead(400).end(); return
    }
    const url = new URL(request.url, 'http://localhost')
    const path = url.pathname
    if (path.startsWith('/api/v1/')) {
      // Validate browser origins before removing Origin for the same-origin proxy.
      if (request.headers.origin) {
        let origin
        try { origin = new URL(request.headers.origin) } catch { response.writeHead(403).end(); return }
        if (origin.host !== request.headers.host || !['http:', 'https:'].includes(origin.protocol)) {
          response.writeHead(403).end(); return
        }
      }
      const headers = { ...request.headers, host: upstream.host }
      delete headers.origin
      delete headers['forwarded']
      delete headers['x-forwarded-for']
      delete headers['x-forwarded-host']
      delete headers['x-forwarded-proto']
      // Enable only when ingress is exclusively Cloudflare/cloudflared, which replaces this header.
      const cloudflare = process.env.LABFLOW_TRUST_CLOUDFLARE_HEADERS === 'true' ? request.headers['cf-connecting-ip'] : undefined
      headers['x-forwarded-for'] = typeof cloudflare === 'string' && isIP(cloudflare) ? cloudflare : request.socket.remoteAddress
      delete headers['cf-connecting-ip']
      delete headers['x-real-ip']
      headers['x-forwarded-proto'] = 'https'
      headers['x-forwarded-host'] = request.headers.host
      const proxy = http.request(new URL(url.pathname + url.search, upstream), { method: request.method, headers, timeout: 30000 }, result => {
        response.writeHead(result.statusCode, { ...result.headers, 'cache-control': 'no-store' })
        result.pipe(response)
      })
      proxy.on('timeout', () => proxy.destroy())
      proxy.on('error', () => {
        if (!response.headersSent) response.writeHead(502, { 'content-type': 'application/json' })
        response.end('{"code":"BACKEND_UNAVAILABLE","message":"Backend unavailable"}')
      })
      request.on('aborted', () => proxy.destroy())
      request.pipe(proxy)
      return
    }
    if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405).end(); return }
    const decoded = decodeURIComponent(path)
    const file = resolve(root, '.' + (decoded === '/' ? '/index.html' : decoded))
    if (!file.startsWith(root + sep) || decoded.split('/').some(part => part.startsWith('.'))) {
      response.writeHead(404).end(); return
    }
    const info = await stat(file)
    if (!info.isFile()) { response.writeHead(404).end(); return }
    response.writeHead(200, {
      'content-type': types[extname(file)] ?? 'application/octet-stream',
      'content-length': info.size,
      'cache-control': extname(file) === '.html' ? 'no-cache' : 'public, max-age=3600',
    })
    if (request.method === 'HEAD') response.end()
    else createReadStream(file).on('error', () => response.destroy()).pipe(response)
  } catch {
    if (!response.headersSent) response.writeHead(404)
    response.end()
  }
})
server.listen(Number(process.env.LABFLOW_PUBLIC_PORT ?? 5174), '127.0.0.1', () => {
  console.log(`LabFlow external gateway: http://127.0.0.1:${server.address().port}`)
})
