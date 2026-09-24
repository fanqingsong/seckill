/**
 * 页面入口。浏览器打开的是前端页面（正式环境由 nginx 监听 8080），
 * 本文件把 App 挂到 index.html 里 id 为 root 的节点上。
 * StrictMode 是 React 的开发期检查，不发 HTTP，也不连接 PostgreSQL。
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
