/*
 *   Copyright 2017 Huawei Technologies Co., Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.servicecomb.poc.demo.seckill.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 的配置对象，对应 {@code application.properties} 里前缀 {@code seckill.gateway} 的键。
 * <p>
 * {@code @ConfigurationProperties} 让 Spring 把 {@code seckill.gateway.admin-uri} 这类键填进字段。
 * 短横线会对应驼峰，例如 {@code rate-limiter} 填到 {@code rateLimiter}。
 * {@link io.servicecomb.poc.demo.GatewayApplication} 上的 {@code @EnableConfigurationProperties} 才会注册本类。
 * <p>
 * 这些字段只决定转发地址、限流放在内存还是 Redis、以及每条路由的速率。Gateway 不把它们写成业务数据。
 * {@code rateLimiter} 默认 {@code memory}，和 {@link GatewayRateLimiterConfiguration} 里
 * {@code matchIfMissing = true} 的内存 Bean 一致；写成 {@code redis} 才连接 {@code redisHost}:{@code redisPort}。
 */
@ConfigurationProperties(prefix = "seckill.gateway")
public class SecKillGatewayProperties {

  private String rateLimiter = "memory";
  private String adminUri = "http://localhost:8081";
  private String commandUri = "http://localhost:8082";
  private String queryUri = "http://localhost:8083";
  private String redisHost = "localhost";
  private int redisPort = 6379;
  private Limit admin = new Limit(10, 20);
  private Limit command = new Limit(50, 100);
  private Limit query = new Limit(100, 200);

  /** {@code memory} 或 {@code redis}，决定限流计数放在哪。 */
  public String getRateLimiter() {
    return rateLimiter;
  }

  /** 绑定 {@code seckill.gateway.rate-limiter}。 */
  public void setRateLimiter(String rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  /** Admin 服务地址，默认本机 8081。 */
  public String getAdminUri() {
    return adminUri;
  }

  /** 绑定 {@code seckill.gateway.admin-uri}。 */
  public void setAdminUri(String adminUri) {
    this.adminUri = adminUri;
  }

  /** Command 服务地址，默认本机 8082。 */
  public String getCommandUri() {
    return commandUri;
  }

  /** 绑定 {@code seckill.gateway.command-uri}。 */
  public void setCommandUri(String commandUri) {
    this.commandUri = commandUri;
  }

  /** Query 服务地址，默认本机 8083。 */
  public String getQueryUri() {
    return queryUri;
  }

  /** 绑定 {@code seckill.gateway.query-uri}。 */
  public void setQueryUri(String queryUri) {
    this.queryUri = queryUri;
  }

  /** 限流计数使用的 Redis 主机，仅 {@code rate-limiter=redis} 时连接。 */
  public String getRedisHost() {
    return redisHost;
  }

  /** 绑定 {@code seckill.gateway.redis-host}。 */
  public void setRedisHost(String redisHost) {
    this.redisHost = redisHost;
  }

  /** 限流 Redis 端口，默认 6379。 */
  public int getRedisPort() {
    return redisPort;
  }

  /** 绑定 {@code seckill.gateway.redis-port}。 */
  public void setRedisPort(int redisPort) {
    this.redisPort = redisPort;
  }

  /** {@code /admin/**} 的补充速率和突发容量。 */
  public Limit getAdmin() {
    return admin;
  }

  /** 绑定 {@code seckill.gateway.admin.*}。 */
  public void setAdmin(Limit admin) {
    this.admin = admin;
  }

  /** {@code /command/**} 的补充速率和突发容量。 */
  public Limit getCommand() {
    return command;
  }

  /** 绑定 {@code seckill.gateway.command.*}。 */
  public void setCommand(Limit command) {
    this.command = command;
  }

  /** {@code /query/**} 的补充速率和突发容量。 */
  public Limit getQuery() {
    return query;
  }

  /** 绑定 {@code seckill.gateway.query.*}。 */
  public void setQuery(Limit query) {
    this.query = query;
  }

  /**
   * 一条路由的令牌桶参数。{@code replenishRate} 是每秒补充的令牌数，{@code burstCapacity} 是桶里最多存多少。
   * <p>
   * Spring 绑定嵌套键时需要无参构造器，例如 {@code seckill.gateway.command.replenish-rate}。
   */
  public static class Limit {
    private int replenishRate;
    private int burstCapacity;

    /** 给配置绑定用的空构造器。 */
    public Limit() {
    }

    /**
     * 代码里的默认限额。
     *
     * @param replenishRate 每秒补充的令牌
     * @param burstCapacity 桶容量
     */
    public Limit(int replenishRate, int burstCapacity) {
      this.replenishRate = replenishRate;
      this.burstCapacity = burstCapacity;
    }

    /** 每秒补充多少令牌。 */
    public int getReplenishRate() {
      return replenishRate;
    }

    /** 绑定 {@code replenish-rate}。 */
    public void setReplenishRate(int replenishRate) {
      this.replenishRate = replenishRate;
    }

    /** 桶里最多保留多少令牌。 */
    public int getBurstCapacity() {
      return burstCapacity;
    }

    /** 绑定 {@code burst-capacity}。 */
    public void setBurstCapacity(int burstCapacity) {
      this.burstCapacity = burstCapacity;
    }
  }
}
