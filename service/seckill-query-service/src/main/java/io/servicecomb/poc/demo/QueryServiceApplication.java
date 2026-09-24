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

package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Query 服务的进程入口。列表和「我的券」读 Redis，搜索读 Elasticsearch，不连接 PostgreSQL。
 * <p>
 * {@code @SpringBootApplication} 让 Spring 从本类所在的包往下扫描并启动 HTTP（本服务端口 8083）。
 * {@code exclude} 关掉数据源、JPA 和仓库的自动配置：本进程没有数据库。若不去掉，启动时会因为找不到
 * 数据源而失败。浏览器只访问前端 nginx；Gateway 把 {@code /query} 转到这里。
 * <p>
 * 抢券成功后查询页暂时没有券，是因为读模型还没被 Event 服务投影，不是本进程漏查。
 */
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    JpaRepositoriesAutoConfiguration.class
})
public class QueryServiceApplication {

  /**
   * 创建 Spring 容器并开始监听 HTTP。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置，这里不解析业务含义
   */
  public static void main(String[] args) {
    SpringApplication.run(QueryServiceApplication.class, args);
  }
}
