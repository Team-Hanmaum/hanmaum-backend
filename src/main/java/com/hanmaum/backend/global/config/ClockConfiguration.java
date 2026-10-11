package com.hanmaum.backend.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
