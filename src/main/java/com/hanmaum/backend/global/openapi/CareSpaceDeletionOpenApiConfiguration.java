package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.global.code.*;
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
public class CareSpaceDeletionOpenApiConfiguration {
  @Bean
  OpenApiCustomizer careSpaceDeletionContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (ErrorCode code :
          List.of(
              CommonErrorCode.VERSION_CONFLICT,
              CommonErrorCode.DELETION_IMPACT_CHANGED,
              CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
              CommonErrorCode.REQUEST_IN_PROGRESS)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        body.put("errors", List.of());
        body.put("timestamp", "2026-11-07T12:00:00Z");
        media.addExamples(code.code(), new Example().value(body));
      }
      api.getComponents()
          .addResponses(
              "DELETE_CARE_SPACE_CONFLICT",
              new ApiResponse()
                  .description("공간 버전·삭제 영향·반복 요청 충돌")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/spaces/{spaceId}/deletions");
      if (path != null && path.getPost() != null) {
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
        path.getPost().getRequestBody().setRequired(true);
      }
      var schema = api.getComponents().getSchemas().get("CareSpaceDeletionBody");
      if (schema != null) {
        schema.setRequired(List.of("expectedSpaceVersion", "impactToken", "clientRequestId"));
        schema.setAdditionalProperties(false);
      }
    };
  }
}
