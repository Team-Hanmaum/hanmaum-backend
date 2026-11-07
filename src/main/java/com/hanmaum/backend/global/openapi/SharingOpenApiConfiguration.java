package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.sharing.code.SharingErrorCode;
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
public class SharingOpenApiConfiguration {
  @Bean
  OpenApiCustomizer sharingContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (ErrorCode code :
          List.of(
              CommonErrorCode.VERSION_CONFLICT,
              CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
              CommonErrorCode.REQUEST_IN_PROGRESS,
              SharingErrorCode.SHARE_TARGET_UNAVAILABLE)) {
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
              "CREATE_SHARE_LINK_CONFLICT",
              new ApiResponse()
                  .description("항목 버전·대상 상태·유효한 확정 변경·반복 요청 충돌")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/spaces/{spaceId}/share-links");
      if (path != null && path.getPost() != null) {
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
        path.getPost().getRequestBody().setRequired(true);
      }
      var schemas = api.getComponents().getSchemas();
      var body = schemas.get("CreateShareLinkBody");
      if (body != null) {
        body.setRequired(List.of("clientRequestId", "targets"));
        body.setAdditionalProperties(false);
        ((Schema<?>) body.getProperties().get("targets")).setMinItems(1);
      }
      var target = schemas.get("ShareLinkTargetBody");
      if (target != null) {
        target.setRequired(List.of("itemId", "expectedItemVersion", "includesChange"));
        target.setAdditionalProperties(false);
      }
    };
  }
}
