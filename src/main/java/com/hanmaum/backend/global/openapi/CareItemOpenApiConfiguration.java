package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.global.code.CommonErrorCode;
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
public class CareItemOpenApiConfiguration {
  @Bean
  OpenApiCustomizer careItemContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (var code :
          List.of(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, CommonErrorCode.REQUEST_IN_PROGRESS)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        body.put("errors", List.of());
        body.put("timestamp", "2026-10-14T00:00:00Z");
        media.addExamples(code.code(), new Example().value(body));
      }
      api.getComponents()
          .addResponses(
              "CREATE_CARE_ITEM_CONFLICT",
              new ApiResponse()
                  .description("요청 ID 재사용·동일 요청 처리 중")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/spaces/{spaceId}/items");
      if (path != null && path.getPost() != null)
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      for (String suffix : List.of("/{itemId}", "/{itemId}/status")) {
        var edit = api.getPaths().get("/api/spaces/{spaceId}/items" + suffix);
        if (edit != null && edit.getPatch() != null)
          edit.getPatch()
              .setSecurity(
                  List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      }
      var request = api.getComponents().getSchemas().get("CreateCareItemRequest");
      if (request != null) {
        request.setRequired(List.of("itemType", "title", "fields", "clientRequestId"));
        var itemType = new StringSchema();
        itemType.setEnum(List.of("SCHEDULE", "TASK", "QUESTION"));
        request.addProperty("itemType", itemType);
      }
    };
  }
}
