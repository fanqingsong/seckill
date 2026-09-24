# 第 4 课：Gateway

本课把限流、熔断从配置文件跟到返回给浏览器的那一行字。

## 要打开的文件

按这个顺序：

1. `service/seckill-gateway/src/main/resources/application.properties`
2. `service/seckill-gateway/src/main/java/io/servicecomb/poc/demo/seckill/gateway/GatewayConfiguration.java`
3. `service/seckill-gateway/src/main/java/io/servicecomb/poc/demo/seckill/web/GatewayFallbackController.java`
4. `service/seckill-gateway/src/main/resources/application-prd.properties`（只看它多改了什么）

`SecKillGatewayProperties.java` 第 2 课已经看过，本课把它当作「配置的 Java 形态」。

## 一条路由在说什么

`GatewayConfiguration.seckillRoutes` 登记了三条路由，结构相同。以 command 为例，读的时候按这四句翻译：

1. 路径匹配 `/command/**`。
2. 先按 IP 做令牌桶限流。速率来自 `seckill.gateway.command.replenish-rate` 和 `burst-capacity`。
3. 再套名为 `commandCb` 的熔断器。名字必须和 `application.properties` 里 `resilience4j.*.instances.commandCb` 一致，Spring 才会用那一组数字。
4. 通过之后，转发到 `seckill.gateway.command-uri`。

Admin、Query 只是路径、熔断器名字和目标地址不同。

`ipKeyResolver` 决定限流的「谁」：先取 `X-Forwarded-For` 里逗号前的第一段，再取 `X-Real-IP`，最后才用 TCP 连接上的地址。这就是 nginx 必须传那两个头的原因。

## 哪些情况算失败

熔断器不会把所有非 200 都当成故障。`DOWNSTREAM_FAILURE_STATUSES` 只有 `500`、`502`、`503`、`504`。超时也算失败，超时时间是：

| 熔断器 | 配置项 | 当前值 |
|--------|--------|--------|
| `adminCb` | `resilience4j.timelimiter.instances.adminCb.timeoutDuration` | 2s |
| `commandCb` | 同上，换成 `commandCb` | 800ms |
| `queryCb` | 同上，换成 `queryCb` | 2s |

Command 抢光或重复下单时返回的 **429** 不在这个集合里。Gateway 会把 429 原样交给浏览器，熔断器保持关闭。

熔断打开，或下游就是 5xx 时，路由里的 `fallbackUri` 把请求转到本进程的 `/fallback/command`。`GatewayFallbackController` 固定返回 **503**，正文 `command unavailable`。Admin、Query 的正文分别是 `admin unavailable`、`query unavailable`。

## 两套限流后端

`seckill.gateway.rate-limiter`：

- `memory`：默认，也是测试用的，计数在进程内存里。
- `redis`：`application-prd.properties` 里的值，计数在 Redis。速率数字仍来自 `application.properties`，prd 文件没有覆盖它们。

对应的装配在 `GatewayRateLimiterConfiguration.java`。第一遍只要知道「配置值决定用哪一个实现」。

## 浏览器看到的两种 429

| 来源 | 正文 | 响应头 |
|------|------|--------|
| Gateway 令牌桶满了 | 空 | 有 `X-RateLimit-*` |
| Command 判定售罄或重复 | 纯文本原因 | 没有这组限流头 |

## 核对

1. 把 `command.replenish-rate` 改成 1 之后，要重启的是哪个进程？
2. Command 返回 429 时，浏览器会不会收到 `command unavailable`？
3. `commandCb` 这个字符串在 Java 和 properties 里各出现在什么位置？

Kubernetes 上可以用 Istio 接过本课的转发、限流和熔断，见 [`k8s/README.md`](../../k8s/README.md)。Compose 仍走本进程。

下一课：[05-admin.md](05-admin.md)
