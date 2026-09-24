# Istio 入口（替代 seckill-gateway）

Kubernetes + Istio 承担原来 `seckill-gateway` 的路径转发、按 IP 限流、超时和熔断。`docker compose` 路径不变，仍使用 Spring Cloud Gateway。

`seckill-gateway` 和 Event 回放都不挂到 Ingress。`POST /admin/replay` 只打 `event-service:8084`。

## 对照

| 原 Gateway | Istio |
|---|---|
| `/admin/promotions` → Admin:8081 | VirtualService 路由 `admin`，超时 2s |
| `/command/` → Command:8082 | 路由 `command`，超时 800ms，禁止重试 |
| `/query/` → Query:8083 | 路由 `query`，超时 2s |
| Resilience4j 熔断 | DestinationRule `outlierDetection`（连续 10 次 5xx，驱逐 5s） |
| 熔断 503 正文 | Envoy `local_reply`：`command/query/admin unavailable` |
| Redis 令牌桶（按 IP） | `envoyproxy/ratelimit` + 独立 Redis，descriptor = `PATH` + `remote_address` |
| 配额 429 的 `X-RateLimit-*` | Envoy `DRAFT_VERSION_03` 头（名字与 Spring 不完全相同） |

Ingress 多副本时，全局限流服务仍共享配额。`requests_per_unit` 对齐补充速率（command 50/s、query 100/s、admin 10/s），没有单独的 burst 字段。

前端 nginx 在集群里只提供静态页（`service/frontend/nginx-k8s.conf`，kustomize 使用 `k8s/apps/nginx-k8s.conf`）。API 不再 `proxy_pass` 到 Gateway。

## 前置

1. 集群（kind / k3d / 其它）已安装 Istio，且 `istio-system` 里有 `istio-ingressgateway`。
2. 本地已构建业务镜像（与 Compose 同名）。

```bash
istioctl install --set profile=demo -y
./k8s/load-images.sh          # 需要 kind；其它发行版把镜像导入节点即可
./k8s/deploy.sh
kubectl -n seckill get pods
kubectl -n istio-system port-forward svc/istio-ingressgateway 8080:80
```

浏览器打开 http://localhost:8080，验收步骤与仓库根 README 的 curl 相同。

## 清单

```
k8s/
├── namespace.yaml
├── infra/           # Postgres / Redis / Kafka / ES，关闭 sidecar
├── apps/            # Admin / Command / Persist / Query / Event / Frontend
├── istio/           # Gateway、VirtualService、DestinationRule、限流、503 正文
├── kustomization.yaml
├── deploy.sh
└── load-images.sh
```

业务 Pod 用 `JAVA_OPTS` 覆盖 `prd` 里的 `*.servicecomb.io` 主机名为集群 Service（`postgres`、`redis`、`kafka`、`elasticsearch`）。

Java 服务的 sidecar 排除了出站 `5432,6379,9092,9200`。Istio 会按 6379 把流量当成 Redis 协议，Envoy 的 Redis 过滤器不支持抢券用的 `EVAL` / Stream，会返回 `WRONGTYPE`。
