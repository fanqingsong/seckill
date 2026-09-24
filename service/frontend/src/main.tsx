/*
 * ┌─ 文件 ─────────────────────────┐
 * │ main.tsx                       │
 * │ 浏览器 → nginx :8080 的页面    │
 * └───────────────────────────────┘
 *
 * index.html 里 id 为 root 的节点
 * │
 * ▼
 * 【本文件】把 App 挂进去
 * │
 * └──▶ 页面上的 /admin、/command、/query 由 App 发出
 *
 * 一句话：这里不发 HTTP，也不打开 PostgreSQL。
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
