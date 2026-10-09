package com.hanmaum.backend.global.config;

import com.hanmaum.backend.global.security.CurrentUserArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {
  private final CurrentUserArgumentResolver currentUserResolver;

  public WebMvcConfig(CurrentUserArgumentResolver currentUserResolver) {
    this.currentUserResolver = currentUserResolver;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(currentUserResolver);
  }
}
