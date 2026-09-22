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

  public String getRateLimiter() {
    return rateLimiter;
  }

  public void setRateLimiter(String rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  public String getAdminUri() {
    return adminUri;
  }

  public void setAdminUri(String adminUri) {
    this.adminUri = adminUri;
  }

  public String getCommandUri() {
    return commandUri;
  }

  public void setCommandUri(String commandUri) {
    this.commandUri = commandUri;
  }

  public String getQueryUri() {
    return queryUri;
  }

  public void setQueryUri(String queryUri) {
    this.queryUri = queryUri;
  }

  public String getRedisHost() {
    return redisHost;
  }

  public void setRedisHost(String redisHost) {
    this.redisHost = redisHost;
  }

  public int getRedisPort() {
    return redisPort;
  }

  public void setRedisPort(int redisPort) {
    this.redisPort = redisPort;
  }

  public Limit getAdmin() {
    return admin;
  }

  public void setAdmin(Limit admin) {
    this.admin = admin;
  }

  public Limit getCommand() {
    return command;
  }

  public void setCommand(Limit command) {
    this.command = command;
  }

  public Limit getQuery() {
    return query;
  }

  public void setQuery(Limit query) {
    this.query = query;
  }

  public static class Limit {
    private int replenishRate;
    private int burstCapacity;

    public Limit() {
    }

    public Limit(int replenishRate, int burstCapacity) {
      this.replenishRate = replenishRate;
      this.burstCapacity = burstCapacity;
    }

    public int getReplenishRate() {
      return replenishRate;
    }

    public void setReplenishRate(int replenishRate) {
      this.replenishRate = replenishRate;
    }

    public int getBurstCapacity() {
      return burstCapacity;
    }

    public void setBurstCapacity(int burstCapacity) {
      this.burstCapacity = burstCapacity;
    }
  }
}
