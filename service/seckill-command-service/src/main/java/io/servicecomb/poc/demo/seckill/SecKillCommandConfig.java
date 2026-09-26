/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SecKillCommandConfig.java                       │
 * │ 链路：抢券 · Command 装配                       │
 * └─────────────────────────────────────────────────┘
 *
 * 【本文件】装入 Redis，并启动引导器
 * │
 * ├─ publishTime ─▼ 初始化 Redis + PromotionStartEvent
 * └─ 抢券 Bean ─▼ 热路径只走 Redis Lua
 *
 * 一句话：本类只声明 Bean，到点才有库存，HTTP 成功不等于已落库。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.SecKillRedisConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 把 Command 服务要用的 Redis 和本进程自己的对象装进 Spring 容器。
 * <p>
 * {@code @Configuration} 表示这个类只用来声明 Bean，不处理 HTTP。{@code @Import} 把库模块里的
 * Redis 配置加载进来，容器里才会有 {@link SecKillStore}。outbox 发到 Kafka 由独立的 Relay 服务负责。
 * 测试默认
 * {@code seckill.infra.mode=memory}，库配置装配内存实现。Spring 的 {@code prd} 档会把该配置写成 {@code prod}，这时才连接 Docker Compose 里的主机。
 * 具体配置键写在 {@link SecKillRedisConfig} 里。
 * <p>
 * 方法参数同样是构造器注入的一种形式：Spring 调用 {@code @Bean} 方法时，按类型把已经创建好的对象传进来。
 * 抢券成功仍然只落在 Redis；{@code PromotionStartEvent} 到 {@code publishTime} 才写入 PostgreSQL。
 */
@Configuration
@Import({SecKillRedisConfig.class})
class SecKillCommandConfig {

  /**
   * 当前进程里已经启动的活动。键是 promotionId。
   * <p>
   * 用同一份可变 {@link HashMap}：引导器到点后往里放，控制器从里面拿。Spring 只创建一次。
   *
   * @return 启动时为空的活动表
   */
  @Bean
  Map<String, SecKillCommandService<String>> commandServices() {
    return new HashMap<String, SecKillCommandService<String>>();
  }

  /**
   * 创建活动引导器，并马上开始定时扫描。
   * <p>
   * {@code run()} 每 500 毫秒看一次 PostgreSQL 里的活动。到 {@code publishTime} 才初始化 Redis，
   * 并在事件表还没有记录时写入 {@code PromotionStartEvent} 和 outbox。
   *
   * @param promotionRepository 读 Admin 已经写入的活动表
   * @param commandServices 与控制器共享的活动表
   * @param recoveryService 用事件表算出剩余库存和已抢顾客
   * @param store Redis 库存与抢券流
   * @param writer 把开始事件和 outbox 放进同一个事务
   * @param eventFormat 事件、数据库行和 JSON 之间的转换
   * @return 已经开始定时扫描的引导器
   */
  @Bean
  SecKillPromotionBootstrap<String> secKillPromotionBootstrap(SpringPromotionRepository promotionRepository,
      Map<String, SecKillCommandService<String>> commandServices,
      SecKillRecoveryService<String> recoveryService,
      SecKillStore store,
      TransactionalEventOutboxWriter writer,
      SecKillEventFormat eventFormat) {
    SecKillPromotionBootstrap<String> promotionBootstrap = new SecKillPromotionBootstrap<String>(promotionRepository,
        commandServices, recoveryService, store, writer, eventFormat);
    promotionBootstrap.run();
    return promotionBootstrap;
  }

  /**
   * 创建重启时读取事件表的恢复服务。顾客编号在本服务里固定为 {@code String}。
   *
   * @param eventRepository 按活动编号查询已追加的事件
   * @param eventFormat 把事件行还原成抢券事件，以便取出顾客编号
   * @return 只读事件表的恢复服务
   */
  @Bean
  SecKillRecoveryService<String> secKillRecoveryService(
      io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository eventRepository,
      SecKillEventFormat eventFormat) {
    return new SecKillRecoveryService<String>(eventRepository, eventFormat);
  }
}
