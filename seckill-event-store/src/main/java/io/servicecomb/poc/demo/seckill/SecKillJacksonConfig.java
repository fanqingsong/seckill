package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecKillJacksonConfig {

  @Bean
  public Format seckillFormat() {
    return new JacksonGeneralFormat();
  }

  @Bean
  public SecKillEventFormat secKillEventFormat(Format format) {
    return new SecKillEventFormat(format);
  }
}
