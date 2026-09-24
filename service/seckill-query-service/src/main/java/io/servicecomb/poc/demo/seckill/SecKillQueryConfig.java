package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.es.SecKillEsConfig;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.redis.SecKillRedisConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 把 Query 服务要用的 Redis 读模型和 Elasticsearch 装进 Spring 容器。
 * <p>
 * {@code @Configuration} 表示这个类只用来声明 Bean，不处理 HTTP。{@code @Import} 加载库模块里的
 * Redis 和 ES 配置，容器里才会有 {@link SecKillStore} 和 {@link SecKillSearchIndex}。测试默认
 * {@code seckill.infra.mode=memory}，库配置装配内存实现；生产形态才连真实 Redis 和 Elasticsearch。
 * 本服务不连 PostgreSQL，活动定义和事件不在这里读。
 * <p>
 * {@code @Bean} 方法的参数由 Spring 按类型注入：调用时传入已经创建好的读模型和搜索索引。
 */
@Configuration
@Import({SecKillRedisConfig.class, SecKillEsConfig.class})
class SecKillQueryConfig {

  /**
   * 创建查询服务。列表走 Redis，搜索走 Elasticsearch。
   *
   * @param store Redis 读模型
   * @param searchIndex 搜索索引
   * @return 供控制器调用的查询服务
   */
  @Bean
  SecKillQueryService seckillQueryService(SecKillStore store, SecKillSearchIndex searchIndex) {
    return new SecKillQueryService(store, searchIndex);
  }
}
