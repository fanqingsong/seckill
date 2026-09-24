/*
 * ┌─ 文件 ────────────────────────────────────┐
 * │ vite.config.ts                            │
 * │ 开发走 Vite :5173，打包后走 nginx :8080   │
 * └──────────────────────────────────────────┘
 *
 * 浏览器
 * │
 * ▼
 * 【本文件】开发时把三条路径代理到本机服务
 * │
 * ├── /admin ──▶ 8081
 * ├── /command ──▶ 8082
 * └── /query ──▶ 8083
 *
 * 一句话：开发时不经过 nginx，页面仍不打开 PostgreSQL。
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
