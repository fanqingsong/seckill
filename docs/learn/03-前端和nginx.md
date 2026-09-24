# 第 3 课：前端和 nginx

本课看浏览器发出的地址，以及这些地址为什么都打到 8080。

## 要打开的文件

- `service/frontend/src/api.ts`
- `service/frontend/nginx.conf`

页面组件在 `service/frontend/src/App.tsx`。本课只要知道：按钮最终调用 `api.ts` 里的函数。

## 页面只认识四条相对路径

`api.ts` 里的请求都没有写主机名和端口，路径以 `/` 开头。浏览器会把它们发给「当前页面所在的网站」，也就是 `http://localhost:8080`。

| 函数 | 请求 | 谁最终处理 |
|------|------|------------|
| `createPromotion` | `POST /admin/promotions/` | Admin |
| `grabCoupon` | `POST /command/coupons/` | Command |
| `listPromotions` | `GET /query/promotions` | Query |
| `listCoupons` | `GET /query/coupons/{customerId}` | Query |

回放接口 `POST /admin/replay` 不在这个文件里。它直接打 Event 服务的 8084，不经过 nginx，也不经过 Gateway。

## nginx 做的两件事

打开 `nginx.conf`：

1. `location /` 用 `try_files` 找静态文件。找不到就返回 `index.html`，这样前端自己的页面路由才能工作。React 打包后的文件放在镜像里的 `/usr/share/nginx/html`。
2. `location /admin/`、`/command/`、`/query/` 把请求原样转到 `gateway-service:8085`。

转发时加上两个头：

- `X-Real-IP`
- `X-Forwarded-For`

Gateway 用它们识别浏览器的 IP。如果没有这两个头，Gateway 看到的会是 nginx 容器的地址，限流就会按「整个前端」算，而不是按每个用户算。

`resolver 127.0.0.11` 是 Docker 内置 DNS。`proxy_pass` 使用变量，是为了容器重建、IP 变化后重新解析 `gateway-service`，避免一直连旧地址。

Kubernetes + Istio 路径里，入口由 Istio Gateway 按路径转发到 Admin / Command / Query，前端 nginx 只提供静态页（`nginx-k8s.conf`）。见 [`k8s/README.md`](../../k8s/README.md)。

## 核对

1. 为什么前端代码里写 `/command/coupons/`，而 Command 进程实际监听的是 8082？
2. nginx 有没有把请求直接转到 Admin？
3. 回放为什么不能在页面里沿用同样的 `/admin/...` 代理？

下一课：[04-gateway.md](04-gateway.md)
