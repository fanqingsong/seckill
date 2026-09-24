# SecKill 学习资料

这份资料给 Java 初学者用。一次只学一课，每课大约 30–60 分钟。目标是看懂这个秒杀项目在干什么，而不是先学完 Java 语法。

## 怎么学

1. 按编号顺序学。后面的课会用到前面的结论。
2. 每课只打开「要打开的文件」。版权声明、`import`、成排的 `get`/`set` 先跳过。
3. 学完一课，用文末的核对题自己讲一遍。讲得出来再进入下一课。
4. 项目总览仍以仓库根目录的 [README.md](../../README.md) 为准。这里是阅读顺序，不是另一套设计。

## 课程

| 课 | 文件 | 你会搞懂 |
|----|------|----------|
| 1 | [01-系统全貌.md](01-系统全貌.md) | 浏览器点一次，数据最终落在哪里 |
| 2 | [02-怎么读Java.md](02-怎么读Java.md) | 这个仓库里反复出现的 Java / Spring 写法 |
| 3 | [03-前端和nginx.md](03-前端和nginx.md) | 页面怎么发出请求，nginx 为什么挡在前面 |
| 4 | [04-gateway.md](04-gateway.md) | 限流和熔断的配置怎样接到代码上 |
| 5 | [05-admin.md](05-admin.md) | 创建活动时，谁把数据写入 PostgreSQL |
| 6 | [06-抢券热路径.md](06-抢券热路径.md) | 热路径的代码位置，以及 Redis 键的类型 |
| 7 | [07-落库和消息.md](07-落库和消息.md) | 工人线程、同一事务里的事件和 outbox、Kafka |
| 8 | [08-查询和回放.md](08-查询和回放.md) | 三类事件在 Redis / Elasticsearch 里的格式，以及怎样对齐 |

## 读代码时始终抓住的一条链

```text
浏览器
  → 前端 nginx :8080
  → Gateway :8085
  → Admin :8081  写活动到 PostgreSQL
  → Command :8082  Redis Lua 抢库存，后台再写入 PostgreSQL，经 Kafka 发出
  → Event :8084  消费 Kafka，更新 Redis 读模型和 Elasticsearch
  → Query :8083  从 Redis / Elasticsearch 读
```

创建活动、抢券、查结果是三条不同的路。抢券成功的 HTTP 响应，只表示 Redis 已经扣减库存，PostgreSQL 和查询结果会晚一点跟上。
