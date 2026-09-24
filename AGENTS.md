# SecKill 项目：给 Agent 的工作说明

本仓库是一个给 **Java 初学者** 读的秒杀示例（CQRS + 事件溯源）。后续 Agent 改代码、补文档、回答问题时，默认读者还不会熟练使用 Spring，需要把「这段代码在整条请求里干什么」写清楚。

权威设计说明仍是根目录 [README.md](README.md)。按课阅读顺序在 [docs/learn/README.md](docs/learn/README.md)。本文件约束 Agent 怎么写，不另起一套架构。

## 每次动代码之前

1. 先判断请求落在哪条链路上，再改对应模块。不要为了「看起来更简单」把抢券改成同步写 PostgreSQL。
2. 行为有变化时，同时更新 [README.md](README.md) 里相关段落，以及 `docs/learn/` 中对应的课。注释、文档、代码三者描述同一件事。
3. 只改任务需要的文件。不要顺手重排 import、重命名公开类型、或改 HTTP 路径、Redis 键、Kafka topic、表结构。
4. 文件顶部若已有 Apache License 版权声明，原样保留，教学注释写在版权声明之后。没有版权声明的文件不要新补一份。
5. 对话里用中文说明。代码里的类名、方法名、配置键保持英文。注释和 `docs/learn/` 使用中文。

## 注释：必须详细，并且教人读

用户明确要求 **详细注释**。新增或修改的 Java / TypeScript 都要带注释，让初学者不查框架文档也能顺着读下去。

每个源文件在版权声明之后、`package` 或 `import` 之前，加一段文件头注释。头注释用中文，并且必须带一张纯文本示意图，让人先看见这个文件在链路里的位置，再往下读类。示意图用 `┌ ┐ └ ┘ │ ▼ ─` 画，标出「本文件」这一步，以及数据落在 Redis、PostgreSQL、Kafka 还是 Elasticsearch。不要贴整张系统大图。已有类注释保留，头注释不把类注释再抄一遍。

示例：

```java
/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SecKillCommandService.java                      │
 * │ 链路：抢券 · Command 服务                        │
 * └─────────────────────────────────────────────────┘
 *
 *   Gateway  POST /command/coupons/
 *      │
 *      ▼
 *   【本文件】判断是否结束，调用 Redis Lua
 *      │
 *      ├── 成功 ──▶ HTTP「已接受」（PostgreSQL 还没有这张券）
 *      └── 卖完 ──▶ 结束事件交给 outbox
 *
 * 一句话：热路径只扣 Redis，不在这里写库。
 */
```

写注释时说明三件事，而不是把下一行代码翻译成中文：

- **它是什么**：这个类或方法在秒杀流程里的角色。
- **为什么在这里**：它为什么不直接写数据库，或者为什么要和别的写入放在同一个事务里。
- **初学者会卡的语法**：注解、泛型、`Optional`、Lambda、构造器注入，在该文件第一次出现时用一两句中文点明。

### Java

- 每个类、接口、枚举写类级 Javadoc。第一句说职责，接着说它被谁调用、成功或失败后数据落在 Redis、PostgreSQL、Kafka 还是 Elasticsearch。
- 每个非平凡方法写方法级 Javadoc：参数含义、返回值表示什么、会不会改库存或发消息。会抛出的业务异常写进 `@throws` 或正文。
- `getXxx` / `setXxx` 用一行说明字段含义即可，不要逐行复述 `return this.xxx`。
- 分支、循环、锁、Lua 脚本、事务边界、重试和补偿，在代码旁边用 `//` 写清判断条件代表的业务状态。
- Spring 注解在类注释里集中解释。例如 `@RestController` 表示这个类接收 HTTP，`@RequestMapping` 上的路径要写明完整 URL 大概是什么。
- 配置类要写出 `application.properties` 里的键名，以及 `demo` / `prd` / 默认 memory 模式各会装配哪一种实现。

示例（保持这种密度，不要更短）：

```java
/**
 * 抢券热路径上的写服务。
 * <p>
 * Gateway 把 {@code POST /command/coupons/} 转到 Command 服务后，控制器会调用本类。
 * 本类只通过 {@link SecKillStore} 和 Redis 判断库存；扣减成功后把结果交给
 * {@link TransactionalEventOutboxWriter}。HTTP 返回「已接受」时，PostgreSQL 里的事件
 * 可能尚未写入，查询页也会晚一点才能看见这张券。这是预期行为，不是漏写。
 * <p>
 * 类名后面的 {@code <T>} 是泛型：顾客编号的具体类型由调用方决定，本类不关心它是字符串还是数字。
 */
public class SecKillCommandService<T> {

  /**
   * 为一名顾客抢一张券。
   *
   * @param customerId 顾客编号，同一活动内重复提交应得到「已经抢过」而不是再扣库存
   * @return 抢券结果。成功只表示 Redis 已扣减；失败原因见结果对象里的说明
   */
  public SecKillGrabResult addCouponTo(T customerId) {
    // 活动已被标记结束，或墙上时钟已经超过 finishTime：直接拒绝，不再访问 Redis。
    if (finished.get() || promotion.getFinishTime().getTime() < System.currentTimeMillis()) {
      ...
    }
  }
}
```

### TypeScript / CSS

- `service/frontend/` 里每个导出的函数、组件，在声明上方用中文块注释说明它请求哪一个后端路径、页面上对应哪一块。
- 样式文件只注释「这一组规则服务页面上的哪一块」，不要给每条 `margin` 写注释。

### 测试

- 测试类注释写明：它替代了哪些外部系统（H2、内存 Redis、内存 Kafka），以及它守护的用户场景。
- 每个 `@Test` 方法用一行到三行说明前置条件、动作和期望。断言旁边不必重复断言语句本身。

### 不要这样写

- 不要写「定义一个变量」「调用方法」这种和代码逐字对应的注释。
- 不要用注释关掉编译错误，或把过期行为留在注释里冒充文档。行为改了，注释一起改。
- 不要为了加注释去改逻辑、改可见性或改格式化工具会再改回去的空白。

## 系统地图（改动时对照）

浏览器只访问前端 nginx **8080**。nginx 把 `/admin`、`/command`、`/query` 转到 Gateway **8085**。回放接口不要从浏览器这条代理暴露出去。

| 模块 | 端口 | 允许它做的事 |
|------|------|----------------|
| `service/seckill-admin-service` | 8081 | 创建和修改活动，写入 PostgreSQL。此时 Redis 里还没有库存 |
| `service/seckill-command-service` | 8082 | 到 `publishTime` 初始化 Redis；抢券只走 Redis Lua；活动开始/结束事件与 outbox |
| `service/seckill-persist-service` | 8086 | 消费 Redis 抢券队列，在同一事务里写事件和 outbox |
| `service/seckill-event-service` | 8084 | 消费 Kafka `seckill.events`，投影到 Redis 读模型和 Elasticsearch；提供回放 |
| `service/seckill-query-service` | 8083 | 列表和「我的券」读 Redis。搜索才读 Elasticsearch |
| `service/seckill-gateway` | 8085 | 按路径转发，限流，熔断。不写业务数据 |
| `service/frontend` | 8080 | 页面。不直接连接 PostgreSQL、Redis、Kafka |
| `library/seckill-event-store` | — | 事件、实体、仓库，给多个服务共用 |
| `library/seckill-infra-redis` | — | 热路径库存与读模型的 Redis 访问 |
| `library/seckill-infra-kafka` | — | 事件发布与消费 |
| `library/seckill-infra-es` | — | 搜索索引 |

三条用户操作不要混线：

1. **创建活动**：Admin → PostgreSQL。Command 里的定时任务等到 `publishTime` 才初始化 Redis，并记下 `PromotionStartEvent`。
2. **抢券**：Command → Redis。HTTP 成功只表示库存已扣。随后 Persist 写 PostgreSQL，outbox 再经 Kafka 到 Event，Query 才能读到。
3. **查询**：Query → Redis（搜索接口才到 Elasticsearch）。刚抢成功时查询页暂时没有券，是正常延迟。

三种事件：`PromotionStartEvent`（库存已初始化）、`CouponGrabbedEvent`（某人抢到）、`PromotionFinishEvent`（卖完，或到结束时间且抢券队列已空）。

基础设施：PostgreSQL 5432（活动、追加事件、outbox）、Redis 6379（热路径、读模型、生产环境限流计数）、Kafka 9092 KRaft（`seckill.events`，key 为 `promotionId`；死信 `seckill.events.dlt`）、Elasticsearch 9200。

测试默认 `seckill.infra.mode=memory`（内存 Redis / Kafka / ES，数据库用 H2）。Spring profile 名叫 `prd`，它通过 `application-prd.properties` 把 `seckill.infra.mode` 设为 `prod`。代码里用 `"prod".equals(mode)` 判断是否连接 Docker Compose 里的 Redis、Kafka、Elasticsearch。不要把 profile 名 `prd` 和配置值 `prod` 写成同一个词。

## 实现时必须守住的行为

- 抢券热路径保持「一次 Lua 完成判断与扣减」。不要在 HTTP 线程里同步写 PostgreSQL 或发 Kafka。
- 事件表是追加的。不要把剩余库存改成唯一的一张可变业务表来代替事件。
- 发到 Kafka 的消息来自 outbox，并且和事件插入处在同一个数据库事务里。不要先发消息再提交数据库。
- 查询不要直接扫事件表来拼页面。读模型由 Event 服务投影。
- Gateway 的限流和熔断保持可配置。生产形态的限流计数在 Redis，测试可以用内存实现。
- Java 版本 17，Spring Boot 3.3（Jakarta）。不要引入 `javax.*`，不要把 ServiceComb Vert.x 0.2 加回运行时。
- 公开 HTTP 路径、JSON 字段名、Redis 键格式、Kafka topic 是跨服务契约。改动时同时改调用方、测试和文档。

## 文档

- 新行为若改变「请求从哪进、数据落在哪」，更新 `docs/learn/` 里对应课，并在该课文末核对题仍然问得通。一次一课的结构不要拆散。
- `docs/learn/` 用中文，句子短，先讲请求走到哪，再贴必要的代码。版权声明、成排的 getter/setter 不要大段贴进课文。
- 根目录 README 保持英文，和现有章节结构一致。不要把 README 改成第二份中文教程。
- 不要新写一份与 README 或 `docs/learn/` 重复的架构长文，除非用户明确要求。

## 构建、运行、测试

- 构建：在仓库根目录执行 `mvn test`（或用户指定的模块）。
- 本地多进程用 **`docker compose`**，不要写成 `docker-compose`。
- Dockerfile 需要国内镜像时，在镜像名前加 `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/`。不要改用户没有要求改的基础镜像。
- 改完服务间契约或读模型后，至少跑相关模块的单元测试；跨服务行为用 `test/integration-test`。
- 前端改了页面交互或样式时，在浏览器里把对应操作走一遍（创建活动、抢券、查询），不要只看编译通过。

## 回答用户时

- 用户是 Java 初学者。先说这段代码在三条链路里的哪一条，再说类和方法。注解翻译成「Spring 看到这个标签后会做什么」。
- 指出文件路径，方便打开。不要一次抛出整个服务的全部类。
- 抢券 HTTP 成功不等于 PostgreSQL 已有记录，解释延迟时把这句话说清楚。
