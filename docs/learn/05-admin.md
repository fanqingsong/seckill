# 第 5 课：Admin，创建活动

本课是第一个「普通的」Spring MVC 服务。它接收 JSON，检查字段，写入 PostgreSQL。它不访问 Redis，也不发 Kafka。

## 要打开的文件

1. `service/seckill-admin-service/src/main/java/io/servicecomb/poc/demo/seckill/web/SecKillAdminRestController.java`
2. `library/seckill-event-store/src/main/java/io/servicecomb/poc/demo/seckill/entities/PromotionEntity.java`
3. `library/seckill-event-store/src/main/java/io/servicecomb/poc/demo/seckill/dto/PromotionDto.java`

启动类 `AdminServiceApplication.java` 和第 2 课的 Gateway 入口是同一种 `main`，看一眼即可。

## 请求体长什么样

前端 `createPromotion` 发送的 JSON 字段：

- `numberOfCoupons`：券的数量
- `discount`：折扣
- `publishTime`：开始时间，毫秒时间戳
- `finishTime`：结束时间，毫秒时间戳

`PromotionDto` 是这个 JSON 的 Java 形态。`PromotionEntity` 是要存进数据库的那一行。控制器里 `new PromotionEntity(...)` 就是在做这个转换。

## `create` 方法在做什么

`POST /admin/promotions/` 进入 `create`：

1. `isValidPromotion` 检查数量、折扣和时间是否说得通。不通过就抛 `ResponseStatusException`，HTTP 状态是 **400**。
2. 通过后 `promotionRepository.save(promotion)` 插入 PostgreSQL。
3. 返回 **200**，正文是新的 `promotionId`。

`SpringPromotionRepository` 是 Spring Data 的仓库接口。这里没有手写 INSERT 语句，`save` 由框架根据 `PromotionEntity` 上的表映射生成。第一遍把 `xxxRepository.save` 读成「写入对应的表」。

## 修改活动

同一个类里还有 `PUT /admin/promotions/{promotionId}`。它要求数据库里还没有这条活动的 `PromotionStartEvent`。活动一旦开始，就开始往事件表里追加记录，Admin 不再改原活动行。

读这个方法时注意 `@PathVariable("promotionId")`：花括号里的那一段来自 URL，不是来自 JSON 正文。

## 创建成功之后 Redis 里有库存吗

没有。Admin 的职责到 PostgreSQL 为止。Command 的 `SecKillPromotionBootstrap` 会周期扫描新活动，等到 `publishTime` 再初始化 Redis。那是第 6 课的开头。

## 核对

1. 创建活动的 URL、HTTP 方法、成功时返回的正文分别是什么？
2. `PromotionDto` 和 `PromotionEntity` 为什么要分成两个类？
3. 活动开始之后，为什么不能再靠 Admin 的 PUT 去改它？

下一课：[06-抢券热路径.md](06-抢券热路径.md)
