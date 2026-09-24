/**
 * 本地 `vite` 开发服务器的配置，监听 5173。
 * proxy 把浏览器里的 /admin、/command、/query 分别转到本机 8081、8082、8083，
 * 这样开发时不必先经过 nginx。打包后的页面由 nginx 在 8080 提供，浏览器不连接 PostgreSQL。
 */
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
