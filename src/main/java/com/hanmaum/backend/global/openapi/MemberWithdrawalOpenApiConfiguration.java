package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.global.code.*;
import com.hanmaum.backend.user.code.UserErrorCode;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class MemberWithdrawalOpenApiConfiguration {
  /** Documents ownership rejection, request conflicts, strict input, and session/CSRF together. */
  @Bean
  OpenApiCustomizer memberWithdrawalContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (ErrorCode code :
          List.of(
              UserErrorCode.OWNED_SPACE_REMAINS,
              CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
              CommonErrorCode.REQUEST_IN_PROGRESS)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        body.put("errors", List.of());
        body.put("timestamp", "2026-11-08T08:00:00Z");
        media.addExamples(code.code(), new Example().value(body));
      }
      api.getComponents()
          .addResponses(
              "MEMBER_WITHDRAWAL_CONFLICT",
              new ApiResponse()
                  .description("소유 공간 정리 필요·반복 요청 충돌")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/users/me/withdrawals");
      if (path != null && path.getPost() != null) {
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
        path.getPost().getRequestBody().setRequired(true);
      }
      var schema = api.getComponents().getSchemas().get("MemberWithdrawalBody");
      if (schema != null) {
        schema.setRequired(List.of("clientRequestId"));
        schema.setAdditionalProperties(false);
      }
    };
  }
}
