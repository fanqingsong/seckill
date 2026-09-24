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

/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ GatewayApplication.java                         │
 * │ 链路：转发 · Gateway 入口                       │
 * └─────────────────────────────────────────────────┘
 *
 * 浏览器 nginx 8080 把 /admin /command /query 转来
 * │
 * ▼
 * 【本文件】启动 Gateway（端口 8085，不写业务数据）
 * │
 * ├── 默认 ──▶ 内存限流
 * └── seckill.gateway.rate-limiter=redis ──▶ Redis 计数
 *
 * 一句话：只转发、限流、熔断；回放接口不从这条代理进来。
 */

package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.servicecomb.poc.demo.seckill.gateway.SecKillGatewayProperties;

/**
 * Gateway 的进程入口。按路径转发、限流、熔断，不写活动、库存或券。
 * <p>
 * {@code @SpringBootApplication} 从本类所在包扫描组件并启动 HTTP（本服务端口 8085）。
 * {@code exclude} 关掉 Redis 自动配置：默认限流在内存里，不需要一启动就连 Redis。
 * 只有 {@code seckill.gateway.rate-limiter=redis} 时，限流配置类才自己创建连接。
 * {@code @EnableConfigurationProperties} 注册 {@link SecKillGatewayProperties}，
 * {@code seckill.gateway.*} 才会绑定到那个对象。
 * <p>
 * 浏览器访问前端 nginx 8080，nginx 把 {@code /admin}、{@code /command}、{@code /query} 转到这里，
 * 再由路由转到 8081、8082、8083。回放接口不从这条代理进来。
 */
@SpringBootApplication(exclude = {
    RedisAutoConfiguration.class,
    RedisReactiveAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class
})
@EnableConfigurationProperties(SecKillGatewayProperties.class)
public class GatewayApplication {

  /**
   * 创建 Spring 容器并开始监听 HTTP。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置，这里不解析业务含义
   */
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
