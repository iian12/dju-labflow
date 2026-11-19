import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.LABFLOW_BACKEND_URL ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
        cookieDomainRewrite: '',
        configure(proxy) {
          // Match frontend-design's same-origin local cookie authentication setup.
          proxy.on('proxyReq', request => request.removeHeader('origin'));
        },
      },
    },
  },
})
