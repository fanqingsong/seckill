/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ SecKillJacksonConfig.java                            │
 * │ 链路：Spring 装配                                    │
 * └──────────────────────────────────────────────────────┘
 *
 *   Spring 启动时读取本配置
 *      │
 *      ▼
 *   【本文件】注册 Format 与事件转换器
 *      │
 *      ▼
 *   Command、Persist、Event 注入使用
 *
 * 一句话：只往容器放两个内存对象，不访问数据库。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 JSON 工具和事件转换器交给 Spring，供 Command、Persist、Event 等服务注入。
 * <p>
 * {@code @Configuration} 表示这是配置类：Spring 启动时读它，并调用带 {@code @Bean} 的方法，
 * 把返回值放进容器。各服务因此不用自己 {@code new}。这里不访问 PostgreSQL、Redis、Kafka
 * 或 Elasticsearch，只注册两个内存对象。
 */
@Configuration
public class SecKillJacksonConfig {

  /**
   * 注册全项目共用的 JSON 实现。
   * <p>
   * {@code @Bean} 表示返回值成为容器里的一个 {@link Format}。方法名 {@code seckillFormat}
   * 默认就是这个 Bean 的名字。
   *
   * @return Jackson 实现，不连接数据库
   */
  @Bean
  public Format seckillFormat() {
    return new JacksonGeneralFormat();
  }

  /**
   * 注册事件转换器。参数 {@code format} 是方法参数注入：Spring 把上面那个 {@link Format} 传进来。
   *
   * @param format 容器里的 JSON 工具
   * @return 可在消息、事件和表行之间转换的对象
   */
  @Bean
  public SecKillEventFormat secKillEventFormat(Format format) {
    return new SecKillEventFormat(format);
  }
}
