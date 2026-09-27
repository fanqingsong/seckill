# SecKill 领域用语

| 用语 | 含义 |
|------|------|
| 活动 (Promotion) | Admin 写入 PostgreSQL 的一场秒杀。到 `publishTime` 才在 Redis 初始化库存。 |
| 券 (Coupon) | 某人在一场活动里抢到的一张折扣。事实在 `CouponGrabbedEvent` 正文里，查询看 Redis / Elasticsearch 投影。 |
| 抢券热路径 | Command 上的 HTTP 抢券：只执行 Redis Lua，不写 PostgreSQL。 |
| 事件 (Event) | 追加记录：活动开始、某人抢到、活动结束。权威账本在 PostgreSQL。 |
| outbox | 与事件同行写入的待发消息。提交后由 Relay 发到 Kafka。 |
| 读模型 | Event 服务投影出的查询副本：Redis 列表/我的券，Elasticsearch 搜索。 |
