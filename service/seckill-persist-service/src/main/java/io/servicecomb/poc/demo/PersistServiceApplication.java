package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
public class PersistServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PersistServiceApplication.class, args);
  }
}
