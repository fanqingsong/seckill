# 只保留三条链路上的代码

仓库里每一段业务代码都要落在创建活动、抢券或查询（含搜索与回放）之一。memory / prod 双实现算活代码：测试走内存，Compose `prd` 走 Redis / Kafka / Elasticsearch。

未注入的包装仓库、零调用方法、未暴露的 HTTP、只为旧路径准备的 Redis 键，一律删除，不要留「以后可能用」。Gateway 上的 `PUT /admin/promotions/{id}`、`GET /query/coupons/search` 和直连 Event 的回放仍是契约，前端未接不等于死代码。

新增入口时同步改 README、`docs/learn/` 和本目录 ADR。
