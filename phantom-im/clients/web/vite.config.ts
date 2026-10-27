import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      watch: {
        ignored: ['**/test-results/**', '**/playwright-report/**'],
      },
      proxy: {
        '/api': {
          target: env.EASY_IM_BFF_URL || 'http://127.0.0.1:28090',
          changeOrigin: false,
        },
      },
    },
  }
})
