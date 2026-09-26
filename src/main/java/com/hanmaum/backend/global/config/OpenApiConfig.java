package com.hanmaum.backend.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
  @Bean
  OpenAPI hanmaumOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("한마음 API")
                .version("v1")
                .description("가족 돌봄 공간 API. 변경 요청은 로그인 세션과 CSRF 토큰이 필요합니다."));
  }
}
