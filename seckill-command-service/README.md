## Architecture of Command Micro-Service

* `SecKillCommandRestController`  
  Accepts `POST /command/coupons/` (`promotionId`, `customerId`).
* `SecKillPromotionBootstrap`  
  Loads unpublished promotions from PostgreSQL and, when `publishTime` is reached, initializes Redis stock and writes `PromotionStartEvent` plus an outbox row.
* Active promotion path  
  1. `SecKillCommandService`: Redis Lua `tryGrab` (stock + claimed set).  
  2. On success: same transaction writes the event and outbox to PostgreSQL; persist failure compensates Redis.  
  3. `OutboxRelay`: publishes committed outbox rows to Kafka `seckill.events` (key = `promotionId`).
* Duplicate / sold out return `429` (`duplicate order` / `out of stock`). Unique `(promotionId, customerId)` on the event store is a second line of defense.

The in-memory blocking queue and ActiveMQ publisher from the original demo are gone. See the root [README](../README.md) for the full CQRS diagram.

![Event sourcing overview](https://github.com/ServiceComb/seckill/blob/master/etc/EventSourcing.png)
