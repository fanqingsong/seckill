# 抢券热路径只走 Redis Lua，Command 不写券事件

`POST /command/coupons/` 只调用一次 Lua：判断、扣减、写入 `seckill:grabs`。HTTP 200 只表示 Redis 已扣。券事件由 Persist 消费该流后，与 outbox 写在同一事务里。

Command 里不再保留「同步 persistGrab」或未调用的库存补偿。需要回滚时改 Persist / 恢复逻辑，不要在热路径上加第二条写库口。
