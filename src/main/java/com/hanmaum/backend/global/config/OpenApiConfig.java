package com.hanmaum.backend.global.config;

import com.hanmaum.backend.global.openapi.ErrorResponseDocumentation;
import com.hanmaum.backend.global.response.ApiResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {
  @Bean
  OpenAPI hanmaumOpenApi(SpringDocConfigProperties properties, ResourceLoader resourceLoader) {
    var components =
        new Components()
            .schemas(
                ModelConverters.getInstance(properties.isOpenapi31()).readAll(ApiResponse.class))
            .addSecuritySchemes(
                "SessionCookie",
                new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.COOKIE)
                    .name("HANMAUM_SESSION")
                    .description("브라우저가 보내는 HttpOnly 세션 쿠키. 로그인 필요 여부는 각 API 권한을 따릅니다."))
            .addSecuritySchemes(
                "CsrfToken",
                new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-CSRF-TOKEN")
                    .description("GET /api/auth/csrf의 token 값. 로그인·로그아웃 후 재발급합니다."));
    ErrorResponseDocumentation.register(components, resourceLoader);
    return new OpenAPI()
        .info(
            new Info()
                .title("한마음 API")
                .version("v1")
                .description(
                    "API 경로 기준은 /api입니다. 구현된 경로만 문서화합니다. 공통 JSON 응답은 여섯 필드이며 CSRF 발급·OAuth 리다이렉트·204 응답은 예외입니다."))
        .components(components)
        .paths(new Paths().addPathItem("/api/auth/logout", logoutPath()));
  }

  private PathItem logoutPath() {
    return new PathItem()
        .post(
            new Operation()
                .operationId("logout")
                .addTagsItem("Auth")
                .summary("로그아웃")
                .description(
                    "현재 세션을 무효화하고 HANMAUM_SESSION 쿠키를 제거합니다. 유효한 CSRF 토큰이 필요합니다. 기존 필터 동작상 비로그인 세션도 유효한 토큰이 있으면 204를 반환합니다.")
                .addSecurityItem(
                    new SecurityRequirement().addList("SessionCookie").addList("CsrfToken"))
                .responses(
                    new ApiResponses()
                        .addApiResponse(
                            "204",
                            new io.swagger.v3.oas.models.responses.ApiResponse()
                                .description("세션 무효화 완료. 응답 본문 없음"))
                        .addApiResponse(
                            "403",
                            new io.swagger.v3.oas.models.responses.ApiResponse()
                                .$ref("#/components/responses/FORBIDDEN"))));
  }
}
