/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillEsConfig.java                          │
 * │ 链路：启动 · 选择索引                         │
 * └───────────────────────────────────────────────┘
 *
 * Spring 创建 @Bean
 *        │
 *        ▼
 *    【本文件】
 *  mode 是否等于 prod
 *   ├─ 是 ─ HTTP ES
 *   └─ 否 ─ 内存索引
 *
 * 一句话：profile 名不选客户端；mode=prod 才连 ES。
 */

package io.servicecomb.poc.demo.seckill.es;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置选择搜索索引用真实 Elasticsearch，还是进程内的 Map。
 * <p>
 * {@code @Configuration} 表示 Spring 启动时加载本类。{@code @Bean} 表示返回值放进容器，
 * Event 服务投影时写入，Query 的搜索接口读出。活动列表和「我的券」不使用这个 Bean，它们读 Redis。
 * 条件写在方法体内：{@code seckill.infra.mode} 等于 {@code prod} 时发 HTTP 给 Elasticsearch，
 * 其它值（缺省 {@code memory}）使用内存索引。
 */
@Configuration
public class SecKillEsConfig {

  /**
   * 创建搜索索引。
   * <p>
   * {@code prod} 时地址来自 {@code seckill.es.url}，缺省 {@code http://127.0.0.1:9200}。
   * 这里不创建索引模板，第一次写入时由 Elasticsearch 按文档自动建字段。
   *
   * @param mode {@code seckill.infra.mode}
   * @param url {@code seckill.es.url}，只在 {@code prod} 时使用
   * @return 放进 Spring 容器的 {@link SecKillSearchIndex}
   */
  @Bean
  public SecKillSearchIndex secKillSearchIndex(
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.es.url:http://127.0.0.1:9200}") String url) {
    // 只有字符串恰好是 prod 才访问 Elasticsearch。
    if ("prod".equals(mode)) {
      return new HttpElasticsearchIndex(url);
    }
    return new InMemorySearchIndex();
  }
}
