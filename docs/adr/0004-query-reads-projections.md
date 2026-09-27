# 查询读投影；券不是 JPA 表

列表和「我的券」读 Redis 读模型。搜索只读 Elasticsearch。不要在 Redis 上再做一套 `searchCoupons`，也不要提供未接入 Gateway 的 `GET /sync/{id}` 增量接口。

`CouponEntity` 是抢券事件正文和读模型对象，不是 PostgreSQL 券表。不要为它保留 Spring Data 仓库。Redis 只按 `seckill:coupon:{promotionId}:{customerId}` 和顾客集合保存券，不再维护仅给 `/sync` 用的 `seckill:coupons_by_id`。
