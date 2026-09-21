import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://127.0.0.1:8081',
      '/command': 'http://127.0.0.1:8082',
      '/query': 'http://127.0.0.1:8083',
    },
  },
})
