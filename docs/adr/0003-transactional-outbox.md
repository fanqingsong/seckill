# Kafka 只来自已提交的 outbox

发到 `seckill.events` 的消息必须先作为 outbox 行，和事件插入处在同一个数据库事务里。Relay 在提交之后再发。不要先发 Kafka 再提交数据库。

开始/结束事件走 Command 的 `TransactionalEventOutboxWriter`；抢券事件走 Persist 的 `PersistOutboxWriter`。两处都遵守同一条规则，不共用一个从未注入的抽象接口。
