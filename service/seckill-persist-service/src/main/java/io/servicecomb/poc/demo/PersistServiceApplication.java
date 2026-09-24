package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Persist 服务的进程入口。它不接收抢券 HTTP，只把 Redis 里已经扣减成功的令牌写成数据库记录。
 * <p>
 * Spring 看到 {@code @SpringBootApplication} 后，会扫描本包及子包，注册 {@code @Component}，
 * 并启动进程。端口在 {@code application.properties}，本服务是 8086。浏览器和 Gateway 都不把抢券请求转到这里。
 * <p>
 * {@code @EnableTransactionManagement(proxyTargetClass = true)} 打开声明式事务，并使用子类代理。
 * 这样 {@code PersistOutboxWriter.persist} 上的 {@code @Transactional} 才会包住具体类的方法：
 * {@code CouponGrabbedEvent} 和 outbox 行在同一个 PostgreSQL 事务里提交。Kafka 仍由 Command 服务的
 * outbox relay 在提交之后发送，本进程不发消息。
 * <p>
 * Command 返回「已接受」时，本服务可能还没消费到那条 Redis 令牌，所以 PostgreSQL 里还没有对应的行。
 */
@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
public class PersistServiceApplication {

  /**
   * 创建 Spring 容器。容器起来后，{@code GrabPersistWorker} 的构造器会开始拉取抢券流。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置
   */
  public static void main(String[] args) {
    SpringApplication.run(PersistServiceApplication.class, args);
  }
}
