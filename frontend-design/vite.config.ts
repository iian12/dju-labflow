import { defineConfig } from 'vite'
import type { ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

// Local browser requests remain same-origin so HttpOnly/CSRF cookies work together.
const backend = process.env.LABFLOW_BACKEND_URL ?? 'http://127.0.0.1:8080'
const proxy: ProxyOptions = {
  target: backend,
  changeOrigin: true,
  cookieDomainRewrite: '',
  configure(server) {
    // The browser talks to this local server. Do not forward its development origin
    // as a cross-origin browser request to the upstream server; CSRF still applies.
    server.on('proxyReq', request => request.removeHeader('origin'))
  },
}

export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': proxy, '/v3/api-docs': proxy, '/swagger-ui': proxy } },
})
