# 第 2 课：怎么读这个项目里的 Java

本课用 Gateway 里已经见过的文件练眼。看懂这几种写法之后，后面的服务都是同一套骨架。

## 一个 Java 文件里先看哪里

打开任意 `.java`，从上到下通常是：

1. 版权注释。跳过。
2. `package ...`：这个类在哪个目录。包名和文件夹一一对应。
3. `import ...`：本文件用到的别人写的类。先跳过，看到不认识的类型再回来查。
4. `public class 名字`：这个文件的主体。**一个文件通常就是一个类。**
5. 字段：这个对象记住的数据。
6. 方法：这个对象会做的动作。方法名后面有 `()`。

类名就是文件名。`GatewayApplication.java` 里面的类就叫 `GatewayApplication`。

## 这个仓库里真正要认的记号

**`main` 方法**

```java
public static void main(String[] args) {
  SpringApplication.run(GatewayApplication.class, args);
}
```

这是程序入口。Spring Boot 服务都长这样：`main` 把当前类交给 `SpringApplication.run`，框架负责读配置、创建对象、打开端口。每个服务有自己的 `*Application.java`：

- `service/seckill-gateway/.../GatewayApplication.java`
- `service/seckill-admin-service/.../AdminServiceApplication.java`
- `service/seckill-command-service/.../CommandServiceApplication.java`
- `service/seckill-query-service/.../QueryServiceApplication.java`
- `service/seckill-event-service/.../EventServiceApplication.java`

**以 `@` 开头的注解**

注解是贴在类或方法上的标签，给 Spring 看，不是业务计算。

| 注解 | 出现时表示 |
|------|------------|
| `@SpringBootApplication` | 这是启动类 |
| `@ConfigurationProperties(prefix = "seckill.gateway")` | 把配置文件里这个前缀下的项填进这个类的字段 |
| `@Configuration` | 这个类用来声明怎么组装对象 |
| `@Bean` | 这个方法的返回值交给 Spring 保管，别处可以注入使用 |
| `@RestController` | 这个类用来接收 HTTP 请求 |
| `@RequestMapping("/admin/promotions")` | 哪个 URL 会进这个类或方法 |
| `@Autowired` 或构造方法参数 | Spring 把依赖的对象传进来，代码里不用自己 `new` |

**`getXxx` / `setXxx`**

Java 里常见的读写方法。`SecKillGatewayProperties` 大半篇幅都是它们。阅读时只看类顶部的字段，例如 `replenishRate`、`burstCapacity`。

**配置文件**

每个服务的 `src/main/resources/application.properties` 是默认配置。`application-prd.properties` 是 Docker Compose 生产形态下覆盖的部分。字段名 `commandUri` 对应配置键 `command-uri`（大小写和连字符由 Spring 自动对应）。

## 请求是怎样进到某个方法的

以 Admin 为例，后面第 5 课会细读。现在先看形状：

```java
@RestController
@RequestMapping("/admin/promotions")
public class SecKillAdminRestController {

  @RequestMapping(method = RequestMethod.POST, value = "/")
  public ResponseEntity<String> create(@RequestBody PromotionDto promotionDto) {
    ...
  }
}
```

翻译：

- 有人 `POST /admin/promotions/`
- 请求体 JSON 被转成 `PromotionDto`
- Spring 调用 `create` 方法
- 方法返回的 `ResponseEntity` 变成 HTTP 状态码和正文

`@RequestBody` 表示「用请求体」。后面还会看到 `@PathVariable`（URL 路径里的一段）和 `@RequestParam`（`?promotionId=1` 这种查询参数）。

## 可以暂时不深究的词

| 词 | 现在把它理解成 |
|----|----------------|
| `Mono` | Gateway 里「稍后返回一个结果」的包装。Gateway 用的是响应式框架，Admin/Command/Query 用的是更常见的 Spring MVC，所以它们的控制器方法直接返回对象 |
| 泛型 `<String>` | 「这里的类型参数是字符串」。第一遍看到 `SecKillCommandService<String>` 时，把它当成处理字符串用户 ID 的抢券服务 |
| `library/` | 不是单独启动的服务，是几个服务共用的代码，打成 jar 被依赖 |

## 练习

打开 `service/seckill-gateway/src/main/java/io/servicecomb/poc/demo/seckill/gateway/SecKillGatewayProperties.java`。

1. 找出类上的注解，说出它和哪个配置前缀绑定。
2. 只读字段，不读 `get`/`set`，列出 Admin、Command、Query 各自的默认地址和限流数字。
3. 对照 `service/seckill-gateway/src/main/resources/application.properties`，看同一组数字出现在哪几行。

下一课：[03-前端和nginx.md](03-前端和nginx.md)
