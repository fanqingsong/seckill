/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillRedisConfig.java                       │
 * │ 链路：启动 · 选择存储                         │
 * └───────────────────────────────────────────────┘
 *
 * Spring 创建 @Bean
 *        │
 *        ▼
 *    【本文件】
 *  mode 是否等于 prod
 *   ├─ 是 ─ Jedis
 *   └─ 否 ─ 内存类
 *
 * 一句话：profile 名不选客户端；mode=prod 才连 Redis。
 */

package io.servicecomb.poc.demo.seckill.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * 按配置选择热路径库存和读模型用哪一种 {@link SecKillStore}。
 * <p>
 * {@code @Configuration} 表示 Spring 启动时会读这个类，并调用标了 {@code @Bean} 的方法。
 * {@code @Bean} 表示方法的返回值放进容器，Command、Persist、Event、Query 用构造器注入拿到同一个对象。
 * 选实现的条件写在方法体内：配置项 {@code seckill.infra.mode} 的值等于 {@code prod} 时连接真实 Redis，
 * 其它值使用内存实现。该项缺省是 {@code memory}，所以单元测试不用起 Redis。
 */
@Configuration
public class SecKillRedisConfig {

  /**
   * 创建全进程共用的秒杀存储。
   * <p>
   * {@code prod}：Jedis 连接池，最大 64 条连接，主机和端口来自
   * {@code seckill.redis.host}（缺省 {@code 127.0.0.1}）和 {@code seckill.redis.port}（缺省 6379）。
   * 其它任何模式，包括缺省的 {@code memory}：进程内的 {@link InMemorySecKillStore}。
   * 这里不初始化某个活动的库存，库存要等到 Command 的 {@code publishTime}。
   *
   * @param mode {@code seckill.infra.mode}，缺省 {@code memory}
   * @param host {@code seckill.redis.host}，只在 {@code prod} 时使用
   * @param port {@code seckill.redis.port}，只在 {@code prod} 时使用
   * @return 放进 Spring 容器的 {@link SecKillStore}
   */
  @Bean
  public SecKillStore secKillStore(
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.redis.host:127.0.0.1}") String host,
      @Value("${seckill.redis.port:6379}") int port) {
    // 只有配置值恰好是 prod 才连 Redis。缺省 memory、空串或其它拼写都走内存实现。
    // Spring 的 prd 档本身不是这个字符串；application-prd.properties 里把 seckill.infra.mode 设成 prod。
    if ("prod".equals(mode)) {
      JedisPoolConfig config = new JedisPoolConfig();
      config.setMaxTotal(64);
      return new JedisSecKillStore(new JedisPool(config, host, port));
    }
    return new InMemorySecKillStore();
  }
}
