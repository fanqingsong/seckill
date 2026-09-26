## Architecture of Command Micro-Service

Folder: [`service/seckill-command-service/`](./). Shared Lua/outbox types live in [`library/seckill-infra-redis/`](../../library/seckill-infra-redis/) and [`library/seckill-event-store/`](../../library/seckill-event-store/). Full map: [root README — Where to review](../../README.md#where-to-review-the-code).

HTTP is Spring MVC (Spring Boot 3). Sold out / duplicate grab returns **HTTP 429** with a plain-text body.

* `SecKillCommandRestController`  
  Accepts `POST /command/coupons/` (`promotionId`, `customerId`). `200` means Redis has claimed the coupon, not that PostgreSQL has the event yet.
* `SecKillPromotionBootstrap`  
  Loads unpublished promotions from PostgreSQL and, when `publishTime` is reached, initializes Redis stock (skipped if the stock key already exists) and writes `PromotionStartEvent` plus an outbox row. Time-based finish waits until the grab queue is empty.
* Active promotion path  
  1. `SecKillCommandService`: Redis Lua `tryGrab` (stock + claimed set + `XADD` grab token). HTTP returns immediately.  
  2. [`seckill-persist-service`](../seckill-persist-service/): `XREADGROUP` on stream `seckill:grabs` (group `persist`), same transaction writes the event and outbox; unique `(promotionId, customerId)` is treated as success `XACK`. Last unit of stock then writes `PromotionFinishEvent`.  
  3. [`seckill-outbox-relay-service`](../seckill-outbox-relay-service/): PostgreSQL CDC notify plus poll publishes committed outbox rows to Kafka `seckill.events` (key = `promotionId`). Kafka in Compose is **KRaft** (no ZooKeeper).
* Duplicate / sold out return `429` (`duplicate order` / `out of stock`). Unique `(promotionId, customerId)` on the event store is a second line of defense. Browser traffic goes through Gateway first: quota 429 has `X-RateLimit-*` headers; sold-out 429 has a plain-text body and does not trip the circuit breaker.

The grab queue is a Redis Stream (Jedis 5) with consumer group `persist`. Query sync is unchanged (outbox → Kafka → Event Service). Unpersisted tokens stay pending until `XACK` and need Redis durability; an empty Redis is rebuilt from the event table.

See the root [README](../README.md) for the full CQRS diagram.

![Event sourcing overview](https://github.com/ServiceComb/seckill/blob/master/etc/EventSourcing.png)
