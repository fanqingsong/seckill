package io.servicecomb.poc.demo.seckill.es;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecKillEsConfig {

  @Bean
  public SecKillSearchIndex secKillSearchIndex(
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.es.url:http://127.0.0.1:9200}") String url) {
    if ("prod".equals(mode)) {
      return new HttpElasticsearchIndex(url);
    }
    return new InMemorySearchIndex();
  }
}
