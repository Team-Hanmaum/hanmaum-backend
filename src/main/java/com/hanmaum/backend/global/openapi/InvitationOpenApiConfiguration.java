package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.global.code.CommonErrorCode;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class InvitationOpenApiConfiguration {
  @Bean
  OpenApiCustomizer invitationContract() {
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
        body.put("timestamp", "2026-10-11T00:00:00Z");
        media.addExamples(code.code(), new Example().summary(code.message()).value(body));
      }
      api.getComponents()
          .addResponses(
              "INVITATION_COMMAND_CONFLICT",
              new ApiResponse()
                  .description("요청 ID 재사용 · 동일 요청 처리 중")
                  .content(new Content().addMediaType("application/json", media)));
      for (String path :
          List.of("/api/spaces/{spaceId}/invitations", "/api/invitations/{invitationToken}/join")) {
        var item = api.getPaths().get(path);
        if (item != null && item.getPost() != null) {
          item.getPost()
              .setSecurity(
                  List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
        }
      }
    };
  }
}
