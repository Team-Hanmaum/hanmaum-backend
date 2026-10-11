package com.hanmaum.backend.global.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.servlet.ServletContext;
import java.util.List;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OAuthLoginOpenApiConfiguration {
  @Bean
  OpenApiCustomizer oauthLoginContract(
      ObjectProvider<ClientRegistrationRepository> clients, ServletContext servletContext) {
    return api -> {
      var registrations = clients.getIfAvailable();
      if (registrations == null) return;
      var providers =
          List.of("google", "kakao").stream()
              .filter(provider -> registrations.findByRegistrationId(provider) != null)
              .toList();
      if (providers.isEmpty()) return;

      String links =
          providers.stream()
              .map(
                  provider ->
                      "- ["
                          + (provider.equals("google") ? "Google" : "Kakao")
                          + " 로그인 시작]("
                          + servletContext.getContextPath()
                          + "/oauth2/authorization/"
                          + provider
                          + ")")
              .collect(Collectors.joining("\n"));
      var operation =
          new Operation()
              .operationId("startSocialLogin")
              .addTagsItem("Auth")
              .summary("소셜 로그인 시작")
              .description(
                  """
              로그인할 제공자를 선택하여 브라우저를 해당 주소로 이동시킵니다. 로그인 세션과 CSRF 토큰은 필요하지 않습니다.

              **로그인은 아래 링크를 브라우저에서 열어 진행해주세요.**
              Swagger의 Try it out은 API 요청을 실행하므로 로그인 화면으로 브라우저를 이동시키지 않습니다.

              %s

              1. BE가 302 응답의 Location 헤더로 제공자 로그인·동의 화면을 안내합니다. 공통 JSON 응답은 없습니다.
              2. 제공자가 `/login/oauth2/code/{provider}`로 돌아오면 BE가 회원을 연결하고 로그인 세션을 설정합니다. 콜백을 직접 호출할 필요는 없습니다.
              3. 로그인 성공 시 설정된 FE 주소로 이동합니다. 같은 브라우저에서 Swagger로 돌아와 `GET /api/users/me`로 확인할 수 있습니다.

              로그인 후 변경 API를 실행하려면 `GET /api/auth/csrf`로 토큰을 다시 받고 Authorize의 CsrfToken에 입력해주세요.
              """
                      .formatted(links)
                      .strip())
              .security(List.of())
              .addParametersItem(
                  new PathParameter()
                      .name("provider")
                      .required(true)
                      .description("현재 서버에 설정된 소셜 로그인 제공자")
                      .schema(new StringSchema()._enum(providers))
                      .example(providers.getFirst()))
              .responses(
                  new ApiResponses()
                      .addApiResponse(
                          "302",
                          new ApiResponse()
                              .description("제공자 로그인 화면으로 이동. 공통 JSON 응답 본문 없음")
                              .addHeaderObject(
                                  "Location",
                                  new Header()
                                      .description("제공자의 인증 요청 URL. 브라우저가 이동할 주소")
                                      .schema(new StringSchema().format("uri")))));
      // Spring Security owns the route. This is documentation only, not another MVC handler.
      api.getPaths().addPathItem("/oauth2/authorization/{provider}", new PathItem().get(operation));
    };
  }
}
